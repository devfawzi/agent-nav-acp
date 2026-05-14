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
 * Service principal qui :
 * 1. Lance claude-agent-acp comme processus enfant
 * 2. Suit le flow protocole ACP : initialize → session/new → session/prompt
 * 3. Intercepte les écritures de fichiers pour afficher les diffs
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
        val sessionId: String?
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

    data class Prerequisites(
        val npxPath: String?,
        val claudeCliPath: String?,
        val claudeConfigDir: String?
    ) {
        val allOk: Boolean get() = npxPath != null && claudeCliPath != null
        val missing: List<String> = buildList {
            if (npxPath == null) add("Node.js / npx")
            if (claudeCliPath == null) add("Claude Code CLI")
        }
    }

    fun checkPrerequisites(): Prerequisites = Prerequisites(
        npxPath = AgentBinaryResolver.resolveNpx(),
        claudeCliPath = AgentBinaryResolver.resolveClaudeCli(),
        claudeConfigDir = "${System.getProperty("user.home")}/.claude".takeIf { File(it).isDirectory }
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

    fun setConfigOption(optionId: String, value: String, targetSessionId: String? = null) {
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

    fun stopAgent() {
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
}
