package com.claudeacp

import com.claudeacp.core.AgentState
import com.claudeacp.core.ConfigOption
import com.claudeacp.core.McpServerInfo
import com.claudeacp.core.PermissionRequest
import com.claudeacp.core.SelectOption
import com.claudeacp.core.SessionConfig
import com.claudeacp.core.ToolCallInfo
import com.claudeacp.core.UsageStats
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Alias court pour le legacy code qui référence State sans qualifier. */
typealias State = AgentState

/**
 * Service principal qui gère deux transports selon le profile actif :
 *  - CLI_STREAM_JSON (Claude Code) : spawn `claude --output-format stream-json` direct.
 *    Utilise le plan d'abonnement interactif Claude (pas l'API).
 *  - ACP (OpenCode et agents custom) : protocole JSON-RPC standard ACP
 *    (initialize → session/new → session/prompt → session/update).
 *
 * Intercepte les écritures de fichiers via VFS pour afficher les diffs dans le pending
 * changes panel + diff viewer, quel que soit le transport.
 */
@Service(Service.Level.PROJECT)
class ClaudeACPService(private val project: Project) {

    private val log = thisLogger()
    private var processHandler: OSProcessHandler? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lineBuffer = StringBuilder()

    @Volatile
    var state: State = State.STOPPED
        private set

    @Volatile
    var sessionId: String? = null
        private set

    /** Le sessionId du prompt actuellement en cours d'exécution. */
    @Volatile
    var currentExecutingSessionId: String? = null
        private set

    @Volatile
    var isPromptExecuting: Boolean = false
        private set

    private val nextRequestId = AtomicLong(1)
    private val pendingRequests = mutableMapOf<Long, (JsonObject) -> Unit>()

    private val messageListeners = mutableListOf<(JsonObject) -> Unit>()
    private val stderrListeners = mutableListOf<(String) -> Unit>()
    private val infoListeners = mutableListOf<(String) -> Unit>()
    private val errorListeners = mutableListOf<(String) -> Unit>()
    private val messageChunkListeners = mutableListOf<(String, String?) -> Unit>()
    private val thoughtChunkListeners = mutableListOf<(String, String?) -> Unit>()
    private val toolCallListeners = mutableListOf<(ToolCallInfo) -> Unit>()
    private val stateListeners = mutableListOf<(State) -> Unit>()
    private val sessionConfigListeners = mutableListOf<(String?, SessionConfig) -> Unit>()
    private val sessionCreatedListeners = mutableListOf<(String) -> Unit>()
    private val executingListeners = mutableListOf<(Boolean, String?) -> Unit>()
    /** Notifié quand un tool_result a is_error=true (ex: Bash bloqué par les permissions claude). */
    private val toolResultErrorListeners = mutableListOf<(message: String, sessionId: String?) -> Unit>()

    fun addToolResultErrorListener(l: (String, String?) -> Unit) { toolResultErrorListeners.add(l) }

    private val sessionUsage = ConcurrentHashMap<String, UsageStats>()
    private val usageListeners = mutableListOf<(sessionId: String, UsageStats) -> Unit>()

    fun getSessionUsage(sid: String?): UsageStats =
        sid?.let { sessionUsage[it] } ?: UsageStats()

    fun addUsageListener(l: (String, UsageStats) -> Unit) { usageListeners.add(l) }
    fun removeUsageListener(l: (String, UsageStats) -> Unit) { usageListeners.remove(l) }

    /** Config par session (chaque chat a son propre sessionConfig). */
    private val sessionConfigs = ConcurrentHashMap<String, SessionConfig>()

    fun getSessionConfig(sid: String?): SessionConfig {
        if (sid != null) sessionConfigs[sid]?.let { return it }
        // Fallback : en CLI mode, on retourne la config par défaut hardcoded (modèles, modes,
        // effort) pour que les dropdowns soient utilisables AVANT le 1er prompt (l'event
        // system:init de claude n'arrive qu'après le 1er user message envoyé).
        return if (activeProfile.transport == Transport.CLI_STREAM_JSON) defaultCliSessionConfig()
        else SessionConfig()
    }

    /** Config CLI par défaut : listes hardcoded + valeurs initiales depuis les args de spawn. */
    fun defaultCliSessionConfig(): SessionConfig {
        // Cherche `--permission-mode <value>` dans les args du profile pour la valeur initiale.
        val args = activeProfile.args
        val initialMode = args.indexOf("--permission-mode").let { idx ->
            if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else "acceptEdits"
        }
        return SessionConfig(
            models = CLAUDE_MODELS,
            modes = CLAUDE_PERMISSION_MODES,
            configOptions = listOf(CLAUDE_EFFORT_OPTION),
            currentModelId = null,  // claude utilise son default tant qu'on n'a pas claim
            currentModeId = initialMode,
            currentConfigValues = mapOf("thinking" to "auto")
        )
    }

    /** Chemins de mémoire auto chargés par claude (exposé dans system:init.memory_paths). */
    @Volatile
    var lastMemoryPaths: Map<String, String> = emptyMap()
        private set

    private val pendingVfsChanges = ConcurrentHashMap<String, String>()
    private val toolCallPreCapturedBefore = ConcurrentHashMap<String, String>()
    private val pathsByToolCallId = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        subscribeToVfsChanges()
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

    private fun subscribeToVfsChanges() {
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun before(events: List<VFileEvent>) {
                    val history = project.getService(PromptHistoryService::class.java)
                    val pending = project.getService(PendingChangesService::class.java)
                    // CRITIQUE : ne track que si CETTE instance d'IntelliJ a un prompt en cours.
                    if (currentExecutingSessionId == null) return
                    if (!history.hasActivePrompt()) return

                    events.forEach { event ->
                        try {
                            when (event) {
                                is VFileContentChangeEvent -> {
                                    if (event.isFromSave) return@forEach
                                    val path = event.file.path
                                    if (!shouldTrackFile(path)) return@forEach
                                    if (pending.consumeRejectFlag(path)) return@forEach
                                    // ISOLATION : on ne tracke un VFS change comme pending QUE si on
                                    // a précapturé un BEFORE via un tool_use de notre agent. Sinon =
                                    // écriture externe (autre process: claude terminal, opencode
                                    // séparé, save manuel, autre IDE) → on ignore. Évite que les
                                    // modifs externes contaminent le pending d'un chat actif.
                                    if (!toolCallPreCapturedBefore.containsKey(path)) return@forEach
                                    if (!pendingVfsChanges.containsKey(path)) {
                                        val before = toolCallPreCapturedBefore.remove(path)
                                            ?: String(event.file.contentsToByteArray())
                                        pendingVfsChanges[path] = before
                                        history.captureFileBefore(path, before)
                                    }
                                }
                                is VFileCreateEvent -> {
                                    val path = "${event.parent.path}/${event.childName}"
                                    if (!shouldTrackFile(path)) return@forEach
                                    if (pending.consumeRejectFlag(path)) return@forEach
                                    // Idem VFileContentChangeEvent : on n'accepte la création QUE si
                                    // un tool_use Write/Edit de notre agent a annoncé ce path.
                                    if (!toolCallPreCapturedBefore.containsKey(path)) return@forEach
                                    if (!pendingVfsChanges.containsKey(path)) {
                                        val initial = toolCallPreCapturedBefore.remove(path) ?: ""
                                        pendingVfsChanges[path] = initial
                                        history.captureFileBefore(path, initial)
                                    }
                                }
                                else -> {}
                            }
                        } catch (e: Exception) {
                            log.warn("VFS before capture failed for ${event.path}", e)
                        }
                    }
                }

                override fun after(events: List<VFileEvent>) {
                    val history = project.getService(PromptHistoryService::class.java)
                    val pending = project.getService(PendingChangesService::class.java)
                    // Cf. before() : on filtre par sid actif de CETTE instance.
                    if (currentExecutingSessionId == null) return
                    if (!history.hasActivePrompt()) return

                    events.forEach { event ->
                        val file = event.file ?: return@forEach
                        val path = file.path
                        if (!shouldTrackFile(path)) return@forEach

                        val before = pendingVfsChanges.remove(path)
                        if (pending.consumeRejectFlag(path)) return@forEach
                        if (before == null) return@forEach

                        try {
                            // Lecture directe disque pour bypasser le cache VFS qui peut renvoyer
                            // du contenu obsolète si l'agent écrit très vite (cas OpenCode).
                            val after = try {
                                java.io.File(path).readText(Charsets.UTF_8)
                            } catch (e: Exception) {
                                String(file.contentsToByteArray())
                            }
                            if (before == after) return@forEach

                            history.captureFileAfter(path, after)
                            log.info("VFS after addOrUpdate path=$path before=${before.length}c after=${after.length}c sid=$currentExecutingSessionId profile=${activeProfile.id}")
                            pending.addOrUpdate(path, before, after, file, currentExecutingSessionId)
                            project.getService(DiffViewerManager::class.java).scheduleRefresh()
                        } catch (e: Exception) {
                            log.warn("VFS after capture failed for $path", e)
                        }
                    }
                }
            }
        )
    }

    /** Profile actif. */
    @Volatile
    var activeProfile: AgentProfile = AgentProfile.CLAUDE_CODE
        private set

    fun switchAgent(profile: AgentProfile) {
        log.info("Switching agent to: ${profile.displayName} (${profile.id})")
        if (state != State.STOPPED) {
            stopAgent()
        }
        sessionId = null
        currentExecutingSessionId = null
        sessionConfigs.clear()
        pendingRequests.clear()
        pendingVfsChanges.clear()
        toolCallPreCapturedBefore.clear()
        pathsByToolCallId.clear()
        lineBuffer.clear()
        setExecuting(false)
        activeProfile = profile
        agentSwitchedListeners.toList().forEach { it(profile) }
        startAgent()
    }

    private val agentSwitchedListeners = mutableListOf<(AgentProfile) -> Unit>()
    fun addAgentSwitchedListener(l: (AgentProfile) -> Unit) { agentSwitchedListeners.add(l) }

    fun startAgent(): Boolean {
        if (state != State.STOPPED && state != State.ERROR) {
            return state == State.READY
        }
        setState(State.STARTING)
        sessionId = null
        pendingRequests.clear()
        lineBuffer.clear()

        activeProfile = AgentProfilesService.getInstance().getActiveProfile()
        project.getService(DiffViewerManager::class.java)

        // Branche CLI direct (claude --output-format stream-json), pas d'ACP / JSON-RPC.
        if (activeProfile.transport == Transport.CLI_STREAM_JSON) {
            return startCliAgent()
        }

        return try {
            val (resolvedCmd, resolvedArgs) = AgentBinaryResolver.resolveProfileCommand(activeProfile)
            val exeFile = File(resolvedCmd)
            val exePath = if (exeFile.isAbsolute && exeFile.canExecute()) {
                resolvedCmd
            } else if (resolvedCmd == "npx") {
                AgentBinaryResolver.resolveNpx() ?: run {
                    notifyError("Cannot find npx for profile '${activeProfile.displayName}'. Install Node.js.")
                    setState(State.ERROR)
                    return false
                }
            } else {
                AgentBinaryResolver.resolveCommandInPath(resolvedCmd) ?: run {
                    notifyError("Command '$resolvedCmd' not found for profile '${activeProfile.displayName}'.")
                    setState(State.ERROR)
                    return false
                }
            }

            val command = GeneralCommandLine().apply {
                this.exePath = exePath
                addParameters(resolvedArgs)
                project.basePath?.let { workDirectory = File(it) }
                withRedirectErrorStream(false)
                File(exePath).parentFile?.absolutePath?.let { binDir ->
                    val currentPath = System.getenv("PATH") ?: ""
                    environment["PATH"] = "$binDir:$currentPath"
                }
            }

            log.info("Starting ${activeProfile.displayName}: ${command.commandLineString}")

            val newHandler = OSProcessHandler(command)
            processHandler = newHandler
            newHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    // Si ce handler n'est plus le courant (ex: après switchAgent qui a killé
                    // l'ancien process), on ignore son output — il appartient à un agent mort.
                    if (newHandler !== processHandler) return
                    when (outputType) {
                        ProcessOutputType.STDOUT -> handleStdout(event.text)
                        ProcessOutputType.STDERR -> handleStderr(event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    // Ignore les callbacks stales (ex: ancien Claude killé par switchAgent
                    // pendant qu'OpenCode démarre — sinon on écrasait le state à ERROR).
                    if (newHandler !== processHandler) {
                        log.info("Ignoring stale processTerminated (exit ${event.exitCode})")
                        return
                    }
                    log.warn("Agent terminated (exit ${event.exitCode})")
                    setState(State.ERROR)
                }
            })

            processHandler!!.startNotify()
            setState(State.INITIALIZING)
            sendInitialize()
            true
        } catch (e: Exception) {
            log.error("Failed to start ACP agent", e)
            notifyError("Failed to start: ${e.message}")
            setState(State.ERROR)
            false
        }
    }

    /**
     * Prérequis minimal : Claude Code CLI installé. Node.js/npx n'est plus requis pour
     * Claude (on utilise le binaire `claude` direct). npx reste utile pour OpenCode via
     * npx, mais c'est optionnel — l'user peut choisir un autre agent.
     */
    data class Prerequisites(
        val claudeCliPath: String?,
        val claudeConfigDir: String?,
        val npxPath: String?
    ) {
        val allOk: Boolean get() = claudeCliPath != null
        val missing: List<String> = buildList {
            if (claudeCliPath == null) add("Claude Code CLI")
        }
    }

    fun checkPrerequisites(): Prerequisites = Prerequisites(
        claudeCliPath = AgentBinaryResolver.resolveClaudeCli(),
        claudeConfigDir = "${System.getProperty("user.home")}/.claude".takeIf { File(it).isDirectory },
        npxPath = AgentBinaryResolver.resolveNpx()
    )

    private fun handleStdout(text: String) {
        lineBuffer.append(text)
        while (lineBuffer.contains('\n')) {
            val newlineIndex = lineBuffer.indexOf('\n')
            val line = lineBuffer.substring(0, newlineIndex).trim()
            lineBuffer.delete(0, newlineIndex + 1)
            if (line.isEmpty()) continue

            if (line.startsWith("{")) {
                try {
                    val json = JsonParser.parseString(line).asJsonObject
                    handleAcpMessage(json)
                } catch (e: Exception) {
                    log.warn("Failed to parse JSON line (len=${line.length}): ${e.message} — line=${line.take(200)}...${line.takeLast(100)}", e)
                    notifyInfo("(parse-fail) ${line.take(120)}…")
                }
            } else {
                notifyInfo("(stdout) $line")
            }
        }
    }

    private fun handleStderr(text: String) {
        val trimmed = text.trimEnd('\n', '\r')
        if (trimmed.isNotEmpty()) {
            log.warn("ACP stderr: $trimmed")
            stderrListeners.toList().forEach { it(trimmed) }
        }
    }

    private fun handleAcpMessage(json: JsonObject) {
        messageListeners.toList().forEach { it(json) }

        val method = json.get("method")?.asString
        val idElem = json.get("id")?.takeIf { !it.isJsonNull }
        val id = idElem?.let { runCatching { it.asLong }.getOrNull() }
        val hasResult = json.has("result")
        val hasError = json.has("error")

        when {
            id != null && method == null && (hasResult || hasError) -> {
                val handler = pendingRequests.remove(id)
                handler?.invoke(json) ?: log.warn("Unhandled response id=$id")
            }
            id != null && method != null -> {
                when (method) {
                    "fs/write_text_file", "fs/writeTextFile" -> handleFileWrite(json)
                    "fs/read_text_file", "fs/readTextFile" -> handleFileRead(json)
                    "session/request_permission" -> handlePermissionRequest(json)
                    else -> {
                        log.warn("Unhandled agent request: $method id=$id")
                        sendRawMessage("""{"jsonrpc":"2.0","id":$id,"result":null}""")
                    }
                }
            }
            method != null -> {
                when (method) {
                    "session/update" -> handleSessionUpdate(json)
                    else -> log.info("Notification: $method")
                }
            }
        }
    }

    private fun sendInitialize() {
        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) {
                notifyError("initialize failed: ${response.get("error")}")
                setState(State.ERROR)
            } else {
                setState(State.CREATING_SESSION)
                sendNewSession()
            }
        }

        val msg = """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":""" +
            """{"protocolVersion":1,"clientCapabilities":""" +
            """{"fs":{"readTextFile":true,"writeTextFile":true}},""" +
            """"clientInfo":{"name":"IntelliJ-ACP-Bridge","version":"0.1.0"}}}"""
        sendRawMessage(msg)
    }

    private fun sendNewSession() {
        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) {
                notifyError("session/new failed: ${response.get("error")}")
                setState(State.ERROR)
            } else {
                val result = response.getAsJsonObject("result")
                val sid = result?.get("sessionId")?.asString
                if (sid.isNullOrEmpty()) {
                    notifyError("session/new returned no sessionId. Raw: $result")
                    setState(State.ERROR)
                } else {
                    sessionId = sid
                    parseSessionCapabilities(result, sid)
                    setState(State.READY)
                    val snapshot = sessionCreatedListeners.toList()
                    snapshot.forEach { it(sid) }
                }
            }
        }

        val cwd = (project.basePath ?: System.getProperty("user.home"))
            .replace("\\", "\\\\").replace("\"", "\\\"")
        val msg = """{"jsonrpc":"2.0","id":$id,"method":"session/new","params":""" +
            """{"cwd":"$cwd","mcpServers":[]}}"""
        sendRawMessage(msg)
    }

    fun newSession(onCreated: ((String) -> Unit)? = null) {
        if (activeProfile.transport == Transport.CLI_STREAM_JSON) {
            newCliSession(onCreated)
            return
        }
        if (state != State.READY && state != State.CREATING_SESSION) {
            notifyInfo("Cannot create new session, agent not ready (state=$state)")
            return
        }
        if (onCreated != null) {
            sessionCreatedListeners.add(object : (String) -> Unit {
                override fun invoke(sid: String) {
                    onCreated(sid)
                    sessionCreatedListeners.remove(this)
                }
            })
        }
        sessionId = null
        sendNewSession()
    }

    fun sendPrompt(
        promptText: String,
        targetSessionId: String? = null,
        attachments: List<PromptAttachment> = emptyList()
    ) {
        if (activeProfile.transport == Transport.CLI_STREAM_JSON) {
            sendCliPrompt(promptText, targetSessionId, attachments)
            return
        }
        if (state != State.READY && targetSessionId == null) {
            notifyError("Cannot send prompt: state=$state")
            return
        }
        val sid = targetSessionId ?: sessionId ?: run {
            notifyError("No active sessionId")
            return
        }

        val historyService = project.getService(PromptHistoryService::class.java)
        historyService.startPrompt(promptText, sid)
        pendingVfsChanges.clear()
        toolCallPreCapturedBefore.clear()
        currentExecutingSessionId = sid
        setExecuting(true)

        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) {
                notifyError("session/prompt failed: ${response.get("error")}")
            }
            setExecuting(false)
        }

        val parts = mutableListOf<String>()
        parts.add("""{"type":"text","text":${escapeJson(promptText)}}""")
        for (att in attachments) {
            when (att) {
                is PromptAttachment.FileLink -> {
                    val uri = "file://" + att.absolutePath
                    val mime = att.mimeType?.let { ""","mimeType":${escapeJson(it)}""" } ?: ""
                    parts.add(
                        """{"type":"resource_link","uri":${escapeJson(uri)},"name":${escapeJson(att.displayName)}$mime}"""
                    )
                }
                is PromptAttachment.Image -> {
                    parts.add(
                        """{"type":"image","data":${escapeJson(att.base64Data)},"mimeType":${escapeJson(att.mimeType)}}"""
                    )
                }
                is PromptAttachment.CodeRef -> {
                    // ACP n'a pas de type code natif → on en fait un bloc texte avec contexte.
                    val lang = att.language.orEmpty()
                    val block = "\n[Code reference from ${att.absolutePath}:${att.lineRange}]\n" +
                        "```$lang\n${att.content}\n```\n"
                    parts.add("""{"type":"text","text":${escapeJson(block)}}""")
                }
            }
        }
        val promptArray = parts.joinToString(",", prefix = "[", postfix = "]")
        val msg = """{"jsonrpc":"2.0","id":$id,"method":"session/prompt","params":""" +
            """{"sessionId":"$sid","prompt":$promptArray}}"""
        sendRawMessage(msg)
    }

    fun cancelPrompt(targetSessionId: String? = null) {
        if (activeProfile.transport == Transport.CLI_STREAM_JSON) {
            cancelCliPrompt(targetSessionId)
            return
        }
        val sid = targetSessionId ?: currentExecutingSessionId ?: sessionId ?: return
        val msg = """{"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"$sid"}}"""
        sendRawMessage(msg)
    }

    private fun setExecuting(value: Boolean) {
        if (isPromptExecuting == value) return
        isPromptExecuting = value
        val sid = currentExecutingSessionId
        executingListeners.toList().forEach { it(value, sid) }
        // Quand le prompt se termine, on clear le sid actif et on close le prompt courant
        // dans l'history. Sinon le VFS listener continue à enregistrer comme s'il y avait
        // toujours un prompt → pollution des pending changes des autres instances IDE.
        if (!value) {
            project.getService(PromptHistoryService::class.java).endPrompt()
            currentExecutingSessionId = null
        }
    }

    fun addExecutingListener(l: (Boolean, String?) -> Unit) { executingListeners.add(l) }

    private fun handlePermissionRequest(json: JsonObject) {
        val id = json.get("id")?.asLong ?: return
        val params = json.getAsJsonObject("params")
        val options = params?.getAsJsonArray("options")

        val chosen = options?.firstOrNull {
            val obj = it.asJsonObject
            val name = (obj.get("name")?.asString ?: "").lowercase()
            val kind = (obj.get("kind")?.asString ?: "").lowercase()
            "allow" in name || "allow" in kind
        }?.asJsonObject ?: options?.firstOrNull()?.asJsonObject

        val optionId = chosen?.get("optionId")?.asString ?: "allow"
        val resp = """{"jsonrpc":"2.0","id":$id,"result":""" +
            """{"outcome":{"outcome":"selected","optionId":"$optionId"}}}"""
        sendRawMessage(resp)
        notifyInfo("Auto-accepted permission ($optionId)")
    }

    private fun handleFileWrite(json: JsonObject) {
        val params = json.getAsJsonObject("params") ?: return
        val filepath = params.get("path")?.asString ?: return
        val newContent = params.get("content")?.asString ?: return

        val historyService = project.getService(PromptHistoryService::class.java)
        scope.launch {
            val beforeContent = readFileContent(filepath)
            historyService.captureFileBefore(filepath, beforeContent)
            delay(200)
            historyService.captureFileAfter(filepath, newContent)
            ApplicationManager.getApplication().invokeLater {
                VirtualFileManager.getInstance().asyncRefresh(null)
                project.getService(DiffViewerManager::class.java).showDiff(
                    filepath, beforeContent, newContent,
                    "Claude: ${File(filepath).name}"
                )
            }
        }

        val id = json.get("id")?.takeIf { !it.isJsonNull }?.asLong
        if (id != null) {
            sendRawMessage("""{"jsonrpc":"2.0","id":$id,"result":null}""")
        }
    }

    private fun handleFileRead(json: JsonObject) {
        val id = json.get("id")?.asLong ?: return
        val params = json.getAsJsonObject("params")
        val filepath = params?.get("path")?.asString
        val content = if (filepath != null) readFileContent(filepath) else ""
        val resp = """{"jsonrpc":"2.0","id":$id,"result":{"content":${escapeJson(content)}}}"""
        sendRawMessage(resp)
    }

    private fun handleSessionUpdate(json: JsonObject) {
        val params = json.getAsJsonObject("params") ?: return
        val sid = params.get("sessionId")?.asString
        val update = params.getAsJsonObject("update") ?: return
        val type = update.get("sessionUpdate")?.asString ?: return

        val text = extractTextFromUpdate(update)

        when (type) {
            "agent_message_chunk", "agentMessageChunk" -> {
                if (!text.isNullOrEmpty()) messageChunkListeners.toList().forEach { it(text, sid) }
            }
            "agent_thought_chunk", "agentThoughtChunk" -> {
                if (!text.isNullOrEmpty()) thoughtChunkListeners.toList().forEach { it(text, sid) }
            }
            "tool_call", "tool_call_update", "toolCall", "toolCallUpdate" -> {
                handleToolCall(update, sid)
            }
            "usage_update", "usageUpdate", "available_commands_update" -> {}
            "current_mode_update", "currentModeUpdate" -> {
                val modeId = update.get("currentModeId")?.asString
                    ?: update.get("modeId")?.asString
                if (modeId != null && sid != null) {
                    updateSessionConfig(sid) { it.copy(currentModeId = modeId) }
                }
            }
            else -> {
                if (!text.isNullOrEmpty()) {
                    messageChunkListeners.toList().forEach { it("[$type] $text", sid) }
                }
            }
        }
    }

    private fun handleToolCall(update: JsonObject, sid: String? = null) {
        val toolCallId = update.get("toolCallId")?.asString
        val status = update.get("status")?.asString

        val title = update.get("title")?.asString
            ?: update.get("kind")?.asString
            ?: "tool"

        val kind = update.get("kind")?.asString
            ?: update.getAsJsonObject("toolCall")?.get("kind")?.asString
        val rawInput = update.getAsJsonObject("rawInput")
        val pathFromInput = rawInput?.get("file_path")?.asString
            ?: rawInput?.get("path")?.asString
            ?: rawInput?.get("filePath")?.asString
        val command = rawInput?.get("command")?.asString

        val paths = mutableSetOf<String>()
        update.getAsJsonArray("content")?.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val obj = item.asJsonObject
            if (obj.get("type")?.asString == "diff") {
                obj.get("path")?.asString?.let { paths.add(it) }
            }
        }
        update.getAsJsonArray("locations")?.forEach { item ->
            if (!item.isJsonObject) return@forEach
            item.asJsonObject.get("path")?.asString?.let { paths.add(it) }
        }

        // Détail secondaire pour OpenCode/ACP (même logique que CLI handleCliToolUse) :
        // pattern Grep, url WebFetch, description Task, todos count, etc. Sans ça l'UI
        // affiche juste "Task" ou "TodoWrite" sans rien.
        val detail = when (title) {
            "Grep", "Glob" -> rawInput?.get("pattern")?.asString
            "WebFetch" -> rawInput?.get("url")?.asString
            "WebSearch" -> rawInput?.get("query")?.asString
            "Task" -> rawInput?.get("description")?.asString
                ?: rawInput?.get("subagent_type")?.asString
                ?: rawInput?.get("prompt")?.asString?.take(80)
            "TodoWrite" -> {
                val todos = rawInput?.getAsJsonArray("todos")
                if (todos != null) "${todos.size()} item(s)" else null
            }
            "Skill" -> rawInput?.get("skill")?.asString
            "ToolSearch" -> rawInput?.get("query")?.asString
            "AskUserQuestion" -> "(question)"
            "ExitPlanMode" -> "(plan)"
            else -> {
                if (title.startsWith("mcp__")) {
                    rawInput?.entrySet()
                        ?.joinToString(", ", limit = 3, truncated = "…") { "${it.key}=${it.value}" }
                } else null
            }
        }
        val info = ToolCallInfo(
            toolCallId = toolCallId,
            title = title,
            kind = kind,
            status = status,
            path = pathFromInput ?: paths.firstOrNull(),
            command = command,
            sessionId = sid,
            detail = detail
        )

        val genericTitles = setOf("tool", "edit", "write", "read", "bash", "find", "grep")
        val isGenericOnly = title.lowercase() in genericTitles &&
            info.path == null && info.command == null && info.detail == null && status != "completed"
        if (!isGenericOnly) {
            toolCallListeners.toList().forEach { it(info) }
        }

        if (toolCallId != null && paths.isNotEmpty()) {
            pathsByToolCallId.getOrPut(toolCallId) { mutableSetOf() }.addAll(paths)
        }

        val allPaths = paths.toMutableSet()
        if (toolCallId != null) {
            pathsByToolCallId[toolCallId]?.let { allPaths.addAll(it) }
        }
        if (allPaths.isEmpty()) return

        for (path in allPaths) {
            if (!shouldTrackFile(path)) continue
            if (!toolCallPreCapturedBefore.containsKey(path)) {
                val before = readFileContent(path)
                toolCallPreCapturedBefore[path] = before
            }
        }

        // 2 retries refresh + fallback final, uniquement sur status=completed
        // (à in_progress le write n'a pas forcément encore eu lieu).
        if (status == "completed") {
            scope.launch {
                val delays = listOf(200L, 800L)
                for ((i, d) in delays.withIndex()) {
                    delay(d)
                    ApplicationManager.getApplication().invokeLater {
                        for (path in allPaths) {
                            try {
                                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                                vf?.refresh(false, false)
                            } catch (e: Exception) {
                                log.warn("refresh attempt #${i + 1} failed for $path", e)
                            }
                        }
                    }
                }
                // Fallback : si VFS n'a jamais fire d'event, déclencher addOrUpdate manuel.
                delay(400)
                ApplicationManager.getApplication().invokeLater {
                    val pending = project.getService(PendingChangesService::class.java)
                    val history = project.getService(PromptHistoryService::class.java)
                    for (path in allPaths) {
                        if (!shouldTrackFile(path)) continue
                        val before = toolCallPreCapturedBefore.remove(path) ?: continue
                        try {
                            val after = java.io.File(path).readText(Charsets.UTF_8)
                            if (before == after) continue
                            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                                ?: continue
                            history.captureFileBefore(path, before)
                            history.captureFileAfter(path, after)
                            pending.addOrUpdate(path, before, after, vf, currentExecutingSessionId)
                            project.getService(DiffViewerManager::class.java).scheduleRefresh()
                        } catch (e: Exception) {
                            log.warn("Fallback addOrUpdate failed for $path", e)
                        }
                    }
                }
            }
        }

        if (status == "completed" && toolCallId != null) {
            scope.launch {
                delay(5000)
                pathsByToolCallId.remove(toolCallId)
            }
        }
    }

    private fun extractTextFromUpdate(update: JsonObject): String? {
        update.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { return it }

        val content = update.get("content")
        when {
            content == null -> {}
            content.isJsonPrimitive -> return content.asString
            content.isJsonObject -> {
                content.asJsonObject.get("text")?.asString?.let { return it }
            }
            content.isJsonArray -> {
                val sb = StringBuilder()
                content.asJsonArray.forEach { item ->
                    if (item.isJsonObject) {
                        item.asJsonObject.get("text")?.asString?.let { sb.append(it) }
                    } else if (item.isJsonPrimitive) {
                        sb.append(item.asString)
                    }
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
        }
        return null
    }

    private fun readFileContent(filepath: String): String {
        return try {
            val file = LocalFileSystem.getInstance().findFileByPath(filepath)
            if (file != null && file.exists()) String(file.contentsToByteArray())
            else File(filepath).takeIf { it.exists() }?.readText() ?: ""
        } catch (e: Exception) {
            log.warn("Could not read file: $filepath", e)
            ""
        }
    }

    private fun sendRawMessage(message: String) {
        try {
            processHandler?.processInput?.let { input ->
                input.write("$message\n".toByteArray())
                input.flush()
            }
        } catch (e: Exception) {
            log.error("Failed to send message", e)
            notifyError("Send failed: ${e.message}")
        }
    }

    private fun escapeJson(text: String): String {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }

    private fun setState(newState: State) {
        val previous = state
        state = newState
        PluginLogService.getInstance(project).info("state", "$previous → $newState")
        stateListeners.toList().forEach { it(newState) }
    }

    private fun notifyInfo(msg: String) {
        log.info(msg)
        infoListeners.toList().forEach { it(msg) }
    }

    private fun notifyError(msg: String) {
        log.warn(msg)
        errorListeners.toList().forEach { it(msg) }
    }

    private fun parseSessionCapabilities(result: JsonObject, sid: String) {
        var modelsList: List<SelectOption> = emptyList()
        var modesList: List<SelectOption> = emptyList()
        var configOptionsList: List<ConfigOption> = emptyList()
        var currentModel: String? = null
        var currentMode: String? = null
        val currentConfig = mutableMapOf<String, String>()

        val modelsState = result.getAsJsonObject("models")
        if (modelsState != null) {
            modelsList = parseSelectOptions(modelsState.getAsJsonArray("availableModels"), idField = "modelId")
            currentModel = modelsState.get("currentModelId")?.asString
        }

        val modesState = result.getAsJsonObject("modes")
        if (modesState != null) {
            modesList = parseSelectOptions(modesState.getAsJsonArray("availableModes"), idField = "id")
            currentMode = modesState.get("currentModeId")?.asString
        }

        val configArr = result.getAsJsonArray("configOptions")
        if (configArr != null) {
            configOptionsList = configArr.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull null
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: id
                val type = obj.get("type")?.asString ?: "select"
                val payload = obj.getAsJsonObject("payload") ?: obj
                val opts = parseSelectOptions(payload.getAsJsonArray("options"), idField = "id")
                val current = payload.get("currentValue")?.asString
                if (current != null) currentConfig[id] = current
                ConfigOption(id, name, type, opts, current)
            }
        }

        val config = SessionConfig(
            models = modelsList,
            modes = modesList,
            configOptions = configOptionsList,
            currentModelId = currentModel,
            currentModeId = currentMode,
            currentConfigValues = currentConfig
        )
        sessionConfigs[sid] = config
        sessionConfigListeners.toList().forEach { it(sid, config) }
    }

    private fun parseSelectOptions(arr: com.google.gson.JsonArray?, idField: String): List<SelectOption> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val id = obj.get(idField)?.asString ?: obj.get("id")?.asString ?: return@mapNotNull null
            val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: id
            val description = obj.get("description")?.asString
            SelectOption(id, name, description)
        }
    }

    internal fun updateSessionConfig(sid: String, transform: (SessionConfig) -> SessionConfig) {
        val current = sessionConfigs[sid] ?: SessionConfig()
        val updated = transform(current)
        sessionConfigs[sid] = updated
        sessionConfigListeners.toList().forEach { it(sid, updated) }
    }

    fun setModel(modelId: String, targetSessionId: String? = null) {
        if (activeProfile.transport == Transport.CLI_STREAM_JSON) {
            setCliModel(modelId, targetSessionId)
            return
        }
        val sid = targetSessionId ?: sessionId ?: return
        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) notifyError("setModel failed: ${response.get("error")}")
            else updateSessionConfig(sid) { it.copy(currentModelId = modelId) }
        }
        val msg = """{"jsonrpc":"2.0","id":$id,"method":"session/set_model","params":""" +
            """{"sessionId":"$sid","modelId":${escapeJson(modelId)}}}"""
        sendRawMessage(msg)
    }

    fun setMode(modeId: String, targetSessionId: String? = null) {
        if (activeProfile.transport == Transport.CLI_STREAM_JSON) {
            setCliMode(modeId, targetSessionId)
            return
        }
        val sid = targetSessionId ?: sessionId ?: return
        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) notifyError("setMode failed: ${response.get("error")}")
            else updateSessionConfig(sid) { it.copy(currentModeId = modeId) }
        }
        val msg = """{"jsonrpc":"2.0","id":$id,"method":"session/set_mode","params":""" +
            """{"sessionId":"$sid","modeId":${escapeJson(modeId)}}}"""
        sendRawMessage(msg)
    }

    /**
     * Helper : trouve le proc actif pour un sid donné, en regardant à la fois cliProcesses
     * (proc avec sid claim) ET pendingCliProcs (proc spawnés mais sid pas encore reçu).
     */
    private fun findCliProc(targetSessionId: String?): CliProc? {
        if (targetSessionId != null) {
            cliProcesses[targetSessionId]?.let { return it }
        }
        return cliProcesses.values.firstOrNull() ?: pendingCliProcs.firstOrNull()
    }

    /**
     * Switch model en CLI : envoie un control_request `set_model` sur stdin. Pas de
     * respawn, la conversation continue avec le nouveau model. (Même mécanisme que
     * `/model` dans le TUI claude.)
     */
    private fun setCliModel(modelId: String, targetSessionId: String?) {
        val proc = findCliProc(targetSessionId) ?: run {
            notifyError("No Claude CLI process to switch model on")
            return
        }
        val sid = proc.sessionId
        val previousModel = if (sid != null) sessionConfigs[sid]?.currentModelId else null
        val previousOverride = proc.modelOverride
        val requestId = "set-model-${System.currentTimeMillis()}"
        // Enregistre le rollback AVANT d'envoyer pour gérer une réponse instantanée.
        pendingControlRequests[requestId] = { success, _ ->
            if (!success) {
                log.warn("CLI set_model rejected, rolling back to $previousModel")
                if (sid != null) {
                    updateSessionConfig(sid) { it.copy(currentModelId = previousModel) }
                }
                proc.modelOverride = previousOverride
            }
        }
        val msg = """{"type":"control_request","request_id":${escapeJson(requestId)},""" +
            """"request":{"subtype":"set_model","model":${escapeJson(modelId)}}}"""
        try {
            proc.writer.write("$msg\n")
            proc.writer.flush()
            log.info("CLI set_model sent: $modelId (sid=$sid)")
            // Optimistic UI update — sera revertie via le callback ci-dessus si claude répond error
            if (sid != null) {
                updateSessionConfig(sid) { it.copy(currentModelId = modelId) }
            }
            proc.modelOverride = modelId
        } catch (e: Exception) {
            pendingControlRequests.remove(requestId)
            log.warn("CLI set_model write failed", e)
            notifyError("Failed to set model: ${e.message}")
        }
    }

    private fun setCliMode(modeId: String, targetSessionId: String?) {
        val proc = findCliProc(targetSessionId) ?: run {
            notifyError("No Claude CLI process to switch mode on")
            return
        }
        val sid = proc.sessionId
        // bypassPermissions exige --dangerously-skip-permissions au lancement de claude.
        // Le control_request set_permission_mode est rejeté ("Cannot set permission mode to
        // bypassPermissions because the session was not launched with --dangerously-skip-permissions").
        // → on respawn le process avec le flag.
        if (modeId == "bypassPermissions") {
            // Respawn pendant qu'un prompt mouline = kill brutal → on bloque, comme pour Effort.
            if (proc.executingSessionId != null || isPromptExecuting) {
                notifyError("Cannot switch to Bypass mode while Claude is running. Stop the current turn first.")
                return
            }
            log.info("CLI set_permission_mode bypassPermissions → respawn with --dangerously-skip-permissions")
            proc.permissionModeOverride = modeId
            if (sid != null) {
                updateSessionConfig(sid) { it.copy(currentModeId = modeId) }
            }
            respawnCliProc(proc, sid)
            return
        }
        val previousMode = if (sid != null) sessionConfigs[sid]?.currentModeId else null
        val previousOverride = proc.permissionModeOverride
        val requestId = "set-mode-${System.currentTimeMillis()}"
        pendingControlRequests[requestId] = { success, _ ->
            if (!success) {
                log.warn("CLI set_permission_mode rejected, rolling back to $previousMode")
                if (sid != null) {
                    updateSessionConfig(sid) { it.copy(currentModeId = previousMode) }
                }
                proc.permissionModeOverride = previousOverride
            }
        }
        val msg = """{"type":"control_request","request_id":${escapeJson(requestId)},""" +
            """"request":{"subtype":"set_permission_mode","mode":${escapeJson(modeId)}}}"""
        try {
            proc.writer.write("$msg\n")
            proc.writer.flush()
            log.info("CLI set_permission_mode sent: $modeId (sid=$sid)")
            if (sid != null) {
                updateSessionConfig(sid) { it.copy(currentModeId = modeId) }
            }
            proc.permissionModeOverride = modeId
        } catch (e: Exception) {
            pendingControlRequests.remove(requestId)
            log.warn("CLI set_permission_mode write failed", e)
            notifyError("Failed to set permission mode: ${e.message}")
        }
    }

    /** Kill l'ancien process, spawn un nouveau avec les overrides. Map old→new sid si change. */
    private fun respawnCliProc(oldProc: CliProc, oldSid: String?) {
        log.info("Respawning Claude CLI for sid=$oldSid (model=${oldProc.modelOverride}, perm=${oldProc.permissionModeOverride}, effort=${oldProc.effortOverride})")
        try { oldProc.handler.destroyProcess() } catch (_: Exception) {}
        if (oldSid != null) cliProcesses.remove(oldSid)
        pendingCliProcs.remove(oldProc)

        val newProc = spawnClaudeCli(
            // --resume <old-sid> pour préserver l'historique de la conversation à travers
            // le respawn (sinon Effort/Bypass change = conv perdue). Confirmé fonctionnel
            // sans -p (test 2026-05-15). Si oldSid est null (jamais claim), nouvelle session.
            resumeSid = oldSid,
            modelOverride = oldProc.modelOverride,
            permissionModeOverride = oldProc.permissionModeOverride,
            effortOverride = oldProc.effortOverride
        )
        if (newProc == null) {
            notifyError("Failed to respawn Claude CLI after settings change")
            setState(State.ERROR)
            return
        }
        newProc.modelOverride = oldProc.modelOverride
        newProc.permissionModeOverride = oldProc.permissionModeOverride
        newProc.effortOverride = oldProc.effortOverride
        pendingCliProcs.add(newProc)
        newProc.state = State.READY
        setState(State.READY)

        // Quand le nouveau system:init arrive, on émet sessionRebound pour que les chats
        // updatent leur mySessionId vers le nouveau sid (toujours différent vu qu'on
        // ne resume pas).
        if (oldSid != null) {
            val migrationListener = object : (String) -> Unit {
                override fun invoke(sid: String) {
                    if (sid != oldSid) {
                        log.info("Claude session rebound: $oldSid → $sid")
                        sessionReboundListeners.toList().forEach { it(oldSid, sid) }
                        sessionConfigs[oldSid]?.let { sessionConfigs[sid] = it }
                        sessionConfigs.remove(oldSid)
                    }
                    sessionCreatedListeners.remove(this)
                }
            }
            sessionCreatedListeners.add(migrationListener)
        }
    }

    fun setConfigOption(optionId: String, value: String, targetSessionId: String? = null) {
        if (activeProfile.transport == Transport.CLI_STREAM_JSON) {
            setCliConfigOption(optionId, value, targetSessionId)
            return
        }
        val sid = targetSessionId ?: sessionId ?: return
        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) notifyError("setConfigOption failed: ${response.get("error")}")
            else updateSessionConfig(sid) {
                it.copy(currentConfigValues = it.currentConfigValues + (optionId to value))
            }
        }
        val msg = """{"jsonrpc":"2.0","id":$id,"method":"session/set_config_option","params":""" +
            """{"sessionId":"$sid","id":${escapeJson(optionId)},"value":${escapeJson(value)}}}"""
        sendRawMessage(msg)
    }

    /**
     * Effort/thinking en CLI : envoie un control_request `set_effort` (équivalent du
     * `/effort` slash command du TUI). Pas de respawn, la conv continue.
     */
    private fun setCliConfigOption(optionId: String, value: String, targetSessionId: String?) {
        if (optionId != "thinking") {
            log.info("CLI: unsupported config option '$optionId'")
            return
        }
        val proc = findCliProc(targetSessionId) ?: run {
            notifyError("No Claude CLI process to switch effort on")
            return
        }
        // set_effort RESPAWN le proc (claude stream-json refuse `set_effort` control_request).
        // Si un prompt tourne, le respawn kill claude au milieu de la génération → chaos.
        // Bloque + demande à l'user d'attendre la fin / stopper avant.
        if (proc.executingSessionId != null || isPromptExecuting) {
            notifyError("Cannot change Effort while Claude is running. Stop the current turn first.")
            return
        }
        val sid = proc.sessionId
        log.info("CLI set_effort: respawning process with --effort=$value (sid=$sid)")
        proc.effortOverride = value
        if (sid != null) {
            updateSessionConfig(sid) {
                it.copy(currentConfigValues = it.currentConfigValues + (optionId to value))
            }
        }
        respawnCliProc(proc, sid)
    }

    fun addMessageChunkListener(l: (text: String, sessionId: String?) -> Unit) { messageChunkListeners.add(l) }
    fun addThoughtChunkListener(l: (text: String, sessionId: String?) -> Unit) { thoughtChunkListeners.add(l) }
    fun addToolCallListener(l: (ToolCallInfo) -> Unit) { toolCallListeners.add(l) }
    fun addSessionConfigListener(l: (String?, SessionConfig) -> Unit) { sessionConfigListeners.add(l) }
    fun addMessageListener(listener: (JsonObject) -> Unit) { messageListeners.add(listener) }
    fun addStderrListener(listener: (String) -> Unit) { stderrListeners.add(listener) }
    fun addInfoListener(listener: (String) -> Unit) { infoListeners.add(listener) }
    fun addErrorListener(listener: (String) -> Unit) { errorListeners.add(listener) }
    fun addStateListener(listener: (State) -> Unit) { stateListeners.add(listener) }
    fun removeStateListener(listener: (State) -> Unit) { stateListeners.remove(listener) }

    /** Notifié quand un sessionId est créé (system:init en CLI, session/new en ACP). */
    fun addSessionCreatedListener(listener: (String) -> Unit) { sessionCreatedListeners.add(listener) }
    fun removeSessionCreatedListener(listener: (String) -> Unit) { sessionCreatedListeners.remove(listener) }

    /** Notifié quand un sessionId migre (ex: respawn claude --resume produit un nouveau sid). */
    private val sessionReboundListeners = mutableListOf<(String, String) -> Unit>()
    fun addSessionReboundListener(listener: (oldSid: String, newSid: String) -> Unit) {
        sessionReboundListeners.add(listener)
    }

    fun stopAgent() {
        // CLI : on tue tous les process claude lancés
        if (activeProfile.transport == Transport.CLI_STREAM_JSON) {
            cliProcesses.values.toList().forEach { proc ->
                try { proc.handler.destroyProcess() } catch (_: Exception) {}
            }
            cliProcesses.clear()
            pendingCliProcs.toList().forEach { proc ->
                try { proc.handler.destroyProcess() } catch (_: Exception) {}
            }
            pendingCliProcs.clear()
            sessionId = null
            currentExecutingSessionId = null
            setExecuting(false)
            setState(State.STOPPED)
            return
        }
        try {
            processHandler?.destroyProcess()
        } catch (e: Exception) {
            log.warn("destroyProcess failed", e)
        }
        processHandler = null
        sessionId = null
        currentExecutingSessionId = null
        setExecuting(false)
        setState(State.STOPPED)
    }

    fun isRunning(): Boolean = state == State.READY

    // ═══════════════════════════════════════════════════════════════════════
    // CLI STREAM-JSON TRANSPORT
    //   Mode bidirectionnel : spawn `claude --output-format stream-json
    //   --input-format stream-json --verbose --permission-mode acceptEdits`,
    //   échange en NDJSON via stdin/stdout. Utilise le plan d'abonnement
    //   interactif (pas l'API). Multi-chat = 1 process par chat.
    // ═══════════════════════════════════════════════════════════════════════

    /** Un process claude par session/chat en mode CLI. */
    private class CliProc(
        val handler: OSProcessHandler,
        val writer: java.io.BufferedWriter,
        val lineBuffer: StringBuilder = StringBuilder(),
        @Volatile var sessionId: String? = null,
        @Volatile var state: State = State.STARTING,
        @Volatile var executingSessionId: String? = null,
        /** Model override courant (null = défaut). Utilisé pour respawn avec --model. */
        @Volatile var modelOverride: String? = null,
        /** Permission mode override courant (null = celui des args par défaut). */
        @Volatile var permissionModeOverride: String? = null,
        /** Effort override courant (low/medium/high/xhigh/max, ou "auto" = pas de flag). */
        @Volatile var effortOverride: String? = null,
        /** UUID pré-généré côté plugin passé via `--session-id`. Identique à sessionId une fois
         *  system:init reçu, mais connu dès le spawn — sert au `claude --resume <uuid>` depuis le terminal. */
        @Volatile var preAssignedSessionId: String? = null,
        /** Nb de tentatives auto-reconnect après crash. Limite à 3. */
        @Volatile var reconnectAttempts: Int = 0
    )

    /** Liste curated des models Claude (le CLI n'expose pas la liste dispo via stream-json). */
    private val CLAUDE_MODELS = listOf(
        SelectOption("claude-opus-4-7", "Claude Opus 4.7"),
        SelectOption("claude-opus-4-7[1m]", "Claude Opus 4.7 (1M context)"),
        SelectOption("claude-opus-4-6", "Claude Opus 4.6"),
        SelectOption("claude-sonnet-4-6", "Claude Sonnet 4.6"),
        SelectOption("claude-sonnet-4-5", "Claude Sonnet 4.5"),
        SelectOption("claude-haiku-4-5", "Claude Haiku 4.5")
    )

    /** Permission modes supportés par claude CLI. Labels courts (le détail va en tooltip). */
    private val CLAUDE_PERMISSION_MODES = listOf(
        SelectOption("default", "Default", "Prompt for each tool use"),
        SelectOption("acceptEdits", "Accept edits", "Auto-approve Write/Edit"),
        SelectOption("bypassPermissions", "Bypass all", "Auto-approve everything (use with care)"),
        SelectOption("plan", "Plan", "Read-only — preview without writing to disk")
    )

    /** Effort / extended thinking — utilise `--effort` flag de claude CLI. */
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

    /** sessionId → process claude. */
    private val cliProcesses = ConcurrentHashMap<String, CliProc>()

    /** Process spawnés mais qui n'ont pas encore reçu leur system:init (et donc sessionId). */
    private val pendingCliProcs = java.util.concurrent.CopyOnWriteArrayList<CliProc>()

    /**
     * Map request_id → callback (rollback ou success) pour les control_request CLI.
     * Permet de revertir l'UI si claude répond `error` au lieu de laisser l'user croire
     * que le changement de mode/model a pris effet.
     */
    private val pendingControlRequests = ConcurrentHashMap<String, (success: Boolean, error: String?) -> Unit>()

    /**
     * Construit un PATH qui inclut les emplacements usuels où sont installés les outils que
     * claude doit pouvoir spawn pour les MCP (npx, uvx, uv, python, …). Sans ça, un IntelliJ
     * lancé depuis un launcher GUI hérite d'un PATH minimaliste et tous les MCP en stdio
     * (Playwright, postgres, openrag, …) plantent au boot.
     */
    private fun buildEnrichedPath(claudeBinDir: String?): String {
        val home = System.getProperty("user.home") ?: ""
        val candidates = mutableListOf<String>()
        claudeBinDir?.let { candidates.add(it) }
        // Linux/Mac usuels (homebrew, system, snap, cargo, pip)
        candidates.addAll(listOf(
            "$home/.local/bin",
            "$home/.cargo/bin",
            "/opt/homebrew/bin",        // macOS Apple Silicon
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
            "/snap/bin"
        ))
        // Détecte tous les dossiers nvm/node disponibles (le plus récent en premier)
        val nvmRoot = File("$home/.nvm/versions/node")
        if (nvmRoot.isDirectory) {
            nvmRoot.listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name }
                ?.forEach { candidates.add("${it.absolutePath}/bin") }
        }
        // Détecte volta (autre node version manager)
        val voltaBin = File("$home/.volta/bin")
        if (voltaBin.isDirectory) candidates.add(voltaBin.absolutePath)
        // pyenv shims (pour python/uvx via pyenv)
        val pyenvShims = File("$home/.pyenv/shims")
        if (pyenvShims.isDirectory) candidates.add(pyenvShims.absolutePath)

        val existing = System.getenv("PATH")?.split(":")?.filter { it.isNotBlank() } ?: emptyList()
        // De-dup tout en gardant l'ordre : candidates d'abord (priorité), puis l'existant.
        val ordered = LinkedHashSet<String>()
        candidates.filter { File(it).isDirectory }.forEach { ordered.add(it) }
        existing.forEach { ordered.add(it) }
        return ordered.joinToString(":")
    }

    private fun startCliAgent(): Boolean {
        val proc = spawnClaudeCli(resumeSid = null) ?: run {
            setState(State.ERROR)
            return false
        }
        pendingCliProcs.add(proc)
        proc.state = State.READY
        // CRITIQUE : on assigne immédiatement sessionId au preAssignedSid pour que le 1er
        // chat (firstSidClaim) le récupère. Sans ça mySessionId reste null jusqu'au 1er
        // system:init et le routing des permissions/cards se casse.
        proc.preAssignedSessionId?.let { sid ->
            sessionId = sid
            // FIX : aligner proc.sessionId DÈS LE SPAWN pour que les permissions arrivant
            // avant le system:init aient un sid non-null à propager au panel.
            proc.sessionId = sid
            cliProcesses[sid] = proc
            pendingCliProcs.remove(proc)
            PluginLogService.getInstance(project).info("permission",
                "🟢 startCliAgent: proc.sessionId pré-assigné à $sid (avant system:init)")
            // Notifie sessionCreatedListeners pour que firstSidClaim du panel récupère sid
            sessionCreatedListeners.toList().forEach { it(sid) }
        }
        setState(State.READY)
        log.info("Claude CLI spawned, state=READY (preAssignedSid=${proc.preAssignedSessionId})")
        return true
    }

    private fun spawnClaudeCli(
        resumeSid: String?,
        modelOverride: String? = null,
        permissionModeOverride: String? = null,
        effortOverride: String? = null,
        cwdOverride: String? = null
    ): CliProc? {
        return try {
            val (resolvedCmd, resolvedArgs) = AgentBinaryResolver.resolveProfileCommand(activeProfile)
            val exeFile = File(resolvedCmd)
            val claudeBin = if (exeFile.isAbsolute && exeFile.canExecute()) {
                resolvedCmd
            } else {
                AgentBinaryResolver.resolveClaudeCli()
                    ?: AgentBinaryResolver.resolveCommandInPath(resolvedCmd)
                    ?: run {
                        notifyError("Cannot find '$resolvedCmd'. Install Claude Code CLI first.")
                        return null
                    }
            }

            // Si permissionModeOverride est set, on remplace la valeur dans resolvedArgs.
            // Cas spécial bypassPermissions : claude refuse `set_permission_mode bypassPermissions`
            // sauf si le process a été lancé avec --dangerously-skip-permissions. On ajoute donc
            // ce flag automatiquement. Le mode --permission-mode est laissé tel quel pour cohérence
            // avec la dropdown UI (même si le flag --dangerously-skip prend le pas).
            // Mode TRUST vs SAFE : choisi par l'user via Settings → "Trust this session".
            //   trust  = --dangerously-skip-permissions (default, jamais de blocage)
            //   safe   = --permission-mode acceptEdits --permission-prompt-tool stdio
            //            (cards Allow/Deny pour Bash/MCP, claude attend nos réponses)
            val trustMode = AgentSettings.getInstance().trustSession
            val safeArgs = listOf(
                "--permission-mode", "acceptEdits",
                "--permission-prompt-tool", "stdio"
            )
            val baseArgs = if (permissionModeOverride != null) {
                // Override explicite via setMode UI : prend le dessus sur trust.
                val mutable = resolvedArgs.toMutableList()
                mutable.removeAll { it == "--dangerously-skip-permissions" }
                val idx = mutable.indexOf("--permission-mode")
                if (idx >= 0 && idx + 1 < mutable.size) {
                    mutable[idx + 1] = permissionModeOverride
                } else {
                    mutable += listOf("--permission-mode", permissionModeOverride)
                }
                if (permissionModeOverride == "bypassPermissions" &&
                    !mutable.contains("--dangerously-skip-permissions")) {
                    mutable += "--dangerously-skip-permissions"
                }
                mutable.toList()
            } else if (trustMode) {
                // Le default args contient déjà --dangerously-skip-permissions, on garde tel quel
                resolvedArgs
            } else {
                // Mode safe : on retire le dangerously et on ajoute le set safe.
                val mutable = resolvedArgs.toMutableList()
                mutable.removeAll { it == "--dangerously-skip-permissions" }
                mutable.addAll(safeArgs)
                mutable.toList()
            }

            // Pré-assigne un UUID via --session-id pour que claude utilise NOTRE sid au lieu
            // d'en générer un aléatoirement. Bénéfices :
            //  - On peut faire `claude --resume <sid>` depuis le terminal pour reprendre nos chats.
            //  - Le sid est connu AVANT system:init (utile pour le routing multi-chat).
            // Sauf si on resume une session existante : on réutilise le sid existant.
            val preAssignedSid = resumeSid ?: java.util.UUID.randomUUID().toString()

            // Préfixes : --resume (si reprise), --session-id, --mcp-config, --model, --effort.
            // --resume marche avec --print + stream-json bidirectionnel (testé en CLI direct).
            val mcpConfigPath = AgentSettings.getInstance().getMcpConfigPathOrNull()
            val argsForLaunch = buildList<String> {
                if (resumeSid != null) {
                    add("--resume"); add(resumeSid)
                } else {
                    add("--session-id"); add(preAssignedSid)
                }
                if (mcpConfigPath != null) {
                    add("--mcp-config"); add(mcpConfigPath)
                }
                if (modelOverride != null) {
                    add("--model"); add(modelOverride)
                }
                if (effortOverride != null && effortOverride != "auto") {
                    add("--effort"); add(effortOverride)
                }
                addAll(baseArgs)
            }

            // Claude (Node/Bun) bufferise stdout en mode pipe → on force line-buffering via
            // `stdbuf -oL` pour recevoir les events NDJSON en temps réel. Fallback : si stdbuf
            // pas dispo, on spawn claude direct (peut bufferiser, mais on essaiera quand même).
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
                // Pour resume, claude refuse si le cwd au spawn ne match pas celui qui a stocké
                // la session (erreur "No conversation found with session ID"). On utilise le
                // cwd d'origine en priorité ; à défaut le basePath du projet IntelliJ.
                val effectiveCwd = cwdOverride ?: project.basePath
                effectiveCwd?.let { workDirectory = File(it) }
                withRedirectErrorStream(false)
                // PATH enrichi : Le PATH hérité de l'IDE peut être minimaliste (cas IntelliJ
                // lancé depuis un launcher GUI sans .profile). Or claude doit pouvoir spawn
                // des sous-process MCP via `npx`, `uvx`, etc. — sans ces binaires dans le PATH,
                // les MCP fail au boot avec "Server stderr: spawn npx ENOENT".
                environment["PATH"] = buildEnrichedPath(File(claudeBin).parentFile?.absolutePath)
                // Forcer Node à ne pas colorer (claude est en Node) au cas où ça parasite la JSON
                environment["NO_COLOR"] = "1"
                environment["TERM"] = "dumb"
            }

            log.info("Starting Claude CLI: ${command.commandLineString}")

            val handler = OSProcessHandler(command)
            val writer = handler.processInput.bufferedWriter(Charsets.UTF_8)
            val proc = CliProc(handler, writer)
            proc.preAssignedSessionId = preAssignedSid

            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when (outputType) {
                        ProcessOutputType.STDOUT -> handleCliStdout(proc, event.text)
                        ProcessOutputType.STDERR -> handleCliStderr(proc, event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    log.info("Claude CLI process terminated (exit ${event.exitCode}) sid=${proc.sessionId}")
                    val crashedSid = proc.sessionId
                    val wasExecuting = proc.executingSessionId != null
                    val crashedDuringTurn = wasExecuting && event.exitCode != 0
                    proc.sessionId?.let { cliProcesses.remove(it) }
                    pendingCliProcs.remove(proc)
                    if (proc.state != State.STOPPED) {
                        proc.state = State.STOPPED
                    }
                    if (proc.executingSessionId == currentExecutingSessionId) {
                        setExecuting(false)
                    }
                    // Auto-reconnect : si claude crashe en plein turn ET qu'on a un sid claim,
                    // on respawn avec --resume. Backoff exponentiel + cap 3 essais pour éviter
                    // une boucle infinie si claude crash à chaque boot.
                    if (crashedDuringTurn && crashedSid != null && proc.reconnectAttempts < 3) {
                        val attempt = proc.reconnectAttempts + 1
                        val delayMs = listOf(1000L, 5000L, 30000L)[proc.reconnectAttempts]
                        proc.reconnectAttempts = attempt
                        log.warn("Claude CLI crashed during turn — auto-reconnecting in ${delayMs}ms (attempt $attempt/3)")
                        notifyInfo("Claude crashed — auto-reconnecting (attempt $attempt/3)…")
                        scope.launch {
                            delay(delayMs)
                            ApplicationManager.getApplication().invokeLater {
                                val newProc = spawnClaudeCli(
                                    resumeSid = crashedSid,
                                    modelOverride = proc.modelOverride,
                                    permissionModeOverride = proc.permissionModeOverride,
                                    effortOverride = proc.effortOverride
                                )
                                if (newProc != null) {
                                    pendingCliProcs.add(newProc)
                                    newProc.state = State.READY
                                    setState(State.READY)
                                    notifyInfo("Claude reconnected (--resume $crashedSid)")
                                } else if (cliProcesses.isEmpty() && pendingCliProcs.isEmpty()) {
                                    setState(State.ERROR)
                                }
                            }
                        }
                        return
                    }
                    // Pas de reconnect : update state global selon ce qui reste.
                    if (cliProcesses.isEmpty() && pendingCliProcs.isEmpty() && state != State.STOPPED) {
                        if (event.exitCode != 0) setState(State.ERROR)
                        else setState(State.STOPPED)
                    }
                }
            })

            handler.startNotify()
            proc
        } catch (e: Exception) {
            log.error("Failed to spawn Claude CLI", e)
            notifyError("Failed to start Claude CLI: ${e.message}")
            null
        }
    }

    private fun handleCliStdout(proc: CliProc, text: String) {
        log.info("CLI stdout chunk (${text.length} chars): ${text.take(150).replace("\n", "\\n")}")
        proc.lineBuffer.append(text)
        while (proc.lineBuffer.contains('\n')) {
            val nl = proc.lineBuffer.indexOf('\n')
            val line = proc.lineBuffer.substring(0, nl).trim()
            proc.lineBuffer.delete(0, nl + 1)
            if (line.isEmpty()) continue
            if (line.startsWith("{")) {
                try {
                    val json = JsonParser.parseString(line).asJsonObject
                    val type = json.get("type")?.asString
                    log.info("CLI event: type=$type sid=${proc.sessionId}")
                    handleCliEvent(proc, json)
                } catch (e: Exception) {
                    log.warn("CLI parse fail (${e.message}): ${line.take(200)}")
                }
            } else {
                log.info("CLI non-json line: ${line.take(150)}")
            }
        }
    }

    private fun handleCliStderr(proc: CliProc, text: String) {
        val trimmed = text.trimEnd('\n', '\r')
        if (trimmed.isNotEmpty()) {
            log.warn("Claude CLI stderr: $trimmed")
            stderrListeners.toList().forEach { it(trimmed) }
        }
    }

    private fun handleCliEvent(proc: CliProc, json: JsonObject) {
        messageListeners.toList().forEach { it(json) }
        when (json.get("type")?.asString) {
            "system" -> handleCliSystemEvent(proc, json)
            "assistant" -> handleCliAssistantEvent(proc, json)
            "user" -> handleCliUserEvent(proc, json)
            "result" -> handleCliResultEvent(proc, json)
            "rate_limit_event" -> { /* ignore for now */ }
            "control_request" -> handleCliControlRequest(proc, json)
            // sdk_control_request : permission request via --permission-prompt-tool stdio.
            // Format : { type:"sdk_control_request", request:{ subtype:"permission",
            //   request_id, tool_name, tool_input } }
            "sdk_control_request" -> handleCliSdkControlRequest(proc, json)
            "control_response" -> handleCliControlResponse(proc, json)
            // Partial chunks émis quand --include-partial-messages est actif. On ignore
            // (le bloc consolidé suit dans assistant:message).
            "stream_event" -> {}
            else -> {}
        }
    }

    /**
     * Permission request envoyée par claude quand `--permission-prompt-tool stdio` est actif.
     * On délègue à un listener UI qui doit appeler `respondPermission(requestId, allow, msg)`.
     * Si aucun listener n'est attaché : auto-allow (compat avec ancien comportement).
     */
    private fun handleCliSdkControlRequest(proc: CliProc, json: JsonObject) {
        val logSvc0 = PluginLogService.getInstance(project)
        logSvc0.debug("permission",
            "🔵 RAW sdk_control_request received | proc.sessionId=${proc.sessionId} | proc.preAssignedSid=${proc.preAssignedSessionId} | raw=${json.toString().take(400)}")
        val request = json.getAsJsonObject("request") ?: run {
            logSvc0.warn("permission", "🔴 sdk_control_request has no 'request' field, ignoring")
            return
        }
        val subtype = request.get("subtype")?.asString
        if (subtype != "permission") {
            logSvc0.debug("permission", "🟡 sdk_control_request subtype=$subtype (not permission) → auto-allow")
            val requestId = request.get("request_id")?.asString ?: return
            respondPermission(proc, requestId, allow = true, reason = null)
            return
        }
        val requestId = request.get("request_id")?.asString ?: run {
            logSvc0.warn("permission", "🔴 permission request has no request_id, ignoring")
            return
        }
        val toolName = request.get("tool_name")?.asString ?: "tool"
        val toolInput = request.get("tool_input")
        val sid = proc.sessionId
        logSvc0.info("permission",
            "🔵 PARSED permission request | tool=$toolName | req=$requestId | sid(from proc.sessionId)=$sid | preAssignedSid=${proc.preAssignedSessionId}")
        if (sid == null) {
            logSvc0.error("permission",
                "🔴🔴🔴 BUG: proc.sessionId is NULL — system:init hasn't fired yet OR claude didn't emit it. Using preAssignedSid=${proc.preAssignedSessionId} as fallback for routing.")
        }

        val responded = java.util.concurrent.atomic.AtomicBoolean(false)
        fun allowOnce(reason: String? = null) {
            if (responded.compareAndSet(false, true)) {
                respondPermission(proc, requestId, allow = true, reason = null)
                if (reason != null) {
                    PluginLogService.getInstance(project).info("permission", "auto-allowed: $reason")
                }
            }
        }
        fun denyOnce(reason: String?) {
            if (responded.compareAndSet(false, true))
                respondPermission(proc, requestId, allow = false, reason = reason)
        }

        // AUTO-ALLOW les tools sûrs (lecture seule, no side effect) — sans déranger l'user
        // avec une card. Sinon `--permission-prompt-tool stdio` rendrait insupportable même
        // un simple `ls`. La whitelist couvre : lecture fichiers, recherche, git read-only,
        // navigation (cd/pwd/ls). Tout ce qui modifie l'état (rm, mv, install, network…)
        // demande toujours confirmation.
        if (isAutoAllowedTool(toolName, toolInput)) {
            log.info("Auto-allow safe tool: $toolName")
            allowOnce("safe-tool: $toolName")
            return
        }

        // QUICK FIX : fallback sur preAssignedSid si proc.sessionId est null (système:init
        // pas encore arrivé). Sinon le routing strict du panel rejette la card.
        val effectiveSid = sid ?: proc.preAssignedSessionId
        logSvc0.info("permission",
            "🔧 effectiveSid for routing = $effectiveSid (sid=$sid, fallback preAssignedSid=${proc.preAssignedSessionId})")

        // Build le PermissionRequest avec respondAllow/Deny qui le retirent de la queue.
        lateinit var info: PermissionRequest
        info = PermissionRequest(
            requestId = requestId,
            toolName = toolName,
            toolInput = toolInput?.toString(),
            sessionId = effectiveSid,
            respondAllow = {
                logSvc0.info("permission", "✅ USER clicked ALLOW for req=$requestId tool=$toolName")
                allowOnce()
                removePendingPermission(info)
            },
            respondDeny = { reason ->
                logSvc0.info("permission", "❌ USER clicked DENY for req=$requestId tool=$toolName reason=$reason")
                denyOnce(reason)
                removePendingPermission(info)
            }
        )
        // Push dans la queue centrale → tous les panels seront notifiés et le 1er qui
        // affiche la card permet à l'user de répondre. Pas de routage fragile.
        val logSvc = PluginLogService.getInstance(project)
        logSvc.info("permission",
            "📥 ADDING to queue: $toolName | req=$requestId | sid=$effectiveSid | listeners=${pendingPermissionsListeners.size}")
        addPendingPermission(info)
        logSvc.debug("permission",
            "📊 Queue state after add: size=${pendingPermissions.size}, all sids=${pendingPermissions.map { it.sessionId }}")
        // Sécurité : si aucun listener (= tool window jamais ouverte), auto-allow pour ne
        // pas bloquer claude. Sinon claude attend pour rien.
        if (pendingPermissionsListeners.isEmpty()) {
            logSvc.error("permission",
                "🔴 NO UI LISTENER registered — auto-allowing $toolName (tool window jamais ouverte?)")
            allowOnce("no-ui")
            removePendingPermission(info)
            return
        }
        // Timeout safety net : si l'user ne clique rien sous 120s, on auto-deny pour
        // débloquer claude. Sinon le tool reste "Running" indéfiniment (bug observé sur
        // les Bash quand la card ne s'affiche pas pour cause de routing).
        scope.launch {
            delay(120_000)
            if (!responded.get()) {
                log.warn("Permission request $requestId for $toolName timed out → auto-deny")
                PluginLogService.getInstance(project).warn("permission",
                    "Timeout on $toolName (req=$requestId) → auto-deny after 120s")
                notifyError("Permission for '$toolName' timed out (no response in 120s) — denied")
                denyOnce("No response from user within 120s")
            }
        }
    }

    /**
     * Retourne true si le tool/commande peut être auto-approuvé sans déranger l'user.
     * Critère : opération en lecture seule sans side effect réseau / disque destructif.
     *
     * - Read tools natifs (Read, Grep, Glob, WebFetch lecture seule…) : auto-allow
     * - Bash : auto-allow uniquement si la commande est dans la whitelist de patterns safe
     * - Reste (Bash dangereux, Edit, Write, MultiEdit, MCP) : ask user
     */
    private fun isAutoAllowedTool(toolName: String, toolInput: com.google.gson.JsonElement?): Boolean {
        // Tools intrinsèquement safe (jamais d'écriture disque ni network state-changing)
        val safeTools = setOf("Read", "Grep", "Glob", "Task", "TodoWrite",
            "NotebookRead", "WebFetch", "WebSearch", "Skill", "ToolSearch",
            "ExitPlanMode", "AskUserQuestion", "TaskOutput", "TaskList", "TaskGet")
        if (toolName in safeTools) return true
        // Pour Bash, regarde la commande
        if (toolName == "Bash") {
            val cmd = toolInput?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("command")?.asString
                ?.trim()
                ?: return false
            return isSafeBashCommand(cmd)
        }
        return false
    }

    /**
     * Heuristique pour détecter les commandes Bash safe (read-only, navigation, git read).
     * Sur-prudent par défaut : tout ce qui ressemble à de l'écriture / network / install
     * passe par l'user.
     */
    private fun isSafeBashCommand(cmd: String): Boolean {
        // Une commande, ou une chaîne de commandes pipées safe : on split sur | && ; et on
        // vérifie chaque sous-commande.
        // ATTENTION : on évite de matcher si on voit un caractère de redirection vers
        // l'écriture (> >> tee | sudo) ou suppression.
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
        // Pour git, on whitelist juste les sous-commandes read-only
        for (bin in parts) {
            if (bin !in safeBins) return false
        }
        // Cas spécial git : si la commande commence par `git`, vérifier le sous-verbe.
        if (cmd.trim().startsWith("git ")) {
            val sub = cmd.trim().removePrefix("git ").trim().substringBefore(' ').lowercase()
            val safeGitVerbs = setOf("status", "log", "diff", "show", "branch", "tag",
                "remote", "config", "describe", "blame", "ls-files", "ls-tree", "rev-parse",
                "stash", "fetch", "shortlog", "reflog", "grep")
            if (sub !in safeGitVerbs) return false
            // Refuse en plus si on voit -d, -D, --force, --delete dans les args
            val args = cmd.trim().removePrefix("git ")
            if (Regex("\\s(--force|--delete|-D)\\b").containsMatchIn(" $args ")) return false
        }
        return true
    }

    private fun respondPermission(proc: CliProc, requestId: String, allow: Boolean, reason: String?) {
        val behaviorPayload = if (allow) {
            """"behavior":"allow""""
        } else {
            val msg = reason ?: "Denied by user"
            """"behavior":"deny","message":${escapeJson(msg)}"""
        }
        val resp = """{"type":"control_response","response":{"subtype":"success",""" +
            """"request_id":${escapeJson(requestId)},"response":{$behaviorPayload}}}"""
        try {
            proc.writer.write("$resp\n")
            proc.writer.flush()
            log.info("Permission response sent: allow=$allow req=$requestId")
        } catch (e: Exception) {
            log.warn("Failed to write permission response", e)
        }
    }

    /**
     * Architecture permission robuste : queue centrale de pending permissions + broadcast.
     * - Chaque permission entrante est immédiatement ajoutée à `pendingPermissions`
     * - TOUS les panels reçoivent l'event via `pendingPermissionsListeners`
     * - Chaque panel décide d'afficher selon sa logique propre (sid match, selected, etc.)
     * - L'AtomicBoolean interne au PermissionRequest empêche les double-réponses
     * - Si l'user répond à la card (Allow/Deny), elle est retirée de la queue
     * - Aucune permission ne peut être perdue silencieusement
     */
    private val pendingPermissions = java.util.concurrent.CopyOnWriteArrayList<PermissionRequest>()
    private val pendingPermissionsListeners = mutableListOf<(List<PermissionRequest>) -> Unit>()

    fun getPendingPermissions(): List<PermissionRequest> = pendingPermissions.toList()

    fun addPendingPermissionsListener(l: (List<PermissionRequest>) -> Unit) {
        pendingPermissionsListeners.add(l)
        // Synchro initial : notifier l'état actuel si non vide
        if (pendingPermissions.isNotEmpty()) l(pendingPermissions.toList())
    }
    fun removePendingPermissionsListener(l: (List<PermissionRequest>) -> Unit) {
        pendingPermissionsListeners.remove(l)
    }

    private fun addPendingPermission(req: PermissionRequest) {
        pendingPermissions.add(req)
        val snapshot = pendingPermissions.toList()
        pendingPermissionsListeners.toList().forEach { it(snapshot) }
    }
    private fun removePendingPermission(req: PermissionRequest) {
        pendingPermissions.remove(req)
        val snapshot = pendingPermissions.toList()
        pendingPermissionsListeners.toList().forEach { it(snapshot) }
    }

    /**
     * Response à un control_request qu'on a envoyé (set_model, set_permission_mode, set_effort).
     * Si succès : rien à faire (config déjà updatée optimistiquement). Si erreur : on log,
     * et on pourrait revertir l'UI mais pour v1 on laisse — l'user verra que ça n'a pas pris effet.
     */
    private fun handleCliControlResponse(proc: CliProc, json: JsonObject) {
        val requestId = json.get("request_id")?.asString
        val response = json.getAsJsonObject("response")
        val subtype = response?.get("subtype")?.asString
        val isError = subtype == "error" || response?.get("error") != null
        val errMsg = if (isError) {
            response?.get("error")?.asString
                ?: response?.toString()
                ?: "unknown error"
        } else null

        val logSvc = PluginLogService.getInstance(project)
        if (isError) {
            log.warn("CLI control_response error for $requestId: $errMsg")
            logSvc.error("control_response", "req=$requestId error=$errMsg")
            notifyError("Setting change failed: $errMsg")
        } else {
            log.info("CLI control_response OK for $requestId")
            logSvc.info("control_response", "req=$requestId OK")
        }

        // Déclenche le callback de rollback/success enregistré au moment de l'envoi.
        if (requestId != null) {
            val cb = pendingControlRequests.remove(requestId)
            cb?.invoke(!isError, errMsg)
        }
    }

    private fun handleCliSystemEvent(proc: CliProc, json: JsonObject) {
        val subtype = json.get("subtype")?.asString
        // status est ré-émis par claude après un set_permission_mode réussi avec le nouveau
        // mode effectif. Source de vérité pour l'UI (préféré à l'optimistic update).
        if (subtype == "status") {
            val sid = json.get("session_id")?.asString ?: proc.sessionId
            val mode = json.get("permissionMode")?.asString
            if (sid != null && mode != null) {
                updateSessionConfig(sid) { it.copy(currentModeId = mode) }
                proc.permissionModeOverride = mode
                log.info("CLI system:status — permissionMode now $mode for sid=$sid")
            }
            return
        }
        if (subtype != "init") return
        val sid = json.get("session_id")?.asString ?: return
        val previousExecuting = proc.executingSessionId
        val oldProcSid = proc.sessionId
        PluginLogService.getInstance(project).info("permission",
            "🟢 system:init received | claude sid=$sid | proc.sessionId (before)=$oldProcSid | preAssignedSid=${proc.preAssignedSessionId} | match=${sid == proc.preAssignedSessionId}")
        proc.sessionId = sid
        proc.state = State.READY
        cliProcesses[sid] = proc
        pendingCliProcs.remove(proc)
        // Le sessionId global du service = le dernier sid claim (pour compat avec UI existante)
        sessionId = sid
        setState(State.READY)
        // Si un prompt avait été envoyé avec un sid placeholder ("pending-…"), on remap
        // sur le vrai sid pour que les pending changes / chunks soient bien routés.
        if (previousExecuting != null && previousExecuting.startsWith("pending-")) {
            log.info("CLI remap executing sid $previousExecuting → $sid")
            proc.executingSessionId = sid
            if (currentExecutingSessionId == previousExecuting) {
                currentExecutingSessionId = sid
            }
            // Important : updater aussi le prompt en cours dans PromptHistoryService,
            // sinon getPromptsForSession(realSid) ne le retrouve pas et l'historique
            // est cassé (View diff désactivé).
            project.getService(PromptHistoryService::class.java).remapCurrentSessionId(sid)
        }
        // Build la SessionConfig avec la liste hardcodée des models/modes Claude (le CLI
        // n'expose pas la liste dispo dans system:init, juste le current). Liste curated.
        val currentModel = json.get("model")?.asString
        val currentPermMode = json.get("permissionMode")?.asString
        // Liste des slash commands (incluant skills personnels) — exposée par system:init.
        val slashCommands = json.getAsJsonArray("slash_commands")
            ?.mapNotNull { it.asString }.orEmpty()
        // Liste des MCP servers avec leur status (connected / needs-auth / error / failed).
        val mcpServers = json.getAsJsonArray("mcp_servers")?.mapNotNull { el ->
            if (!el.isJsonObject) null
            else {
                val o = el.asJsonObject
                val name = o.get("name")?.asString ?: return@mapNotNull null
                val status = o.get("status")?.asString ?: "unknown"
                McpServerInfo(name, status)
            }
        }.orEmpty()
        // Tools MCP disponibles (préfixés `mcp__`). Permet à l'UI de lister précisément ce
        // que claude peut invoquer côté MCP — distinct des tools natifs Bash/Read/Edit/...
        val mcpTools = json.getAsJsonArray("tools")?.mapNotNull { it.asString }
            ?.filter { it.startsWith("mcp__") }.orEmpty()
        // Update le cache app-level pour que Settings → MCP puisse afficher la liste des tools
        // par server sans avoir besoin de relancer claude.
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
        // Skills isolés (slashCommands marqués skill par claude, claude_code 2.1+).
        val skills = json.getAsJsonArray("skills")?.mapNotNull { it.asString }.orEmpty()
        // Sub-agents : Explore, Plan, general-purpose, statusline-setup, etc.
        val agents = json.getAsJsonArray("agents")?.mapNotNull { it.asString }.orEmpty()
        // Memory paths : où claude charge sa mémoire auto. Permet à l'UI d'exposer un inspector.
        val memoryPathsObj = json.getAsJsonObject("memory_paths")
        if (memoryPathsObj != null) {
            lastMemoryPaths = memoryPathsObj.entrySet().associate { (k, v) ->
                k to (v.asString ?: "")
            }
        }
        val config = SessionConfig(
            models = CLAUDE_MODELS,
            modes = CLAUDE_PERMISSION_MODES,
            configOptions = listOf(CLAUDE_EFFORT_OPTION),
            currentModelId = currentModel,
            currentModeId = currentPermMode,
            currentConfigValues = mapOf("thinking" to "auto"),
            slashCommands = slashCommands,
            mcpServers = mcpServers,
            mcpTools = mcpTools,
            skills = skills,
            agents = agents
        )
        sessionConfigs[sid] = config
        sessionConfigListeners.toList().forEach { it(sid, config) }
        // Notifier les listeners de session créée (pour les chats 2+)
        val snapshot = sessionCreatedListeners.toList()
        snapshot.forEach { it(sid) }
        log.info("Claude CLI session ready: $sid (model=$currentModel)")
    }

    private fun handleCliAssistantEvent(proc: CliProc, json: JsonObject) {
        val sid = json.get("session_id")?.asString ?: proc.sessionId
        val message = json.getAsJsonObject("message") ?: return
        val content = message.getAsJsonArray("content") ?: return
        content.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val block = item.asJsonObject
            when (block.get("type")?.asString) {
                "text" -> {
                    val text = block.get("text")?.asString
                    if (!text.isNullOrEmpty()) {
                        messageChunkListeners.toList().forEach { it(text, sid) }
                    }
                }
                "thinking" -> {
                    val thinking = block.get("thinking")?.asString
                        ?: block.get("text")?.asString
                    if (!thinking.isNullOrEmpty()) {
                        thoughtChunkListeners.toList().forEach { it(thinking, sid) }
                    }
                }
                "tool_use" -> handleCliToolUse(proc, block, sid)
            }
        }
    }

    private fun handleCliUserEvent(proc: CliProc, json: JsonObject) {
        // Contient les tool_results — on les utilise pour marquer les tool_use comme completed
        val sid = json.get("session_id")?.asString ?: proc.sessionId
        val message = json.getAsJsonObject("message") ?: return
        val content = message.getAsJsonArray("content") ?: return
        content.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val block = item.asJsonObject
            if (block.get("type")?.asString == "tool_result") {
                val toolUseId = block.get("tool_use_id")?.asString ?: return@forEach
                val isError = block.get("is_error")?.asBoolean == true
                // Si claude a refusé/bloqué le tool (permissions, etc.), on remonte le message
                // pour que l'user le voie dans le chat plutôt que de le perdre.
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
                        log.info("CLI tool error (sid=$sid): $errorContent")
                        toolResultErrorListeners.toList().forEach { it(errorContent, sid) }
                    }
                }
                // Marquer l'outil comme completed (utilisé par RunCommandBlock pour passer à "Done")
                toolCallListeners.toList().forEach {
                    it(ToolCallInfo(
                        toolCallId = toolUseId,
                        title = "tool",
                        kind = null,
                        status = if (isError) "error" else "completed",
                        path = null,
                        command = null,
                        sessionId = sid
                    ))
                }
            }
        }
    }

    private fun handleCliResultEvent(proc: CliProc, json: JsonObject) {
        val sid = json.get("session_id")?.asString ?: proc.sessionId
        proc.executingSessionId = null
        if (currentExecutingSessionId == sid) {
            setExecuting(false)
        }
        // Update usage stats (cumulative across turns) — utilisé par l'indicateur header.
        if (sid != null) {
            val usage = json.getAsJsonObject("usage")
            val cost = json.get("total_cost_usd")?.let {
                runCatching { it.asDouble }.getOrNull()
            } ?: 0.0
            val current = sessionUsage[sid] ?: UsageStats()
            val previousCost = current.totalCostUsd
            val newStats = current.copy(
                inputTokens = current.inputTokens + (usage?.get("input_tokens")?.asLong ?: 0L),
                outputTokens = current.outputTokens + (usage?.get("output_tokens")?.asLong ?: 0L),
                cacheReadTokens = current.cacheReadTokens + (usage?.get("cache_read_input_tokens")?.asLong ?: 0L),
                cacheCreationTokens = current.cacheCreationTokens + (usage?.get("cache_creation_input_tokens")?.asLong ?: 0L),
                // total_cost_usd dans l'event result est CUMULÉ pour la session, donc on remplace
                totalCostUsd = cost.takeIf { it > current.totalCostUsd } ?: current.totalCostUsd,
                turnCount = current.turnCount + 1
            )
            sessionUsage[sid] = newStats
            usageListeners.toList().forEach { it(sid, newStats) }
            // Add this turn's incremental cost to the rolling weekly counter for budget tracking.
            val deltaCost = (newStats.totalCostUsd - previousCost).coerceAtLeast(0.0)
            if (deltaCost > 0.0) {
                AgentSettings.getInstance().addToCurrentWeek(deltaCost)
            }
        }
        val isError = json.get("is_error")?.asBoolean ?: false
        if (isError) {
            // `errors` (array of strings) est le champ rempli quand subtype=error_during_execution
            // (ex: "No conversation found with session ID: ..." quand --resume échoue).
            // Fallback : `result` (string) sur les erreurs classiques.
            val errorsArr = json.getAsJsonArray("errors")
            val errMsg = if (errorsArr != null && errorsArr.size() > 0) {
                errorsArr.mapNotNull { it.asString }.joinToString("; ")
            } else {
                json.get("result")?.asString ?: "unknown error"
            }
            notifyError("Claude error: $errMsg")
            // Forward aussi vers les chats de cette session (les errorListeners globaux affichent
            // partout mais on veut que le sid actuel le voie en priorité dans son chat).
            toolResultErrorListeners.toList().forEach { it(errMsg, sid) }
        }
    }

    private fun handleCliControlRequest(proc: CliProc, json: JsonObject) {
        // Avec --permission-mode acceptEdits, on n'est pas censé recevoir de control_request,
        // mais si jamais il arrive, on répond "allow_once" pour ne pas bloquer.
        val requestId = json.get("request_id")?.asString ?: return
        val resp = """{"type":"control_response","request_id":${escapeJson(requestId)},"response":{"decision":"allow_once"}}"""
        try {
            proc.writer.write("$resp\n")
            proc.writer.flush()
        } catch (e: Exception) {
            log.warn("CLI control_response write failed", e)
        }
    }

    private fun handleCliToolUse(proc: CliProc, block: JsonObject, sid: String?) {
        val toolName = block.get("name")?.asString ?: return
        val toolUseId = block.get("id")?.asString
        val input = block.getAsJsonObject("input")

        // Tools interactifs qui attendent une réponse user (sinon claude tombe en erreur
        // "Exit plan mode?" / "Answer questions?"). On les expose à l'UI via planContent /
        // userQuestionsJson pour qu'elle affiche une carte dédiée avec actions.
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
                sessionId = sid,
                planContent = planContent,
                userQuestionsJson = userQuestionsJson
            )
            toolCallListeners.toList().forEach { it(info) }
            return
        }

        val path = input?.get("file_path")?.asString
            ?: input?.get("path")?.asString
            ?: input?.get("filePath")?.asString
        val command = input?.get("command")?.asString
        // Pour Write : full content. Pour Edit : old/new strings. Permet d'afficher
        // un preview inline en mode plan (où le fichier n'est pas écrit sur disque).
        val writeContent = input?.get("content")?.asString
        val editOld = input?.get("old_string")?.asString
        val editNew = input?.get("new_string")?.asString
        // Détails secondaires affichés dans la tool card pour donner du contexte sans
        // forcer l'user à déplier le bloc. Ex: pattern Grep, url WebFetch, skill name.
        val detail = when (toolName) {
            "Grep", "Glob" -> input?.get("pattern")?.asString
            "WebFetch" -> input?.get("url")?.asString
            "WebSearch" -> input?.get("query")?.asString
            "Task" -> input?.get("description")?.asString
                ?: input?.get("subagent_type")?.asString
            "TodoWrite" -> {
                val todos = input?.getAsJsonArray("todos")
                if (todos != null) "${todos.size()} item(s)" else null
            }
            "Skill" -> input?.get("skill")?.asString
            "Bash" -> command  // duplicate pour que l'aperçu marche aussi dans ToolCallsBlock
            "Read", "Edit", "Write", "MultiEdit", "NotebookEdit" -> path
            "ToolSearch" -> input?.get("query")?.asString
            "AskUserQuestion" -> "(question)"
            "ExitPlanMode" -> "(plan)"
            // Pour les MCP tools (mcp__...) : afficher les paramètres compacts.
            else -> {
                if (toolName.startsWith("mcp__")) {
                    input?.entrySet()
                        ?.joinToString(", ", limit = 3, truncated = "…") { "${it.key}=${it.value}" }
                } else null
            }
        }

        // Permission mode courant = override du proc, sinon valeur initiale (acceptEdits par défaut)
        val permMode = proc.permissionModeOverride
            ?: sessionConfigs[proc.sessionId]?.currentModeId

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
            sessionId = sid,
            writeContent = writeContent,
            editOldString = editOld,
            editNewString = editNew,
            permissionMode = permMode,
            detail = detail
        )
        toolCallListeners.toList().forEach { it(info) }

        // Pre-capture BEFORE + retries refresh + fallback addOrUpdate
        // (même logique que pour ACP — on s'appuie sur le VFS listener pour détecter
        // les écritures réelles, avec retries pour gérer la latence inotify).
        if (path != null && shouldTrackFile(path)) {
            if (!toolCallPreCapturedBefore.containsKey(path)) {
                val before = readFileContent(path)
                toolCallPreCapturedBefore[path] = before
            }
            if (toolName in setOf("Write", "Edit", "MultiEdit")) {
                scheduleVfsRefreshAndFallback(path)
            }
        }
    }

    private fun scheduleVfsRefreshAndFallback(path: String) {
        scope.launch {
            // Retries refresh
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
            // Fallback addOrUpdate si VFS ne fire pas
            delay(400)
            ApplicationManager.getApplication().invokeLater {
                if (!shouldTrackFile(path)) return@invokeLater
                val before = toolCallPreCapturedBefore.remove(path) ?: return@invokeLater
                try {
                    val after = java.io.File(path).readText(Charsets.UTF_8)
                    if (before == after) return@invokeLater
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                        ?: return@invokeLater
                    val historyService = project.getService(PromptHistoryService::class.java)
                    val pending = project.getService(PendingChangesService::class.java)
                    historyService.captureFileBefore(path, before)
                    historyService.captureFileAfter(path, after)
                    log.info("CLI fallback addOrUpdate path=$path before=${before.length}c after=${after.length}c sid=$currentExecutingSessionId")
                    pending.addOrUpdate(path, before, after, vf, currentExecutingSessionId)
                    project.getService(DiffViewerManager::class.java).scheduleRefresh()
                } catch (e: Exception) {
                    log.warn("CLI fallback addOrUpdate failed for $path", e)
                }
            }
        }
    }

    /**
     * Envoie un prompt user sur le stdin du process claude correspondant à `targetSessionId`.
     * Si pas de process pour ce sid, on échoue (le panel parent doit appeler newSession() avant).
     */
    private fun sendCliPrompt(
        text: String,
        targetSessionId: String?,
        attachments: List<PromptAttachment>
    ) {
        // Trouve le process : par sid si fourni, sinon le 1er process actif (claimed ou pending).
        // Avant le 1er prompt, le process est dans pendingCliProcs (pas de sid encore).
        val proc = when {
            targetSessionId != null -> cliProcesses[targetSessionId]
            cliProcesses.isNotEmpty() -> cliProcesses.values.firstOrNull()
            else -> pendingCliProcs.firstOrNull()
        }
        if (proc == null) {
            notifyError("No Claude CLI process available")
            return
        }
        if (proc.state != State.READY) {
            notifyError("Claude CLI not ready (state=${proc.state})")
            return
        }
        // Si pas encore de sid, on utilise un placeholder pour le historyService.
        // Le vrai sid sera claim quand system:init arrivera et la session sera "rebaptisée".
        val sid = proc.sessionId ?: "pending-${System.currentTimeMillis()}"

        val historyService = project.getService(PromptHistoryService::class.java)
        historyService.startPrompt(text, sid)
        pendingVfsChanges.clear()
        toolCallPreCapturedBefore.clear()
        currentExecutingSessionId = sid
        proc.executingSessionId = sid
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
                    // Claude lit les fichiers via Read tool ; on injecte @path dans le texte.
                    contentArr.add("""{"type":"text","text":${escapeJson(" @${att.absolutePath}")}}""")
                }
                is PromptAttachment.CodeRef -> {
                    // Bloc texte enrichi : chemin + lignes + fenced code block langage-aware.
                    // Format inspiré de Cursor pour qu'on identifie immédiatement le fragment.
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
            proc.writer.write("$msg\n")
            proc.writer.flush()
            log.info("CLI prompt sent sid=$sid (${text.take(80)}…)")
        } catch (e: Exception) {
            log.error("CLI write failed", e)
            notifyError("Failed to send prompt: ${e.message}")
            setExecuting(false)
        }
    }

    /**
     * Réponse à un tool_use claude qui attend une input user (ExitPlanMode, AskUserQuestion).
     * Envoie un message `user` contenant un `tool_result` lié au `toolUseId`. Le texte est
     * libre — claude le lit comme l'input de l'utilisateur (ex: "User approved the plan"
     * ou "Selected: Option B for question 1").
     *
     * Important : ce message déclenche un nouveau tour assistant (claude reprend après le
     * tool_result). En conséquence on remet executing=true pour que l'UI montre l'état actif.
     */
    fun replyToolResult(toolUseId: String, contentText: String, targetSessionId: String?) {
        if (activeProfile.transport != Transport.CLI_STREAM_JSON) {
            log.info("replyToolResult: skipped (transport != CLI_STREAM_JSON)")
            return
        }
        val proc = findCliProc(targetSessionId) ?: run {
            notifyError("No Claude CLI process to reply on")
            return
        }
        val sid = proc.sessionId ?: targetSessionId
        val msg = """{"type":"user","message":{"role":"user","content":[""" +
            """{"type":"tool_result","tool_use_id":${escapeJson(toolUseId)},""" +
            """"content":${escapeJson(contentText)}}""" +
            """]}}"""
        try {
            proc.writer.write("$msg\n")
            proc.writer.flush()
            if (sid != null) {
                currentExecutingSessionId = sid
                proc.executingSessionId = sid
                setExecuting(true)
            }
            log.info("CLI tool_result reply sent (toolUseId=$toolUseId, sid=$sid, ${contentText.take(60)}…)")
        } catch (e: Exception) {
            log.error("CLI tool_result write failed", e)
            notifyError("Failed to send reply: ${e.message}")
        }
    }

    private fun cancelCliPrompt(targetSessionId: String?) {
        // Stop doit TOUJOURS pouvoir s'exécuter. On cherche le proc dans cet ordre :
        //  1) cliProcesses[targetSessionId] si fourni
        //  2) un proc actif (executingSessionId != null) dans cliProcesses
        //  3) un proc actif dans pendingCliProcs (cas resume pas encore claim)
        //  4) n'importe quel proc disponible
        val proc = when {
            targetSessionId != null && cliProcesses[targetSessionId] != null ->
                cliProcesses[targetSessionId]
            else -> cliProcesses.values.firstOrNull { it.executingSessionId != null }
                ?: pendingCliProcs.firstOrNull { it.executingSessionId != null }
                ?: cliProcesses.values.firstOrNull()
                ?: pendingCliProcs.firstOrNull()
        }
        if (proc == null) {
            log.info("No CLI process to cancel for sid=$targetSessionId")
            // En dernier recours, on remet isPromptExecuting à false pour que l'UI ne reste
            // pas bloquée avec un bouton Stop inactif si plus aucun proc n'existe.
            if (currentExecutingSessionId != null) setExecuting(false)
            return
        }
        val requestId = "interrupt-${System.currentTimeMillis()}"
        val msg = """{"type":"control_request","request_id":${escapeJson(requestId)},"request":{"subtype":"interrupt"}}"""
        log.info("Sending interrupt to CLI sid=${proc.sessionId} (executing=${proc.executingSessionId})")
        var written = false
        try {
            proc.writer.write("$msg\n")
            proc.writer.flush()
            written = true
        } catch (e: Exception) {
            log.warn("CLI interrupt write failed, falling back to destroyProcess", e)
        }
        // Si l'écriture a échoué OU si claude n'envoie pas de result rapidement, kill le proc.
        // L'user doit toujours retrouver un état idle. On force le state UI immédiatement.
        if (!written) {
            try { proc.handler.destroyProcess() } catch (e2: Exception) { log.warn("CLI cancel destroy failed", e2) }
        }
        // Toujours mettre executing=false ici, même si interrupt est asynchrone — l'user a cliqué
        // Stop, l'UI doit refléter ce choix instantanément (le bouton ⏹ retombe sur ➤).
        setExecuting(false)
    }

    /** Spawn un nouveau process claude pour un nouveau chat (multi-process). */
    private fun newCliSession(onCreated: ((String) -> Unit)?) {
        val proc = spawnClaudeCli(resumeSid = null)
        if (proc != null) {
            pendingCliProcs.add(proc)
            proc.state = State.READY
            setState(State.READY)
            // CRITIQUE : on attribue immédiatement le preAssignedSid au panel, AVANT que
            // claude n'envoie son system:init. Garantit que mySessionId du panel est set
            // dès la création du chat → routing strict des permissions/messages par sid.
            val sid = proc.preAssignedSessionId
            if (sid != null) {
                // FIX : aligner proc.sessionId DÈS LE SPAWN (cf startCliAgent).
                proc.sessionId = sid
                cliProcesses[sid] = proc
                pendingCliProcs.remove(proc)
                PluginLogService.getInstance(project).info("permission",
                    "🟢 newCliSession: proc.sessionId pré-assigné à $sid (avant system:init)")
                onCreated?.invoke(sid)
            }
        } else {
            setState(State.ERROR)
        }
    }

    /**
     * Reprend une session Claude Code existante (stockée dans ~/.claude/projects/...) en
     * spawnant un nouveau process claude avec --resume <sid>. Le sid réel émis par claude
     * peut différer (claude génère parfois un nouveau sid à la reprise) ; on remap via
     * sessionCreatedListener.
     */
    fun resumeCliSession(
        resumeSid: String,
        cwdOverride: String? = null,
        onCreated: ((String) -> Unit)? = null
    ) {
        if (activeProfile.transport != Transport.CLI_STREAM_JSON) {
            notifyError("Resume only supported for Claude CLI profile")
            return
        }
        if (onCreated != null) {
            sessionCreatedListeners.add(object : (String) -> Unit {
                override fun invoke(sid: String) {
                    onCreated(sid)
                    sessionCreatedListeners.remove(this)
                }
            })
        }
        val proc = spawnClaudeCli(resumeSid = resumeSid, cwdOverride = cwdOverride)
        if (proc != null) {
            pendingCliProcs.add(proc)
            proc.state = State.READY
            setState(State.READY)
            log.info("Claude CLI resuming session $resumeSid (cwd=${cwdOverride ?: project.basePath})")
        } else {
            notifyError("Failed to spawn Claude CLI for resume")
            setState(State.ERROR)
        }
    }
}
