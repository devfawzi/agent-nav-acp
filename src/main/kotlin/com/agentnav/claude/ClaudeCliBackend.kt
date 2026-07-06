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
    override var onSyntheticOutput: ((String) -> Unit)? = null
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
                // IMPROVEMENTS #8 : étend le sandbox filesystem (@/abs/path hors projet).
                AgentSettings.getInstance().getAdditionalDirs().forEach { dir ->
                    add("--add-dir"); add(dir)
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

    override fun renameSession(title: String) {
        val trimmed = title.trim().take(120)
        if (trimmed.isEmpty()) return
        val requestId = "rename-${System.currentTimeMillis()}"
        pendingControlRequests[requestId] = { success, err ->
            if (!success) log.warn("rename_session rejected: $err")
        }
        if (writeLine(ClaudeRequests.renameSession(requestId, trimmed))) {
            pluginLog.info("control", "✏️ rename_session sent: \"$trimmed\"")
        } else {
            pendingControlRequests.remove(requestId)
        }
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
            // Parser pur validé contre les fixtures (src/test/resources/fixtures/claude/) —
            // ne lève jamais : ParseError/Unknown sont des événements comme les autres.
            ClaudeStreamParser.parse(line).forEach { dispatchEvent(it) }
        }
    }

    /** Écrit une ligne sur stdin de claude. Retourne false si l'écriture échoue. */
    private fun writeLine(message: String): Boolean {
        val w = writer ?: return false
        return try {
            w.write(message); w.write("\n"); w.flush(); true
        } catch (e: Exception) {
            log.warn("CLI write failed", e); false
        }
    }

    private fun handleStderr(text: String) {
        val trimmed = text.trimEnd('\n', '\r')
        if (trimmed.isNotEmpty()) {
            log.warn("Claude CLI stderr: $trimmed")
            onStderr?.invoke(trimmed)
        }
    }

    /**
     * Route les événements typés du parser vers les EFFETS (état, callbacks UI, VFS, caches).
     * Toute l'extraction JSON vit dans ClaudeStreamParser — testée contre les fixtures.
     */
    private fun dispatchEvent(e: ClaudeEvent) {
        pluginLog.info("cli-event", "📨 ${e::class.simpleName} sid=${_sessionId.take(8)}")
        when (e) {
            is ClaudeEvent.Init -> handleInit(e)
            is ClaudeEvent.Status -> {
                if (e.permissionMode != null) {
                    updateConfig { it.copy(currentModeId = e.permissionMode) }
                    permissionModeOverride = e.permissionMode
                    log.info("CLI system:status — permissionMode now ${e.permissionMode}")
                }
            }
            is ClaudeEvent.Hook -> pluginLog.info("hooks", "${e.subtype}: ${e.hookName ?: "?"}")
            is ClaudeEvent.ThinkingTokens -> { /* jauge de contexte future — pas d'UI encore */ }
            is ClaudeEvent.SystemOther -> pluginLog.info("cli-event", "system:${e.subtype} (ignoré)")
            is ClaudeEvent.AssistantText ->
                if (e.isSynthetic) (onSyntheticOutput ?: onTextChunk)?.invoke(e.text)
                else onTextChunk?.invoke(e.text)
            is ClaudeEvent.AssistantThinking -> onThoughtChunk?.invoke(e.text)
            is ClaudeEvent.ToolUse -> handleToolUse(e)
            is ClaudeEvent.ToolResult -> {
                if (e.isError && !e.errorText.isNullOrBlank()) {
                    log.info("CLI tool error: ${e.errorText}")
                    onToolResultError?.invoke(e.errorText)
                }
                onToolCall?.invoke(ToolCallInfo(
                    toolCallId = e.toolUseId, title = "tool", kind = null,
                    status = if (e.isError) "error" else "completed",
                    path = null, command = null, sessionId = _sessionId
                ))
            }
            is ClaudeEvent.LocalCommandOutput -> onInfo?.invoke(e.text)
            is ClaudeEvent.TurnResult -> handleTurnResult(e)
            is ClaudeEvent.ControlResponse -> handleControlResponseEvent(e)
            is ClaudeEvent.CanUseTool -> {
                pluginLog.info("control",
                    "🔀 can_use_tool → permission flow | tool=${e.toolName} req=${e.requestId} legacy=${e.legacy} blocked=${e.blockedPath}")
                dispatchPermission(e.requestId, e.toolName, e.input, e.blockedPath, e.permissionSuggestionsJson)
            }
            is ClaudeEvent.UnknownControlRequest -> {
                if (e.requestId != null && writeLine(ClaudeRequests.allowOnceDecision(e.requestId))) {
                    pluginLog.info("control", "✓ allow_once to unknown control_request subtype=${e.subtype}")
                }
            }
            ClaudeEvent.RateLimit, ClaudeEvent.StreamEvent -> {}
            is ClaudeEvent.Unknown -> {
                pluginLog.warn("protocol", "❓ unknown event type=${e.type} raw=${e.raw}")
                onInfo?.invoke("⚠ unhandled event: ${e.type}")
            }
            is ClaudeEvent.ParseError ->
                pluginLog.warn("protocol", "parse-fail: ${e.message} raw=${e.raw}")
        }
    }

    private fun handleInit(e: ClaudeEvent.Init) {
        val sid = e.sessionId ?: return
        // Cas normal : claude utilise le preAssignedSid → sid == _sessionId. Si différent
        // (claude a régénéré), on adopte le nouveau pour que les events futurs collent.
        if (sid != _sessionId) {
            pluginLog.warn("backend",
                "Claude renvoyé un sid différent du preAssignedSid : était=$_sessionId, devient=$sid")
            _sessionId = sid
            onSessionReady?.invoke(sid)
        }

        // Update cache MCP au niveau settings (utilisé par Settings → MCP inventory)
        val toolsByServer = e.mcpTools
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

        // Persiste slash commands / skills / MCP servers pour peupler le popup `/` et le
        // picker /mcp AVANT le 1er prompt des prochaines sessions (parité TUI claude).
        AgentSettings.getInstance().apply {
            updateSlashCommandsCache(e.slashCommands)
            updateSkillsCache(e.skills)
            updateMcpServersCache(e.mcpServers.associate { it.name to it.status })
        }

        if (e.memoryPaths.isNotEmpty()) onMemoryPaths?.invoke(e.memoryPaths)

        val newConfig = SessionConfig(
            models = CLAUDE_MODELS,
            modes = CLAUDE_PERMISSION_MODES,
            configOptions = listOf(CLAUDE_EFFORT_OPTION),
            currentModelId = e.model ?: _config.currentModelId,
            currentModeId = e.permissionMode ?: _config.currentModeId,
            currentConfigValues = _config.currentConfigValues,
            slashCommands = e.slashCommands,
            mcpServers = e.mcpServers,
            mcpTools = e.mcpTools,
            skills = e.skills,
            agents = e.agents
        )
        _config = newConfig
        onConfigChange?.invoke(newConfig)
        log.info("Claude CLI session ready: sid=$_sessionId model=${e.model}")
    }

    // (extraction assistant/user/result → ClaudeStreamParser ; ici il ne reste que les effets)

    private fun handleTurnResult(e: ClaudeEvent.TurnResult) {
        setExecuting(false)
        val previousCost = _usage.totalCostUsd
        val newStats = _usage.copy(
            inputTokens = _usage.inputTokens + e.inputTokens,
            outputTokens = _usage.outputTokens + e.outputTokens,
            cacheReadTokens = _usage.cacheReadTokens + e.cacheReadTokens,
            cacheCreationTokens = _usage.cacheCreationTokens + e.cacheCreationTokens,
            totalCostUsd = e.totalCostUsd.takeIf { it > _usage.totalCostUsd } ?: _usage.totalCostUsd,
            turnCount = _usage.turnCount + 1
        )
        _usage = newStats
        onUsage?.invoke(newStats)

        val deltaCost = (newStats.totalCostUsd - previousCost).coerceAtLeast(0.0)
        if (deltaCost > 0.0) {
            AgentSettings.getInstance().addToCurrentWeek(deltaCost)
        }

        if (e.isError) {
            val errMsg = e.errorMessage ?: "unknown error"
            notifyError("Claude error: $errMsg")
            onToolResultError?.invoke(errMsg)
        }
    }

    // (extraction control_request/sdk_control_request → ClaudeStreamParser.CanUseTool)

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

    private fun handleControlResponseEvent(e: ClaudeEvent.ControlResponse) {
        if (!e.success) {
            log.warn("CLI control_response error for ${e.requestId}: ${e.error}")
            pluginLog.error("control_response", "req=${e.requestId} error=${e.error}")
            notifyError("Setting change failed: ${e.error}")
        } else {
            pluginLog.info("control_response", "req=${e.requestId} OK")
        }
        e.requestId?.let { pendingControlRequests.remove(it)?.invoke(e.success, e.error) }
    }

    private fun handleToolUse(e: ClaudeEvent.ToolUse) {
        // Mapping riche (detail par tool, plan/questions, write/edit content) → ToolCallMapper,
        // pur et testé contre les fixtures. Ici : uniquement les effets VFS.
        val info = ToolCallMapper.fromToolUse(
            e, _sessionId, permissionModeOverride ?: _config.currentModeId
        )
        onToolCall?.invoke(info)

        val path = info.path ?: return
        val tracked = shouldTrackFile(path)
        pluginLog.info("vfs",
            "📁 tool=${e.name} path=$path tracked=$tracked basePath=${project.basePath}")
        if (!tracked) return
        if (!toolCallPreCapturedBefore.containsKey(path)) {
            val before = readFileContent(path)
            toolCallPreCapturedBefore[path] = before
            pluginLog.info("vfs", "📷 BEFORE captured (${before.length} chars) for $path")
        }
        if (e.name in setOf("Write", "Edit", "MultiEdit")) {
            pluginLog.info("vfs", "⏰ scheduling VFS refresh + fallback for $path")
            scheduleVfsRefreshAndFallback(path)
        }
    }

    private fun respondPermission(
        requestId: String,
        allow: Boolean,
        reason: String?,
        originalInput: com.google.gson.JsonElement?,
        permissionSuggestionsJson: String?
    ) {
        // Schéma wire golden-testé dans ClaudeRequests (updatedInput obligatoire au allow,
        // updatedPermissions pour "Allow always").
        val resp = if (allow) {
            ClaudeRequests.permissionAllow(requestId, originalInput, permissionSuggestionsJson)
        } else {
            ClaudeRequests.permissionDeny(requestId, reason)
        }
        if (writeLine(resp)) {
            pluginLog.info("permission",
                "✓ response flushed to claude stdin: allow=$allow req=$requestId")
        } else {
            pluginLog.error("permission",
                "🔴 Failed to write permission response req=$requestId — claude is gonna hang!")
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
