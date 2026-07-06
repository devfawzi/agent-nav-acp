package com.agentnav.claude

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend stream-json pour Claude Code CLI.
 *
 * 1 instance = 1 process claude = 1 sessionId. Aucun multiplexage interne. Tous les events
 * arrivés sur stdout sont routés directement vers les callbacks de l'interface AgentBackend
 * — pas de broadcast à N consommateurs, pas de filtre par sid.
 *
 * Cycle de vie :
 *   - constructeur : génère le preAssignedSid (UUID v4 ou resumeSid)
 *   - start() : spawn `claude --output-format stream-json --session-id <preAssignedSid> ...`
 *   - les callbacks sont invoqués au fil des events claude
 *   - stop() : kill propre + cleanup VFS listener
 *
 * Permissions :
 *   - Mode SAFE par défaut (`--permission-mode acceptEdits --permission-prompt-tool stdio`)
 *     → claude demande pour Bash et MCP via `sdk_control_request`
 *   - Mode TRUST (settings) → `--dangerously-skip-permissions`, aucune demande
 *   - AutoAllow whitelist : Read/Grep/Glob et Bash read-only (ls/cat/git status/…)
 *   - Timeout 120s : si l'user ne clique rien, auto-deny pour débloquer claude
 *
 * Routing strict : la requête de permission contient `sessionId = this.sessionId` (notre
 * preAssignedSid). Le panel qui possède CE backend reçoit directement `onPermission(req)`
 * sans passer par une queue ou un filtre.
 */
class ClaudeCliBackend(
    private val project: Project,
    private val profile: AgentProfile,
    resumeSid: String? = null,
    private val cwdOverride: String? = null
) : AgentBackend {

    private val log = thisLogger()
    private val pluginLog get() = PluginLogService.getInstance(project)

    // ═══════════════════════════════════════════════════════════════════════
    // État public exposé par AgentBackend
    // ═══════════════════════════════════════════════════════════════════════

    /** UUID pré-assigné côté plugin, passé à claude via `--session-id`. Stable jusqu'à
     *  un respawn éventuel (set_effort, bypass) qui re-resume avec le même sid. */
    @Volatile
    private var _sessionId: String = resumeSid ?: UUID.randomUUID().toString()
    override val sessionId: String? get() = _sessionId

    @Volatile
    private var _state: AgentState = AgentState.STOPPED
    override val state: AgentState get() = _state

    @Volatile
    private var _config: SessionConfig = defaultConfig()
    override val config: SessionConfig get() = _config

    @Volatile
    private var _usage: UsageStats = UsageStats()
    override val usage: UsageStats get() = _usage

    override var onStateChange: ((AgentState) -> Unit)? = null
    override var onSessionReady: ((String) -> Unit)? = null
    override var onTextChunk: ((String) -> Unit)? = null
    override var onThoughtChunk: ((String) -> Unit)? = null
    override var onToolCall: ((ToolCallInfo) -> Unit)? = null
    override var onPermission: ((PermissionRequest) -> Unit)? = null
    override var onExecuting: ((Boolean) -> Unit)? = null
    override var onConfigChange: ((SessionConfig) -> Unit)? = null
    override var onUsage: ((UsageStats) -> Unit)? = null
    override var onInfo: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onStderr: ((String) -> Unit)? = null
    override var onToolResultError: ((String) -> Unit)? = null
    override var onMemoryPaths: ((Map<String, String>) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════════════
    // État interne (process & parsing)
    // ═══════════════════════════════════════════════════════════════════════

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var handler: OSProcessHandler? = null
    private var writer: BufferedWriter? = null
    private val lineBuffer = StringBuilder()

    /** Override courant du modèle (null = défaut). Préservé à travers les respawns. */
    @Volatile private var modelOverride: String? = null
    /** Override du permission mode (plan / bypassPermissions). */
    @Volatile private var permissionModeOverride: String? = null
    /** Override du thinking effort (low/medium/high/xhigh/max). */
    @Volatile private var effortOverride: String? = null

    /** True entre un sendPrompt et le result event. Utilisé par le VFS listener. */
    @Volatile private var executing: Boolean = false

    /** request_id → callback pour les control_request envoyés (set_model, etc.). */
    private val pendingControlRequests = ConcurrentHashMap<String, (Boolean, String?) -> Unit>()

    /** path → contenu BEFORE pré-capturé lors d'un tool_use Write/Edit, à confronter au VFS. */
    private val toolCallPreCapturedBefore = ConcurrentHashMap<String, String>()

    /** Connection au MessageBus VFS, nettoyée à stop(). */
    private var vfsConnection: MessageBusConnection? = null

    /** Sid de resume initial (null si nouvelle session). Conservé pour les respawns. */
    private val initialResumeSid: String? = resumeSid

    // ═══════════════════════════════════════════════════════════════════════
    // Cycle de vie
    // ═══════════════════════════════════════════════════════════════════════

    override fun start() {
        if (_state != AgentState.STOPPED && _state != AgentState.ERROR) {
            log.info("Cli backend already started (state=$_state)")
            return
        }
        setState(AgentState.STARTING)
        spawn(resumeSid = initialResumeSid)
    }

    override fun stop() {
        try { handler?.destroyProcess() } catch (_: Exception) {}
        handler = null
        writer = null
        vfsConnection?.disconnect()
        vfsConnection = null
        scope.cancel()
        setState(AgentState.STOPPED)
    }

    /**
     * Spawn (ou respawn pour migration model/effort) le process claude avec les bons args.
     * Si resumeSid != null, on passe `--resume <sid>` pour continuer la conv ; sinon
     * `--session-id <preAssignedSid>` pour nouvelle session.
     */
    private fun spawn(resumeSid: String?) {
        try {
            val (resolvedCmd, resolvedArgs) = AgentBinaryResolver.resolveProfileCommand(profile)
            val exeFile = File(resolvedCmd)
            val claudeBin = if (exeFile.isAbsolute && exeFile.canExecute()) {
                resolvedCmd
            } else {
                AgentBinaryResolver.resolveClaudeCli()
                    ?: AgentBinaryResolver.resolveCommandInPath(resolvedCmd)
                    ?: run {
                        notifyError("Cannot find '$resolvedCmd'. Install Claude Code CLI first.")
                        setState(AgentState.ERROR)
                        return
                    }
            }

            // Construire les args : `acceptEdits + permission-prompt-tool stdio` par défaut
            // pour exposer les cards Allow/Deny. Mode TRUST (settings) → swap pour
            // --dangerously-skip-permissions.
            val trustMode = AgentSettings.getInstance().trustSession
            val safeArgs = listOf(
                "--permission-mode", "acceptEdits",
                "--permission-prompt-tool", "stdio"
            )
            val baseArgs = if (permissionModeOverride != null) {
                val mutable = resolvedArgs.toMutableList()
                mutable.removeAll { it == "--dangerously-skip-permissions" }
                val idx = mutable.indexOf("--permission-mode")
                if (idx >= 0 && idx + 1 < mutable.size) {
                    mutable[idx + 1] = permissionModeOverride!!
                } else {
                    mutable += listOf("--permission-mode", permissionModeOverride!!)
                }
                if (permissionModeOverride == "bypassPermissions" &&
                    !mutable.contains("--dangerously-skip-permissions")) {
                    mutable += "--dangerously-skip-permissions"
                }
                mutable.toList()
            } else if (trustMode) {
                resolvedArgs
            } else {
                // Mode safe : retire --dangerously-skip-permissions et ajoute les safe args
                // SEULEMENT s'ils ne sont pas déjà présents (sinon duplicate flags qui peuvent
                // perturber le parser CLI claude).
                val mutable = resolvedArgs.toMutableList()
                mutable.removeAll { it == "--dangerously-skip-permissions" }
                if (!mutable.contains("--permission-mode")) {
                    mutable += "--permission-mode"
                    mutable += "acceptEdits"
                }
                if (!mutable.contains("--permission-prompt-tool")) {
                    mutable += "--permission-prompt-tool"
                    mutable += "stdio"
                }
                mutable.toList()
            }

            val mcpConfigPath = AgentSettings.getInstance().getMcpConfigPathOrNull()
            val argsForLaunch = buildList {
                if (resumeSid != null) {
                    add("--resume"); add(resumeSid)
                } else {
                    add("--session-id"); add(_sessionId)
                }
                if (mcpConfigPath != null) {
                    add("--mcp-config"); add(mcpConfigPath)
                }
                if (modelOverride != null) {
                    add("--model"); add(modelOverride!!)
                }
                if (effortOverride != null && effortOverride != "auto") {
                    add("--effort"); add(effortOverride!!)
                }
                addAll(baseArgs)
            }

            val stdbufPath = "/usr/bin/stdbuf".takeIf { File(it).canExecute() }
                ?: AgentBinaryResolver.resolveCommandInPath("stdbuf")

            val command = GeneralCommandLine().apply {
                if (stdbufPath != null) {
                    this.exePath = stdbufPath
                    addParameters("-oL", "-eL", claudeBin)
                } else {
                    this.exePath = claudeBin
                }
                addParameters(argsForLaunch)
                val effectiveCwd = cwdOverride ?: project.basePath
                effectiveCwd?.let { workDirectory = File(it) }
                withRedirectErrorStream(false)
                environment["PATH"] = buildEnrichedPath(File(claudeBin).parentFile?.absolutePath)
                environment["NO_COLOR"] = "1"
                environment["TERM"] = "dumb"
            }

            log.info("Starting Claude CLI: ${command.commandLineString}")
            pluginLog.info("backend",
                "🚀 SPAWN Claude CLI | trustMode=$trustMode | profile=${profile.displayName} | sid=$_sessionId | args=${argsForLaunch.joinToString(" ")}")
            val proc = OSProcessHandler(command)
            this.handler = proc
            this.writer = proc.processInput.bufferedWriter(StandardCharsets.UTF_8)

            proc.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when (outputType) {
                        ProcessOutputType.STDOUT -> handleStdout(event.text)
                        ProcessOutputType.STDERR -> handleStderr(event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    log.info("Claude CLI process terminated (exit ${event.exitCode}) sid=$_sessionId")
                    if (_state != AgentState.STOPPED) {
                        setState(if (event.exitCode == 0) AgentState.STOPPED else AgentState.ERROR)
                    }
                    setExecuting(false)
                }
            })

            proc.startNotify()
            setState(AgentState.READY)

            // Abonnement VFS pour tracker les écritures Claude → diff inline + PendingChanges.
            subscribeVfs()

            // Notifie le panel : le sid est connu (et stable pour cette session).
            pluginLog.info("backend",
                "🟢 ClaudeCliBackend spawned: sid=$_sessionId resumeSid=$resumeSid")
            onSessionReady?.invoke(_sessionId)
        } catch (e: Exception) {
            log.error("Failed to spawn Claude CLI", e)
            notifyError("Failed to spawn Claude CLI: ${e.message}")
            setState(AgentState.ERROR)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Actions publiques
    // ═══════════════════════════════════════════════════════════════════════

    override fun sendPrompt(text: String, attachments: List<PromptAttachment>) {
        val w = writer ?: run {
            notifyError("Claude CLI not running")
            return
        }
        if (_state != AgentState.READY) {
            notifyError("Claude CLI not ready (state=$_state)")
            return
        }

        val historyService = project.getService(PromptHistoryService::class.java)
        historyService.startPrompt(text, _sessionId)
        toolCallPreCapturedBefore.clear()
        setExecuting(true)

        val contentArr = mutableListOf<String>()
        contentArr.add("""{"type":"text","text":${escapeJson(text)}}""")
        for (att in attachments) {
            when (att) {
                is PromptAttachment.Image -> {
                    contentArr.add(
                        """{"type":"image","source":{"type":"base64","media_type":${escapeJson(att.mimeType)},"data":${escapeJson(att.base64Data)}}}"""
                    )
                }
                is PromptAttachment.FileLink -> {
                    contentArr.add("""{"type":"text","text":${escapeJson(" @${att.absolutePath}")}}""")
                }
                is PromptAttachment.CodeRef -> {
                    val lang = att.language.orEmpty()
                    val block = buildString {
                        append("\n[Code reference from ")
                        append(att.absolutePath)
                        append(":")
                        append(att.lineRange)
                        append("]\n```")
                        append(lang)
                        append("\n")
                        append(att.content)
                        append("\n```\n")
                    }
                    contentArr.add("""{"type":"text","text":${escapeJson(block)}}""")
                }
            }
        }
        val contentJson = contentArr.joinToString(",", prefix = "[", postfix = "]")
        val msg = """{"type":"user","message":{"role":"user","content":$contentJson}}"""

        try {
            w.write("$msg\n")
            w.flush()
            log.info("CLI prompt sent sid=$_sessionId (${text.take(80)}…)")
        } catch (e: Exception) {
            log.error("CLI write failed", e)
            notifyError("Failed to send prompt: ${e.message}")
            setExecuting(false)
        }
    }

    override fun cancel() {
        val w = writer ?: return
        val requestId = "interrupt-${System.currentTimeMillis()}"
        val msg = """{"type":"control_request","request_id":${escapeJson(requestId)},"request":{"subtype":"interrupt"}}"""
        log.info("Sending interrupt to CLI sid=$_sessionId")
        var written = false
        try {
            w.write("$msg\n"); w.flush(); written = true
        } catch (e: Exception) {
            log.warn("CLI interrupt write failed, falling back to destroyProcess", e)
        }
        if (!written) {
            try { handler?.destroyProcess() } catch (_: Exception) {}
        }
        setExecuting(false)
    }

    override fun replyToolResult(toolUseId: String, content: String) {
        val w = writer ?: run {
            notifyError("Claude CLI not running")
            return
        }
        val msg = """{"type":"user","message":{"role":"user","content":[""" +
            """{"type":"tool_result","tool_use_id":${escapeJson(toolUseId)},""" +
            """"content":${escapeJson(content)}}""" +
            """]}}"""
        try {
            w.write("$msg\n"); w.flush()
            setExecuting(true)
            log.info("CLI tool_result reply sent toolUseId=$toolUseId (${content.take(60)}…)")
        } catch (e: Exception) {
            log.error("CLI tool_result write failed", e)
            notifyError("Failed to send reply: ${e.message}")
        }
    }

    override fun setMode(modeId: String) {
        val w = writer ?: return
        // bypassPermissions exige `--dangerously-skip-permissions` au lancement.
        // claude refuse le control_request set_permission_mode bypassPermissions sinon.
        // → respawn le process avec le flag.
        if (modeId == "bypassPermissions") {
            if (executing) {
                notifyError("Cannot switch to Bypass mode while Claude is running. Stop the current turn first.")
                return
            }
            permissionModeOverride = modeId
            updateConfig { it.copy(currentModeId = modeId) }
            respawn()
            return
        }
        val previousMode = _config.currentModeId
        val previousOverride = permissionModeOverride
        val requestId = "set-mode-${System.currentTimeMillis()}"
        pendingControlRequests[requestId] = { success, _ ->
            if (!success) {
                log.warn("CLI set_permission_mode rejected, rolling back to $previousMode")
                updateConfig { it.copy(currentModeId = previousMode) }
                permissionModeOverride = previousOverride
            }
        }
        val msg = """{"type":"control_request","request_id":${escapeJson(requestId)},""" +
            """"request":{"subtype":"set_permission_mode","mode":${escapeJson(modeId)}}}"""
        try {
            w.write("$msg\n"); w.flush()
            updateConfig { it.copy(currentModeId = modeId) }
            permissionModeOverride = modeId
            log.info("CLI set_permission_mode sent: $modeId")
        } catch (e: Exception) {
            pendingControlRequests.remove(requestId)
            log.warn("CLI set_permission_mode write failed", e)
            notifyError("Failed to set permission mode: ${e.message}")
        }
    }

    override fun setModel(modelId: String) {
        val w = writer ?: return
        val previousModel = _config.currentModelId
        val previousOverride = modelOverride
        val requestId = "set-model-${System.currentTimeMillis()}"
        pendingControlRequests[requestId] = { success, _ ->
            if (!success) {
                log.warn("CLI set_model rejected, rolling back to $previousModel")
                updateConfig { it.copy(currentModelId = previousModel) }
                modelOverride = previousOverride
            }
        }
        val msg = """{"type":"control_request","request_id":${escapeJson(requestId)},""" +
            """"request":{"subtype":"set_model","model":${escapeJson(modelId)}}}"""
        try {
            w.write("$msg\n"); w.flush()
            updateConfig { it.copy(currentModelId = modelId) }
            modelOverride = modelId
            log.info("CLI set_model sent: $modelId")
        } catch (e: Exception) {
            pendingControlRequests.remove(requestId)
            log.warn("CLI set_model write failed", e)
            notifyError("Failed to set model: ${e.message}")
        }
    }

    override fun setEffort(level: String) {
        // set_effort RESPAWN (claude stream-json refuse `set_effort` control_request).
        if (executing) {
            notifyError("Cannot change Effort while Claude is running. Stop the current turn first.")
            return
        }
        effortOverride = level
        updateConfig {
            it.copy(currentConfigValues = it.currentConfigValues + ("thinking" to level))
        }
        respawn()
    }

    /** Kill l'ancien process, spawn un nouveau avec --resume du même sid + overrides. */
    private fun respawn() {
        log.info("Respawning Claude CLI for sid=$_sessionId (model=$modelOverride, perm=$permissionModeOverride, effort=$effortOverride)")
        try { handler?.destroyProcess() } catch (_: Exception) {}
        handler = null
        writer = null
        vfsConnection?.disconnect()
        vfsConnection = null
        setState(AgentState.STARTING)
        // --resume <sid> préserve l'historique de la conv à travers le respawn.
        spawn(resumeSid = _sessionId)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Parsing stdout (stream-json)
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleStdout(text: String) {
        lineBuffer.append(text)
        while (lineBuffer.contains('\n')) {
            val nl = lineBuffer.indexOf('\n')
            val line = lineBuffer.substring(0, nl).trim()
            lineBuffer.delete(0, nl + 1)
            if (line.isEmpty()) continue
            if (line.startsWith("{")) {
                try {
                    val json = JsonParser.parseString(line).asJsonObject
                    handleEvent(json)
                } catch (e: Exception) {
                    log.warn("CLI parse fail (${e.message}): ${line.take(200)}")
                }
            } else {
                log.info("CLI non-json line: ${line.take(150)}")
            }
        }
    }

    private fun handleStderr(text: String) {
        val trimmed = text.trimEnd('\n', '\r')
        if (trimmed.isNotEmpty()) {
            log.warn("Claude CLI stderr: $trimmed")
            onStderr?.invoke(trimmed)
        }
    }

    private fun handleEvent(json: JsonObject) {
        val type = json.get("type")?.asString
        val subtype = json.get("subtype")?.asString
        // Trace TOUS les events claude pour diagnostique des hangs (INFO level pour qu'on voie
        // tout dans le Logger sans devoir changer le filtre).
        pluginLog.info("cli-event", "📨 type=$type subtype=$subtype sid=${_sessionId.take(8)}")
        when (type) {
            "system" -> handleSystemEvent(json)
            "assistant" -> handleAssistantEvent(json)
            "user" -> handleUserEvent(json)
            "result" -> handleResultEvent(json)
            "rate_limit_event" -> { /* ignore */ }
            "control_request" -> handleControlRequest(json)
            "sdk_control_request" -> handleSdkControlRequest(json)
            "control_response" -> handleControlResponse(json)
            "stream_event" -> {}
            else -> {
                pluginLog.warn("cli-event", "❓ unknown type=$type raw=${json.toString().take(300)}")
            }
        }
    }

    private fun handleSystemEvent(json: JsonObject) {
        val subtype = json.get("subtype")?.asString
        if (subtype == "status") {
            val mode = json.get("permissionMode")?.asString
            if (mode != null) {
                updateConfig { it.copy(currentModeId = mode) }
                permissionModeOverride = mode
                log.info("CLI system:status — permissionMode now $mode")
            }
            return
        }
        if (subtype != "init") return
        val sid = json.get("session_id")?.asString ?: return
        // Cas normal : claude utilise le preAssignedSid → sid == _sessionId. Si différent
        // (claude a régénéré), on adopte le nouveau pour que les events futurs collent.
        if (sid != _sessionId) {
            pluginLog.warn("backend",
                "Claude renvoyé un sid différent du preAssignedSid : était=$_sessionId, devient=$sid")
            _sessionId = sid
            onSessionReady?.invoke(sid)
        }

        val currentModel = json.get("model")?.asString
        val currentPermMode = json.get("permissionMode")?.asString
        val slashCommands = json.getAsJsonArray("slash_commands")
            ?.mapNotNull { it.asString }.orEmpty()
        val mcpServers = json.getAsJsonArray("mcp_servers")?.mapNotNull { el ->
            if (!el.isJsonObject) null
            else {
                val o = el.asJsonObject
                val name = o.get("name")?.asString ?: return@mapNotNull null
                val status = o.get("status")?.asString ?: "unknown"
                McpServerInfo(name, status)
            }
        }.orEmpty()
        val mcpTools = json.getAsJsonArray("tools")?.mapNotNull { it.asString }
            ?.filter { it.startsWith("mcp__") }.orEmpty()

        // Update cache MCP au niveau settings (utilisé par Settings → MCP inventory)
        val toolsByServer = mcpTools
            .mapNotNull { full ->
                val rest = full.removePrefix("mcp__")
                val server = rest.substringBefore("__")
                val action = rest.substringAfter("__", "")
                if (server.isEmpty() || action.isEmpty()) null else server to action
            }
            .groupBy({ it.first }, { it.second })
        if (toolsByServer.isNotEmpty()) {
            AgentSettings.getInstance().updateMcpToolsCache(toolsByServer)
        }

        val skills = json.getAsJsonArray("skills")?.mapNotNull { it.asString }.orEmpty()
        val agents = json.getAsJsonArray("agents")?.mapNotNull { it.asString }.orEmpty()
        val memoryPathsObj = json.getAsJsonObject("memory_paths")
        if (memoryPathsObj != null) {
            val paths = memoryPathsObj.entrySet().associate { (k, v) -> k to (v.asString ?: "") }
            onMemoryPaths?.invoke(paths)
        }

        // Persiste slash commands / skills / MCP servers pour peupler le popup `/` et le
        // picker /mcp AVANT le 1er prompt des prochaines sessions (parité TUI claude).
        AgentSettings.getInstance().apply {
            updateSlashCommandsCache(slashCommands)
            updateSkillsCache(skills)
            updateMcpServersCache(mcpServers.associate { it.name to it.status })
        }

        val newConfig = SessionConfig(
            models = CLAUDE_MODELS,
            modes = CLAUDE_PERMISSION_MODES,
            configOptions = listOf(CLAUDE_EFFORT_OPTION),
            currentModelId = currentModel ?: _config.currentModelId,
            currentModeId = currentPermMode ?: _config.currentModeId,
            currentConfigValues = _config.currentConfigValues,
            slashCommands = slashCommands,
            mcpServers = mcpServers,
            mcpTools = mcpTools,
            skills = skills,
            agents = agents
        )
        _config = newConfig
        onConfigChange?.invoke(newConfig)
        log.info("Claude CLI session ready: sid=$_sessionId model=$currentModel")
    }

    private fun handleAssistantEvent(json: JsonObject) {
        val message = json.getAsJsonObject("message") ?: return
        val content = message.getAsJsonArray("content") ?: return
        content.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val block = item.asJsonObject
            when (block.get("type")?.asString) {
                "text" -> {
                    val text = block.get("text")?.asString
                    if (!text.isNullOrEmpty()) onTextChunk?.invoke(text)
                }
                "thinking" -> {
                    val thinking = block.get("thinking")?.asString
                        ?: block.get("text")?.asString
                    if (!thinking.isNullOrEmpty()) onThoughtChunk?.invoke(thinking)
                }
                "tool_use" -> handleToolUse(block)
            }
        }
    }

    private fun handleUserEvent(json: JsonObject) {
        // Contient les tool_results — on les utilise pour marquer les tool_use comme completed
        val message = json.getAsJsonObject("message") ?: return
        val rawContent = message.get("content") ?: return
        // content STRING = sortie de commande locale (ex. "<local-command-stdout>Set model
        // to …</local-command-stdout>" après un set_model) — découvert par fixture 2.1.201,
        // getAsJsonArray levait une ClassCastException et la ligne était perdue.
        if (rawContent.isJsonPrimitive) {
            val text = rawContent.asString
                .removePrefix("<local-command-stdout>")
                .removeSuffix("</local-command-stdout>")
                .trim()
            if (text.isNotEmpty()) onInfo?.invoke(text)
            return
        }
        if (!rawContent.isJsonArray) return
        val content = rawContent.asJsonArray
        content.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val block = item.asJsonObject
            if (block.get("type")?.asString == "tool_result") {
                val toolUseId = block.get("tool_use_id")?.asString ?: return@forEach
                val isError = block.get("is_error")?.asBoolean == true
                if (isError) {
                    val errorContent = block.get("content")?.let {
                        if (it.isJsonPrimitive) it.asString
                        else if (it.isJsonArray) {
                            it.asJsonArray.joinToString("\n") { el ->
                                el.asJsonObject?.get("text")?.asString ?: ""
                            }
                        } else null
                    }
                    if (!errorContent.isNullOrBlank()) {
                        log.info("CLI tool error: $errorContent")
                        onToolResultError?.invoke(errorContent)
                    }
                }
                onToolCall?.invoke(ToolCallInfo(
                    toolCallId = toolUseId,
                    title = "tool",
                    kind = null,
                    status = if (isError) "error" else "completed",
                    path = null,
                    command = null,
                    sessionId = _sessionId
                ))
            }
        }
    }

    private fun handleResultEvent(json: JsonObject) {
        setExecuting(false)
        val usage = json.getAsJsonObject("usage")
        val cost = json.get("total_cost_usd")?.let {
            runCatching { it.asDouble }.getOrNull()
        } ?: 0.0
        val previousCost = _usage.totalCostUsd
        val newStats = _usage.copy(
            inputTokens = _usage.inputTokens + (usage?.get("input_tokens")?.asLong ?: 0L),
            outputTokens = _usage.outputTokens + (usage?.get("output_tokens")?.asLong ?: 0L),
            cacheReadTokens = _usage.cacheReadTokens + (usage?.get("cache_read_input_tokens")?.asLong ?: 0L),
            cacheCreationTokens = _usage.cacheCreationTokens + (usage?.get("cache_creation_input_tokens")?.asLong ?: 0L),
            totalCostUsd = cost.takeIf { it > _usage.totalCostUsd } ?: _usage.totalCostUsd,
            turnCount = _usage.turnCount + 1
        )
        _usage = newStats
        onUsage?.invoke(newStats)

        val deltaCost = (newStats.totalCostUsd - previousCost).coerceAtLeast(0.0)
        if (deltaCost > 0.0) {
            AgentSettings.getInstance().addToCurrentWeek(deltaCost)
        }

        val isError = json.get("is_error")?.asBoolean ?: false
        if (isError) {
            val errorsArr = json.getAsJsonArray("errors")
            val errMsg = if (errorsArr != null && errorsArr.size() > 0) {
                errorsArr.mapNotNull { it.asString }.joinToString("; ")
            } else {
                json.get("result")?.asString ?: "unknown error"
            }
            notifyError("Claude error: $errMsg")
            onToolResultError?.invoke(errMsg)
        }
    }

    private fun handleControlRequest(json: JsonObject) {
        pluginLog.info("control",
            "📨 control_request RAW: ${json.toString().take(800)}")

        val request = json.getAsJsonObject("request")
        val subtype = request?.get("subtype")?.asString

        // claude 2.1+ : nouveau format permission via control_request avec subtype="can_use_tool".
        // Le request_id est AU TOP-LEVEL (pas dans request), et l'input s'appelle "input" pas "tool_input".
        if (subtype == "can_use_tool") {
            val requestId = json.get("request_id")?.asString ?: run {
                pluginLog.warn("control", "can_use_tool sans request_id, ignoring")
                return
            }
            val toolName = request.get("tool_name")?.asString ?: "tool"
            val toolInput = request.get("input")
            val blockedPath = request.get("blocked_path")?.asString
            // permission_suggestions = règles que claude propose pour ne plus redemander
            // (scope "session" ou "localSettings"). On renvoie ces règles en updatedPermissions
            // si l'user choisit "Allow always".
            val permissionSuggestions = request.get("permission_suggestions")?.toString()
            pluginLog.info("control",
                "🔀 can_use_tool → permission flow | tool=$toolName req=$requestId blocked=$blockedPath suggestions=${permissionSuggestions != null}")
            dispatchPermission(requestId, toolName, toolInput, blockedPath, permissionSuggestions)
            return
        }

        // claude legacy : control_request subtype="permission" (rare, vieux format).
        if (subtype == "permission") {
            handleSdkControlRequest(json)
            return
        }

        // Fallback : control_request inconnu → réponse permissive pour ne pas bloquer.
        val requestId = json.get("request_id")?.asString ?: run {
            pluginLog.warn("control", "control_request without request_id, ignoring")
            return
        }
        val resp = """{"type":"control_response","request_id":${escapeJson(requestId)},"response":{"decision":"allow_once"}}"""
        try {
            writer?.write("$resp\n")
            writer?.flush()
            pluginLog.info("control", "✓ Replied allow_once to unknown req=$requestId subtype=$subtype")
        } catch (e: Exception) {
            log.warn("CLI control_response write failed", e)
        }
    }

    private fun handleSdkControlRequest(json: JsonObject) {
        pluginLog.info("permission",
            "🔵 sdk_control_request RAW: ${json.toString().take(800)}")
        val request = json.getAsJsonObject("request") ?: run {
            pluginLog.warn("permission", "🔴 sdk_control_request has no 'request' field")
            return
        }
        val subtype = request.get("subtype")?.asString
        if (subtype != "permission") {
            val requestId = request.get("request_id")?.asString ?: return
            respondPermission(requestId, allow = true, reason = null, originalInput = null, permissionSuggestionsJson = null)
            return
        }
        val requestId = request.get("request_id")?.asString ?: run {
            pluginLog.warn("permission", "🔴 sdk_control_request permission sans request_id")
            return
        }
        val toolName = request.get("tool_name")?.asString ?: "tool"
        val toolInput = request.get("tool_input")
        dispatchPermission(requestId, toolName, toolInput, blockedPath = null)
    }

    /**
     * Flow permission unifié pour les deux formats claude :
     *  - sdk_control_request avec request.subtype="permission" (legacy SDK)
     *  - control_request avec request.subtype="can_use_tool" (claude 2.1+)
     *
     * Format de réponse claude 2.1+ (Zod schema) :
     *  - allow : `{"behavior":"allow","updatedInput":<input>}`  ← updatedInput OBLIGATOIRE
     *  - deny  : `{"behavior":"deny","message":"..."}`
     * Le `updatedInput` permet à l'host de re-écrire l'input (ex: changer le path) ;
     * pour un allow simple, on renvoie l'input tel quel.
     */
    private fun dispatchPermission(
        requestId: String,
        toolName: String,
        toolInput: com.google.gson.JsonElement?,
        blockedPath: String?,
        permissionSuggestionsJson: String? = null
    ) {
        pluginLog.info("permission",
            "🔵 PERMISSION REQUEST: tool=$toolName req=$requestId sid=${_sessionId.take(8)} input=${toolInput?.toString()?.take(200)} blocked=$blockedPath")

        val responded = AtomicBoolean(false)
        fun allowOnce(reason: String? = null, withSuggestions: Boolean = false) {
            if (responded.compareAndSet(false, true)) {
                val sufx = if (withSuggestions) " + remember suggestions" else ""
                pluginLog.info("permission", "✅ ALLOW response sent: req=$requestId reason=$reason$sufx")
                respondPermission(
                    requestId, allow = true, reason = null, originalInput = toolInput,
                    permissionSuggestionsJson = if (withSuggestions) permissionSuggestionsJson else null
                )
            } else {
                pluginLog.warn("permission", "🟡 allowOnce called twice for req=$requestId (already responded)")
            }
        }
        fun denyOnce(reason: String?) {
            if (responded.compareAndSet(false, true)) {
                pluginLog.info("permission", "❌ DENY response sent: req=$requestId reason=$reason")
                respondPermission(requestId, allow = false, reason = reason, originalInput = toolInput,
                    permissionSuggestionsJson = null)
            }
        }

        val autoAllow = isAutoAllowedTool(toolName, toolInput)
        pluginLog.info("permission", "🔍 isAutoAllowedTool($toolName) = $autoAllow")
        // Cas particulier : si claude a bloqué un path (blockedPath != null), c'est un accès
        // hors sandbox → on demande toujours à l'user même si le tool est en théorie "safe".
        if (autoAllow && blockedPath == null) {
            log.info("Auto-allow safe tool: $toolName")
            allowOnce("safe-tool: $toolName")
            return
        }

        pluginLog.info("permission",
            "📤 Dispatching to UI handler (onPermission set=${onPermission != null})")

        val req = PermissionRequest(
            requestId = requestId,
            toolName = toolName,
            toolInput = toolInput?.toString(),
            sessionId = _sessionId,
            respondAllow = {
                pluginLog.info("permission", "👆 USER clicked ALLOW req=$requestId")
                allowOnce()
            },
            respondDeny = { reason ->
                pluginLog.info("permission", "👆 USER clicked DENY req=$requestId reason=$reason")
                denyOnce(reason)
            },
            respondAllowAlways = if (permissionSuggestionsJson != null) ({
                pluginLog.info("permission", "👆 USER clicked ALLOW ALWAYS req=$requestId")
                allowOnce(withSuggestions = true)
            }) else null,
            permissionSuggestionsJson = permissionSuggestionsJson
        )
        val handler = onPermission
        if (handler == null) {
            pluginLog.error("permission",
                "🔴🔴 NO onPermission HANDLER — auto-allow $toolName (BUG: panel didn't wire callback)")
            allowOnce("no-ui-handler")
            return
        }
        handler(req)
        pluginLog.info("permission", "📤 Handler invoked, awaiting user click...")

        scope.launch {
            delay(120_000)
            if (!responded.get()) {
                log.warn("Permission request $requestId for $toolName timed out → auto-deny")
                pluginLog.warn("permission",
                    "⏰ Timeout on $toolName req=$requestId → auto-deny after 120s")
                notifyError("Permission for '$toolName' timed out (no response in 120s) — denied")
                denyOnce("No response from user within 120s")
            }
        }
    }

    private fun handleControlResponse(json: JsonObject) {
        val requestId = json.get("request_id")?.asString
        val response = json.getAsJsonObject("response")
        val subtype = response?.get("subtype")?.asString
        val isError = subtype == "error" || (response != null && response.get("error") != null)
        val errMsg = if (isError) {
            response?.get("error")?.asString
                ?: response?.toString()
                ?: "unknown error"
        } else null

        if (isError) {
            log.warn("CLI control_response error for $requestId: $errMsg")
            pluginLog.error("control_response", "req=$requestId error=$errMsg")
            notifyError("Setting change failed: $errMsg")
        } else {
            log.info("CLI control_response OK for $requestId")
            pluginLog.info("control_response", "req=$requestId OK")
        }
        if (requestId != null) {
            val cb = pendingControlRequests.remove(requestId)
            cb?.invoke(!isError, errMsg)
        }
    }

    private fun handleToolUse(block: JsonObject) {
        val toolName = block.get("name")?.asString ?: return
        val toolUseId = block.get("id")?.asString
        val input = block.getAsJsonObject("input")

        val planContent = if (toolName == "ExitPlanMode") input?.get("plan")?.asString else null
        val userQuestionsJson = if (toolName == "AskUserQuestion") {
            input?.getAsJsonArray("questions")?.toString()
        } else null
        if (planContent != null || userQuestionsJson != null) {
            val info = ToolCallInfo(
                toolCallId = toolUseId,
                title = toolName,
                kind = "interactive",
                status = "in_progress",
                path = null,
                command = null,
                sessionId = _sessionId,
                planContent = planContent,
                userQuestionsJson = userQuestionsJson
            )
            onToolCall?.invoke(info)
            return
        }

        val path = input?.get("file_path")?.asString
            ?: input?.get("path")?.asString
            ?: input?.get("filePath")?.asString
        val command = input?.get("command")?.asString
        val writeContent = input?.get("content")?.asString
        val editOld = input?.get("old_string")?.asString
        val editNew = input?.get("new_string")?.asString
        val detail = when (toolName) {
            "Grep", "Glob" -> input?.get("pattern")?.asString?.let { pat ->
                val path2 = input.get("path")?.asString
                if (path2 != null) "$pat in $path2" else pat
            }
            "WebFetch" -> input?.get("url")?.asString
            "WebSearch" -> input?.get("query")?.asString
            "Task" -> {
                val desc = input?.get("description")?.asString
                val agentType = input?.get("subagent_type")?.asString
                val prompt = input?.get("prompt")?.asString?.take(80)
                when {
                    desc != null && agentType != null -> "$agentType — $desc"
                    desc != null -> desc
                    agentType != null -> "sub-agent: $agentType"
                    prompt != null -> prompt
                    else -> null
                }
            }
            "TodoWrite" -> input?.getAsJsonArray("todos")?.let { todos ->
                val active = (0 until todos.size())
                    .mapNotNull { todos[it].asJsonObject?.get("activeForm")?.asString }
                    .firstOrNull()
                if (active != null) "${todos.size()} todos — $active" else "${todos.size()} todo(s)"
            }
            "Skill" -> input?.get("skill")?.asString
            "Bash" -> command ?: input?.get("description")?.asString
            "Read", "Edit", "Write", "MultiEdit", "NotebookEdit", "NotebookRead" -> path
            "ToolSearch" -> input?.get("query")?.asString
            "AskUserQuestion" -> {
                val qs = input?.getAsJsonArray("questions")
                when {
                    qs == null || qs.size() == 0 -> "(no question)"
                    qs.size() == 1 -> qs[0].asJsonObject?.get("question")?.asString?.take(100) ?: "(question)"
                    else -> "${qs.size()} questions"
                }
            }
            "ExitPlanMode" -> {
                input?.get("plan")?.asString
                    ?.lineSequence()
                    ?.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?.take(80)
                    ?: "(plan submitted)"
            }
            "TaskCreate" -> input?.get("subject")?.asString
            "TaskUpdate" -> {
                val tid = input?.get("taskId")?.asString
                val status = input?.get("status")?.asString
                listOfNotNull(tid?.let { "#$it" }, status).joinToString(" → ")
                    .ifEmpty { null }
            }
            "TaskList", "TaskGet" -> input?.get("taskId")?.asString?.let { "#$it" }
            else -> {
                if (toolName.startsWith("mcp__")) {
                    input?.entrySet()
                        ?.joinToString(", ", limit = 3, truncated = "…") { "${it.key}=${it.value.toString().take(40)}" }
                } else {
                    // Fallback générique : 1er champ string non-vide de l'input
                    input?.entrySet()
                        ?.firstOrNull { it.value.isJsonPrimitive }
                        ?.let { "${it.key}=${it.value.toString().take(60)}" }
                }
            }
        }

        val permMode = permissionModeOverride ?: _config.currentModeId

        val kind = when (toolName) {
            "Write", "Edit", "MultiEdit" -> "edit"
            "Bash" -> "execute"
            "Read" -> "read"
            else -> null
        }
        val info = ToolCallInfo(
            toolCallId = toolUseId,
            title = toolName,
            kind = kind,
            status = "in_progress",
            path = path,
            command = command,
            sessionId = _sessionId,
            writeContent = writeContent,
            editOldString = editOld,
            editNewString = editNew,
            permissionMode = permMode,
            detail = detail
        )
        onToolCall?.invoke(info)

        // Pre-capture + retries refresh + fallback addOrUpdate
        if (path != null) {
            val tracked = shouldTrackFile(path)
            pluginLog.info("vfs",
                "📁 tool=$toolName path=$path tracked=$tracked basePath=${project.basePath}")
            if (tracked) {
                if (!toolCallPreCapturedBefore.containsKey(path)) {
                    val before = readFileContent(path)
                    toolCallPreCapturedBefore[path] = before
                    pluginLog.info("vfs", "📷 BEFORE captured (${before.length} chars) for $path")
                }
                if (toolName in setOf("Write", "Edit", "MultiEdit")) {
                    pluginLog.info("vfs", "⏰ scheduling VFS refresh + fallback for $path")
                    scheduleVfsRefreshAndFallback(path)
                }
            }
        }
    }

    private fun respondPermission(
        requestId: String,
        allow: Boolean,
        reason: String?,
        originalInput: com.google.gson.JsonElement?,
        permissionSuggestionsJson: String?
    ) {
        val w = writer ?: run {
            pluginLog.error("permission",
                "🔴🔴 writer is NULL when responding to req=$requestId — claude is gonna hang!")
            return
        }
        val behaviorPayload = if (allow) {
            val updatedInput = originalInput?.takeIf { it.isJsonObject }?.toString() ?: "{}"
            // Si l'user a choisi "Allow always", on inclut updatedPermissions = règles que claude
            // a suggérées. claude les applique pour le scope spécifié (session/localSettings).
            val permsClause = if (permissionSuggestionsJson != null) {
                ""","updatedPermissions":$permissionSuggestionsJson"""
            } else ""
            """"behavior":"allow","updatedInput":$updatedInput$permsClause"""
        } else {
            val msg = reason ?: "Denied by user"
            """"behavior":"deny","message":${escapeJson(msg)}"""
        }
        val resp = """{"type":"control_response","response":{"subtype":"success",""" +
            """"request_id":${escapeJson(requestId)},"response":{$behaviorPayload}}}"""
        pluginLog.debug("permission",
            "📤 WRITING response to claude stdin: $resp")
        try {
            w.write("$resp\n"); w.flush()
            log.info("Permission response sent: allow=$allow req=$requestId")
            pluginLog.info("permission",
                "✓ response flushed to claude stdin: allow=$allow req=$requestId")
        } catch (e: Exception) {
            log.warn("Failed to write permission response", e)
            pluginLog.error("permission",
                "🔴 Failed to write permission response: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VFS tracking
    // ═══════════════════════════════════════════════════════════════════════

    private fun subscribeVfs() {
        if (vfsConnection != null) return
        val conn = project.messageBus.connect()
        vfsConnection = conn
        conn.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: List<VFileEvent>) {
                // On ne track une écriture comme "pending change" QUE si CE backend est en train
                // d'exécuter un turn ET a précapturé un BEFORE pour le path (cf handleToolUse).
                // Sinon = écriture externe (autre process, autre backend, save manuel) → on ignore.
                if (!executing) return
                val history = project.getService(PromptHistoryService::class.java)
                if (!history.hasActivePrompt()) return
                val pending = project.getService(PendingChangesService::class.java)

                events.forEach { event ->
                    try {
                        when (event) {
                            is VFileContentChangeEvent -> {
                                if (event.isFromSave) return@forEach
                                val path = event.file.path
                                if (!shouldTrackFile(path)) return@forEach
                                if (pending.consumeRejectFlag(path)) return@forEach
                                if (!toolCallPreCapturedBefore.containsKey(path)) return@forEach
                                val before = toolCallPreCapturedBefore.remove(path)
                                    ?: String(event.file.contentsToByteArray())
                                history.captureFileBefore(path, before)
                            }
                            is VFileCreateEvent -> {
                                val path = "${event.parent.path}/${event.childName}"
                                if (!shouldTrackFile(path)) return@forEach
                                if (pending.consumeRejectFlag(path)) return@forEach
                                if (!toolCallPreCapturedBefore.containsKey(path)) return@forEach
                                val before = toolCallPreCapturedBefore.remove(path) ?: ""
                                history.captureFileBefore(path, before)
                            }
                            else -> {}
                        }
                    } catch (e: Exception) {
                        log.warn("VFS before handler failed for ${event.path}", e)
                    }
                }
            }

            override fun after(events: List<VFileEvent>) {
                if (!executing) return
                val history = project.getService(PromptHistoryService::class.java)
                if (!history.hasActivePrompt()) return
                val pending = project.getService(PendingChangesService::class.java)

                events.forEach { event ->
                    try {
                        when (event) {
                            is VFileContentChangeEvent -> {
                                if (event.isFromSave) return@forEach
                                val path = event.file.path
                                if (!shouldTrackFile(path)) return@forEach
                                val before = history.getFileContentAtPrompt(
                                    history.currentPromptId() ?: return@forEach,
                                    path
                                ) ?: return@forEach
                                val after = String(event.file.contentsToByteArray())
                                if (before == after) return@forEach
                                history.captureFileAfter(path, after)
                                pending.addOrUpdate(path, before, after, event.file, _sessionId)
                                project.getService(DiffViewerManager::class.java).scheduleRefresh()
                            }
                            is VFileCreateEvent -> {
                                val path = "${event.parent.path}/${event.childName}"
                                if (!shouldTrackFile(path)) return@forEach
                                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                                    ?: return@forEach
                                val after = String(vf.contentsToByteArray())
                                val before = history.getFileContentAtPrompt(
                                    history.currentPromptId() ?: return@forEach,
                                    path
                                ) ?: ""
                                history.captureFileAfter(path, after)
                                pending.addOrUpdate(path, before, after, vf, _sessionId)
                                project.getService(DiffViewerManager::class.java).scheduleRefresh()
                            }
                            else -> {}
                        }
                    } catch (e: Exception) {
                        log.warn("VFS after handler failed for ${event.path}", e)
                    }
                }
            }
        })
    }

    /**
     * Fallback : si le VFS ne fire pas après une écriture par claude (cas SCM/inotify lent ou
     * écriture hors VFS), on lit nous-mêmes le fichier après quelques retries et on crée le
     * PendingChange manuellement.
     */
    private fun scheduleVfsRefreshAndFallback(path: String) {
        scope.launch {
            val delays = listOf(200L, 800L)
            for ((i, d) in delays.withIndex()) {
                delay(d)
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                        vf?.refresh(false, false)
                    } catch (e: Exception) {
                        log.warn("CLI refresh #${i + 1} failed for $path", e)
                    }
                }
            }
            delay(400)
            ApplicationManager.getApplication().invokeLater {
                if (!shouldTrackFile(path)) {
                    pluginLog.warn("vfs", "🔴 fallback: $path not tracked anymore — skip addOrUpdate")
                    return@invokeLater
                }
                val before = toolCallPreCapturedBefore.remove(path) ?: run {
                    pluginLog.info("vfs",
                        "ℹ fallback: no precaptured BEFORE for $path (VFS event already consumed it)")
                    return@invokeLater
                }
                try {
                    val after = File(path).readText(Charsets.UTF_8)
                    if (before == after) {
                        pluginLog.info("vfs", "= fallback: before==after for $path (no actual change)")
                        return@invokeLater
                    }
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                        ?: run {
                            pluginLog.warn("vfs", "🔴 fallback: VirtualFile not found for $path")
                            return@invokeLater
                        }
                    val historyService = project.getService(PromptHistoryService::class.java)
                    val pending = project.getService(PendingChangesService::class.java)
                    historyService.captureFileBefore(path, before)
                    historyService.captureFileAfter(path, after)
                    pluginLog.info("vfs",
                        "✅ fallback addOrUpdate path=$path before=${before.length}c after=${after.length}c sid=${_sessionId.take(8)}")
                    pending.addOrUpdate(path, before, after, vf, _sessionId)
                    project.getService(DiffViewerManager::class.java).scheduleRefresh()
                } catch (e: Exception) {
                    log.warn("CLI fallback addOrUpdate failed for $path", e)
                    pluginLog.error("vfs", "🔴 fallback exception: ${e.message}")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun setState(s: AgentState) {
        if (_state == s) return
        _state = s
        onStateChange?.invoke(s)
    }

    private fun setExecuting(b: Boolean) {
        if (executing == b) return
        executing = b
        onExecuting?.invoke(b)
    }

    private fun notifyError(msg: String) {
        log.warn(msg)
        onError?.invoke(msg)
    }

    private fun updateConfig(transform: (SessionConfig) -> SessionConfig) {
        val newConfig = transform(_config)
        _config = newConfig
        onConfigChange?.invoke(newConfig)
    }

    private fun defaultConfig(): SessionConfig {
        // Cherche `--permission-mode <value>` dans les args du profile pour la valeur initiale.
        val args = profile.args
        val initialMode = args.indexOf("--permission-mode").let { idx ->
            if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else "acceptEdits"
        }
        // Le system:init n'arrive qu'après le 1er prompt : on pré-peuple slash commands,
        // skills et MCP depuis les caches persistés du dernier init + les builtins connus,
        // pour que le popup `/` et /mcp soient utilisables immédiatement (parité TUI).
        val settings = AgentSettings.getInstance()
        val cachedSlash = settings.getSlashCommandsCache()
        val cachedServers = settings.getMcpServersCache()
        val cachedTools = settings.getMcpToolsCache()
        return SessionConfig(
            models = CLAUDE_MODELS,
            modes = CLAUDE_PERMISSION_MODES,
            configOptions = listOf(CLAUDE_EFFORT_OPTION),
            currentModelId = null,
            currentModeId = initialMode,
            currentConfigValues = mapOf("thinking" to "auto"),
            slashCommands = (BUILTIN_SLASH_COMMANDS + cachedSlash).distinct().sorted(),
            mcpServers = cachedServers.map { (name, status) -> McpServerInfo(name, status) },
            mcpTools = cachedTools.flatMap { (srv, tools) -> tools.map { "mcp__${srv}__$it" } },
            skills = settings.getSkillsCache()
        )
    }

    private fun escapeJson(text: String): String {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }

    private fun shouldTrackFile(path: String): Boolean {
        val basePath = project.basePath ?: return false
        if (!path.startsWith(basePath)) return false
        val ignoredSegments = listOf(
            "/build/", "/.gradle/", "/.idea/", "/.git/",
            "/node_modules/", "/.intellijPlatform/",
            "/target/", "/out/", "/dist/", "/.next/",
            "/__pycache__/", "/venv/", "/.venv/"
        )
        return ignoredSegments.none { path.contains(it) }
    }

    private fun readFileContent(path: String): String {
        return try {
            File(path).readText(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun isAutoAllowedTool(toolName: String, toolInput: JsonElement?): Boolean {
        val safeTools = setOf("Read", "Grep", "Glob", "Task", "TodoWrite",
            "NotebookRead", "WebFetch", "WebSearch", "Skill", "ToolSearch",
            "ExitPlanMode", "AskUserQuestion", "TaskOutput", "TaskList", "TaskGet")
        if (toolName in safeTools) return true
        if (toolName == "Bash") {
            val cmd = toolInput?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("command")?.asString
                ?.trim()
                ?: return false
            return isSafeBashCommand(cmd)
        }
        return false
    }

    private fun isSafeBashCommand(cmd: String): Boolean {
        val dangerous = listOf(">", ">>", "|tee", " tee ", "sudo ", "rm ", "mv ", "cp ",
            "chmod ", "chown ", "kill ", "killall ", "dd ", "mkfs", "shutdown",
            "reboot", "halt", "curl ", "wget ", "fetch ", "scp ", "ssh ", "rsync ",
            "git push", "git reset --hard", "git clean", "git rebase", "git merge",
            "git checkout -B", "git commit", "git tag", "npm install", "pip install",
            "apt ", "apt-get", "yum ", "brew install", "docker run", "docker build")
        val lower = " " + cmd.lowercase() + " "
        if (dangerous.any { lower.contains(it) }) return false

        val parts = cmd.split(Regex("\\s*(\\||&&|;)\\s*"))
            .filter { it.isNotBlank() }
            .map { it.trim().substringBefore(' ').lowercase() }
        if (parts.isEmpty()) return false

        val safeBins = setOf(
            "ls", "pwd", "cd", "cat", "head", "tail", "wc", "echo", "printf",
            "grep", "egrep", "fgrep", "ag", "rg", "ripgrep", "find", "fd", "fdfind",
            "tree", "file", "stat", "du", "df", "which", "whereis", "type", "command",
            "uname", "whoami", "id", "hostname", "date", "uptime", "ps", "top", "htop",
            "free", "lscpu", "lsblk", "lsmod", "lsof", "env", "printenv", "set",
            "sort", "uniq", "cut", "awk", "sed", "tr", "rev", "fold", "diff", "cmp",
            "basename", "dirname", "realpath", "readlink", "true", "false",
            "git", "jq", "yq", "xq",
            "node", "python", "python3", "ruby", "perl",
            "less", "more"
        )
        for (bin in parts) {
            if (bin !in safeBins) return false
        }
        if (cmd.trim().startsWith("git ")) {
            val sub = cmd.trim().removePrefix("git ").trim().substringBefore(' ').lowercase()
            val safeGitVerbs = setOf("status", "log", "diff", "show", "branch", "tag",
                "remote", "config", "describe", "blame", "ls-files", "ls-tree", "rev-parse",
                "stash", "fetch", "shortlog", "reflog", "grep")
            if (sub !in safeGitVerbs) return false
            val args = cmd.trim().removePrefix("git ")
            if (Regex("\\s(--force|--delete|-D)\\b").containsMatchIn(" $args ")) return false
        }
        return true
    }

    private fun buildEnrichedPath(claudeBinDir: String?): String {
        val home = System.getProperty("user.home") ?: ""
        val candidates = mutableListOf<String>()
        claudeBinDir?.let { candidates.add(it) }
        candidates.addAll(listOf(
            "$home/.local/bin",
            "$home/.cargo/bin",
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
            "/snap/bin"
        ))
        val nvmRoot = File("$home/.nvm/versions/node")
        if (nvmRoot.isDirectory) {
            nvmRoot.listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name }
                ?.forEach { candidates.add("${it.absolutePath}/bin") }
        }
        val voltaBin = File("$home/.volta/bin")
        if (voltaBin.isDirectory) candidates.add(voltaBin.absolutePath)
        val pyenvShims = File("$home/.pyenv/shims")
        if (pyenvShims.isDirectory) candidates.add(pyenvShims.absolutePath)

        val existing = System.getenv("PATH")?.split(":")?.filter { it.isNotBlank() } ?: emptyList()
        val ordered = LinkedHashSet<String>()
        candidates.filter { File(it).isDirectory }.forEach { ordered.add(it) }
        existing.forEach { ordered.add(it) }
        return ordered.joinToString(":")
    }

    companion object {
        /** Liste curated des models Claude (le CLI n'expose pas la liste dispo via stream-json). */
        private val CLAUDE_MODELS = listOf(
            SelectOption("claude-opus-4-7", "Claude Opus 4.7"),
            SelectOption("claude-opus-4-7[1m]", "Claude Opus 4.7 (1M context)"),
            SelectOption("claude-opus-4-6", "Claude Opus 4.6"),
            SelectOption("claude-sonnet-4-6", "Claude Sonnet 4.6"),
            SelectOption("claude-sonnet-4-5", "Claude Sonnet 4.5"),
            SelectOption("claude-haiku-4-5", "Claude Haiku 4.5")
        )

        private val CLAUDE_PERMISSION_MODES = listOf(
            SelectOption("default", "Default", "Prompt for each tool use"),
            SelectOption("acceptEdits", "Accept edits", "Auto-approve Write/Edit"),
            SelectOption("bypassPermissions", "Bypass all", "Auto-approve everything (use with care)"),
            SelectOption("plan", "Plan", "Read-only — preview without writing to disk")
        )

        /**
         * Slash commands builtin toujours présentes (observées dans system:init claude 2.1.201).
         * Fallback si aucun cache : le popup `/` n'est jamais vide, même au tout 1er lancement.
         */
        private val BUILTIN_SLASH_COMMANDS = listOf(
            "compact", "context", "config", "usage", "init", "review",
            "security-review", "agents", "clear"
        )

        private val CLAUDE_EFFORT_OPTION = ConfigOption(
            id = "thinking",
            name = "Effort",
            type = "select",
            options = listOf(
                SelectOption("auto", "Auto", "Model default"),
                SelectOption("low", "Low"),
                SelectOption("medium", "Medium"),
                SelectOption("high", "High"),
                SelectOption("xhigh", "Extra High"),
                SelectOption("max", "Max")
            ),
            currentValue = "auto"
        )
    }
}
