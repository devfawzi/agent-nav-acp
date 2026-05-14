package com.claudeacp

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

    enum class State { STOPPED, STARTING, INITIALIZING, CREATING_SESSION, READY, ERROR }

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

    /** Config par session (chaque chat a son propre sessionConfig). */
    private val sessionConfigs = ConcurrentHashMap<String, SessionConfig>()

    fun getSessionConfig(sid: String?): SessionConfig =
        if (sid != null) sessionConfigs[sid] ?: SessionConfig() else SessionConfig()

    data class SelectOption(val id: String, val name: String, val description: String? = null)

    data class SessionConfig(
        val models: List<SelectOption> = emptyList(),
        val modes: List<SelectOption> = emptyList(),
        val configOptions: List<ConfigOption> = emptyList(),
        val currentModelId: String? = null,
        val currentModeId: String? = null,
        val currentConfigValues: Map<String, String> = emptyMap()
    )

    data class ConfigOption(
        val id: String,
        val name: String,
        val type: String,
        val options: List<SelectOption> = emptyList(),
        val currentValue: String? = null
    )

    data class ToolCallInfo(
        val toolCallId: String?,
        val title: String,
        val kind: String?,
        val status: String?,
        val path: String?,
        val command: String?,
        val sessionId: String?,
        /** Pour les Write : le contenu intégral à écrire (permet d'afficher un preview
         *  inline en mode plan où le fichier n'est pas réellement créé sur disque). */
        val writeContent: String? = null,
        /** Pour les Edit / MultiEdit : le diff old→new à afficher en preview. */
        val editOldString: String? = null,
        val editNewString: String? = null,
        /** Permission mode actif (plan, acceptEdits, …) — permet à l'UI de savoir si
         *  le fichier va vraiment être créé ou juste proposé. */
        val permissionMode: String? = null
    )

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
                    if (!history.hasActivePrompt()) return

                    events.forEach { event ->
                        try {
                            when (event) {
                                is VFileContentChangeEvent -> {
                                    if (event.isFromSave) return@forEach
                                    val path = event.file.path
                                    if (!shouldTrackFile(path)) return@forEach
                                    if (pending.consumeRejectFlag(path)) return@forEach
                                    if (!pendingVfsChanges.containsKey(path)) {
                                        // Préférer le BEFORE pre-capturé via tool_call : OpenCode
                                        // écrit si vite que le VFS peut déjà avoir rafraîchi son
                                        // cache au nouveau contenu au moment de ce callback.
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
        agentSwitchedListeners.forEach { it(profile) }
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
            stderrListeners.forEach { it(trimmed) }
        }
    }

    private fun handleAcpMessage(json: JsonObject) {
        messageListeners.forEach { it(json) }

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
        executingListeners.forEach { it(value, sid) }
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
                if (!text.isNullOrEmpty()) messageChunkListeners.forEach { it(text, sid) }
            }
            "agent_thought_chunk", "agentThoughtChunk" -> {
                if (!text.isNullOrEmpty()) thoughtChunkListeners.forEach { it(text, sid) }
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
                    messageChunkListeners.forEach { it("[$type] $text", sid) }
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

        val info = ToolCallInfo(
            toolCallId = toolCallId,
            title = title,
            kind = kind,
            status = status,
            path = pathFromInput ?: paths.firstOrNull(),
            command = command,
            sessionId = sid
        )

        val genericTitles = setOf("tool", "edit", "write", "read", "bash", "find", "grep")
        val isGenericOnly = title.lowercase() in genericTitles &&
            info.path == null && info.command == null && status != "completed"
        if (!isGenericOnly) {
            toolCallListeners.forEach { it(info) }
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
        state = newState
        stateListeners.forEach { it(newState) }
    }

    private fun notifyInfo(msg: String) {
        log.info(msg)
        infoListeners.forEach { it(msg) }
    }

    private fun notifyError(msg: String) {
        log.warn(msg)
        errorListeners.forEach { it(msg) }
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
        sessionConfigListeners.forEach { it(sid, config) }
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
        sessionConfigListeners.forEach { it(sid, updated) }
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
        val requestId = "set-model-${System.currentTimeMillis()}"
        val msg = """{"type":"control_request","request_id":${escapeJson(requestId)},""" +
            """"request":{"subtype":"set_model","model":${escapeJson(modelId)}}}"""
        try {
            proc.writer.write("$msg\n")
            proc.writer.flush()
            log.info("CLI set_model sent: $modelId (sid=$sid)")
            // Optimistic UI update — sera reverti si claude répond error
            if (sid != null) {
                updateSessionConfig(sid) { it.copy(currentModelId = modelId) }
            }
            proc.modelOverride = modelId
        } catch (e: Exception) {
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
        val requestId = "set-mode-${System.currentTimeMillis()}"
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
            resumeSid = null,  // --resume désactivé en v1 (incompat stream-json)
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
                        sessionReboundListeners.forEach { it(oldSid, sid) }
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
        val sid = proc.sessionId
        val requestId = "set-effort-${System.currentTimeMillis()}"
        val msg = """{"type":"control_request","request_id":${escapeJson(requestId)},""" +
            """"request":{"subtype":"set_effort","level":${escapeJson(value)}}}"""
        try {
            proc.writer.write("$msg\n")
            proc.writer.flush()
            log.info("CLI set_effort sent: $value (sid=$sid)")
            if (sid != null) {
                updateSessionConfig(sid) {
                    it.copy(currentConfigValues = it.currentConfigValues + (optionId to value))
                }
            }
            proc.effortOverride = value
        } catch (e: Exception) {
            log.warn("CLI set_effort write failed", e)
            notifyError("Failed to set effort: ${e.message}")
        }
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
        @Volatile var effortOverride: String? = null
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

    /** Permission modes supportés par claude CLI. */
    private val CLAUDE_PERMISSION_MODES = listOf(
        SelectOption("default", "Default (prompt for each)"),
        SelectOption("acceptEdits", "Accept edits (auto-approve Write/Edit)"),
        SelectOption("bypassPermissions", "Bypass all (auto-approve everything)"),
        SelectOption("plan", "Plan mode (read-only)")
    )

    /** Effort / extended thinking — utilise `--effort` flag de claude CLI. */
    private val CLAUDE_EFFORT_OPTION = ConfigOption(
        id = "thinking",
        name = "Effort",
        type = "select",
        options = listOf(
            SelectOption("auto", "Auto (model default)"),
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

    private fun startCliAgent(): Boolean {
        val proc = spawnClaudeCli(resumeSid = null) ?: run {
            setState(State.ERROR)
            return false
        }
        pendingCliProcs.add(proc)
        // En CLI : le process est vivant dès maintenant, on peut écrire sur stdin.
        // Pas besoin d'attendre system:init (qui n'arrive qu'après le 1er prompt envoyé
        // dans certaines versions de claude). On set READY direct pour activer l'input.
        proc.state = State.READY
        setState(State.READY)
        log.info("Claude CLI spawned, state=READY (sessionId pending until first user message)")
        return true
    }

    private fun spawnClaudeCli(
        resumeSid: String?,
        modelOverride: String? = null,
        permissionModeOverride: String? = null,
        effortOverride: String? = null
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

            // Si permissionModeOverride est set, on remplace la valeur dans resolvedArgs
            val baseArgs = if (permissionModeOverride != null) {
                val mutable = resolvedArgs.toMutableList()
                val idx = mutable.indexOf("--permission-mode")
                if (idx >= 0 && idx + 1 < mutable.size) {
                    mutable[idx + 1] = permissionModeOverride
                } else {
                    mutable += listOf("--permission-mode", permissionModeOverride)
                }
                mutable.toList()
            } else resolvedArgs

            // Préfixes : --model, --effort, --resume. NOTE: --resume <sid> sans --print
            // semble forcer le mode TUI interactif et casse stream-json. Donc en respawn,
            // on n'utilise PAS --resume pour l'instant — la conv repart à zéro (limite v1).
            // L'historique des fichiers reste accessible via pending changes et le chat UI.
            val argsForLaunch = buildList<String> {
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
                project.basePath?.let { workDirectory = File(it) }
                withRedirectErrorStream(false)
                File(claudeBin).parentFile?.absolutePath?.let { binDir ->
                    val currentPath = System.getenv("PATH") ?: ""
                    environment["PATH"] = "$binDir:$currentPath"
                }
                // Forcer Node à ne pas colorer (claude est en Node) au cas où ça parasite la JSON
                environment["NO_COLOR"] = "1"
                environment["TERM"] = "dumb"
            }

            log.info("Starting Claude CLI: ${command.commandLineString}")

            val handler = OSProcessHandler(command)
            val writer = handler.processInput.bufferedWriter(Charsets.UTF_8)
            val proc = CliProc(handler, writer)

            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when (outputType) {
                        ProcessOutputType.STDOUT -> handleCliStdout(proc, event.text)
                        ProcessOutputType.STDERR -> handleCliStderr(proc, event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    log.info("Claude CLI process terminated (exit ${event.exitCode}) sid=${proc.sessionId}")
                    proc.sessionId?.let { cliProcesses.remove(it) }
                    pendingCliProcs.remove(proc)
                    if (proc.state != State.STOPPED) {
                        proc.state = State.STOPPED
                    }
                    // Mettre à jour le state global : ERROR si plus aucun process actif
                    if (cliProcesses.isEmpty() && pendingCliProcs.isEmpty() && state != State.STOPPED) {
                        if (event.exitCode != 0) setState(State.ERROR)
                        else setState(State.STOPPED)
                    }
                    if (proc.executingSessionId == currentExecutingSessionId) {
                        setExecuting(false)
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
            stderrListeners.forEach { it(trimmed) }
        }
    }

    private fun handleCliEvent(proc: CliProc, json: JsonObject) {
        messageListeners.forEach { it(json) }
        when (json.get("type")?.asString) {
            "system" -> handleCliSystemEvent(proc, json)
            "assistant" -> handleCliAssistantEvent(proc, json)
            "user" -> handleCliUserEvent(proc, json)
            "result" -> handleCliResultEvent(proc, json)
            "rate_limit_event" -> { /* ignore for now */ }
            "control_request" -> handleCliControlRequest(proc, json)
            "control_response" -> handleCliControlResponse(proc, json)
            else -> {}
        }
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
        if (subtype == "error" || response?.get("error") != null) {
            val errMsg = response?.get("error")?.asString
                ?: response?.toString()
                ?: "unknown error"
            log.warn("CLI control_response error for $requestId: $errMsg")
            notifyError("Setting change failed: $errMsg")
        } else {
            log.info("CLI control_response OK for $requestId")
        }
    }

    private fun handleCliSystemEvent(proc: CliProc, json: JsonObject) {
        if (json.get("subtype")?.asString != "init") return
        val sid = json.get("session_id")?.asString ?: return
        val previousExecuting = proc.executingSessionId
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
        val config = SessionConfig(
            models = CLAUDE_MODELS,
            modes = CLAUDE_PERMISSION_MODES,
            configOptions = listOf(CLAUDE_EFFORT_OPTION),
            currentModelId = currentModel,
            currentModeId = currentPermMode,
            currentConfigValues = mapOf("thinking" to "auto")
        )
        sessionConfigs[sid] = config
        sessionConfigListeners.forEach { it(sid, config) }
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
                        messageChunkListeners.forEach { it(text, sid) }
                    }
                }
                "thinking" -> {
                    val thinking = block.get("thinking")?.asString
                        ?: block.get("text")?.asString
                    if (!thinking.isNullOrEmpty()) {
                        thoughtChunkListeners.forEach { it(thinking, sid) }
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
                // Marquer l'outil comme completed (utilisé par RunCommandBlock pour passer à "Done")
                toolCallListeners.forEach {
                    it(ToolCallInfo(
                        toolCallId = toolUseId,
                        title = "tool",
                        kind = null,
                        status = "completed",
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
        val isError = json.get("is_error")?.asBoolean ?: false
        if (isError) {
            val errMsg = json.get("result")?.asString ?: "unknown error"
            notifyError("Claude turn failed: $errMsg")
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

        val path = input?.get("file_path")?.asString
            ?: input?.get("path")?.asString
            ?: input?.get("filePath")?.asString
        val command = input?.get("command")?.asString
        // Pour Write : full content. Pour Edit : old/new strings. Permet d'afficher
        // un preview inline en mode plan (où le fichier n'est pas écrit sur disque).
        val writeContent = input?.get("content")?.asString
        val editOld = input?.get("old_string")?.asString
        val editNew = input?.get("new_string")?.asString

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
            permissionMode = permMode
        )
        toolCallListeners.forEach { it(info) }

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

    private fun cancelCliPrompt(targetSessionId: String?) {
        val proc = when {
            targetSessionId != null -> cliProcesses[targetSessionId]
            else -> cliProcesses.values.firstOrNull { it.executingSessionId != null }
        }
        if (proc == null) {
            log.info("No CLI process to cancel for sid=$targetSessionId")
            return
        }
        // Pas de session/cancel en CLI — on kill le process. La conversation est perdue
        // sauf si on respawn avec --resume <sid>.
        log.info("Cancelling CLI process for sid=${proc.sessionId}")
        try {
            proc.handler.destroyProcess()
        } catch (e: Exception) {
            log.warn("CLI cancel failed", e)
        }
        if (proc.executingSessionId == currentExecutingSessionId) {
            setExecuting(false)
        }
    }

    /** Spawn un nouveau process claude pour un nouveau chat (multi-process). */
    private fun newCliSession(onCreated: ((String) -> Unit)?) {
        if (onCreated != null) {
            sessionCreatedListeners.add(object : (String) -> Unit {
                override fun invoke(sid: String) {
                    onCreated(sid)
                    sessionCreatedListeners.remove(this)
                }
            })
        }
        val proc = spawnClaudeCli(resumeSid = null)
        if (proc != null) {
            pendingCliProcs.add(proc)
            proc.state = State.READY
            setState(State.READY)
        } else {
            setState(State.ERROR)
        }
    }
}
