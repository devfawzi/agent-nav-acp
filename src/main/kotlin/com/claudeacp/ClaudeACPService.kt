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

    /** Le sessionId du prompt actuellement en cours d'exécution (mis à jour par sendPrompt,
     *  utilisé pour tagger les pending changes avec la session qui les a déclenchés). */
    @Volatile
    var currentExecutingSessionId: String? = null
        private set

    /** true entre l'envoi d'un prompt et la réception de sa response finale. */
    @Volatile
    var isPromptExecuting: Boolean = false
        private set

    private val nextRequestId = AtomicLong(1)
    private val pendingRequests = mutableMapOf<Long, (JsonObject) -> Unit>()

    // Listeners (sid? = sessionId du chunk, null si pas attaché à une session spécifique)
    private val messageListeners = mutableListOf<(JsonObject) -> Unit>()
    private val stderrListeners = mutableListOf<(String) -> Unit>()
    private val infoListeners = mutableListOf<(String) -> Unit>()
    private val errorListeners = mutableListOf<(String) -> Unit>()
    private val messageChunkListeners = mutableListOf<(String, String?) -> Unit>()
    private val thoughtChunkListeners = mutableListOf<(String, String?) -> Unit>()
    private val toolCallListeners = mutableListOf<(ToolCallInfo) -> Unit>()
    private val stateListeners = mutableListOf<(State) -> Unit>()
    private val sessionConfigListeners = mutableListOf<(SessionConfig) -> Unit>()
    private val sessionCreatedListeners = mutableListOf<(String) -> Unit>()
    private val executingListeners = mutableListOf<(Boolean) -> Unit>()

    @Volatile
    var sessionConfig: SessionConfig = SessionConfig()
        private set

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

    /** Info enrichie passée aux listeners de tool_call pour permettre un affichage spécialisé. */
    data class ToolCallInfo(
        val toolCallId: String?,
        val title: String,
        val kind: String?,
        val status: String?,
        val path: String?,
        val command: String?,
        val sessionId: String?
    )

    // VFS tracking: stocke le contenu BEFORE quand un fichier change pendant un prompt actif
    private val pendingVfsChanges = ConcurrentHashMap<String, String>()
    // BEFORE capturé en amont via un tool_call ACP (plus précis que VFS pour les nouveaux fichiers)
    private val toolCallPreCapturedBefore = ConcurrentHashMap<String, String>()
    // Paths affectés par chaque toolCallId — utilisé pour rafraîchir agressivement
    // les fichiers à plusieurs reprises (workaround pour la limite inotify Linux).
    private val pathsByToolCallId = ConcurrentHashMap<String, MutableSet<String>>()
    // Auto-open du diff viewer à la 1ère modif d'un prompt (reset au début de chaque prompt)
    @Volatile
    private var autoOpenedDiffThisPrompt = false

    init {
        subscribeToVfsChanges()
    }

    /**
     * Filtre les fichiers qu'on track pour le diff :
     * - Doit être sous project.basePath
     * - Pas dans des dossiers générés (build/, .gradle/, .idea/, .git/, etc.)
     */
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
                                    // Skip si ce change vient d'un reject en cours (revert vers BEFORE)
                                    if (pending.consumeRejectFlag(path)) {
                                        log.info("VFS before: skipping reject revert for $path")
                                        return@forEach
                                    }
                                    if (!pendingVfsChanges.containsKey(path)) {
                                        val before = String(event.file.contentsToByteArray())
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

                        // Cleanup le pre-capture si on avait pré-stocké un before (avant le check reject)
                        val before = pendingVfsChanges.remove(path)

                        // Skip si ce change vient d'un reject (le before() a peut-être déjà consommé,
                        // mais on re-check au cas où l'event before() n'a pas été reçu)
                        if (pending.consumeRejectFlag(path)) {
                            log.info("VFS after: skipping reject revert for $path")
                            return@forEach
                        }

                        if (before == null) return@forEach

                        try {
                            val after = String(file.contentsToByteArray())
                            if (before == after) return@forEach

                            history.captureFileAfter(path, after)
                            log.info("VFS after: about to addOrUpdate path=$path before=${before.length}c after=${after.length}c sid=$currentExecutingSessionId")
                            pending.addOrUpdate(path, before, after, file, currentExecutingSessionId)
                            log.info("VFS after: addOrUpdate done, now calling DiffViewerManager.scheduleRefresh")

                            // Appel direct au cas où le DiffViewerManager ne serait pas
                            // encore abonné en tant que listener (lazy service).
                            project.getService(DiffViewerManager::class.java).scheduleRefresh()
                        } catch (e: Exception) {
                            log.warn("VFS after capture failed for $path", e)
                        }
                    }
                }
            }
        )
    }

    fun startAgent(): Boolean {
        if (state != State.STOPPED && state != State.ERROR) {
            log.info("Agent already in state $state")
            return state == State.READY
        }
        setState(State.STARTING)
        sessionId = null
        pendingRequests.clear()
        lineBuffer.clear()

        // Force l'instanciation du DiffViewerManager pour qu'il s'abonne au
        // PendingChangesService avant le premier `addOrUpdate` (sinon il rate
        // les notifications du 1er prompt parce qu'il est lazy).
        project.getService(DiffViewerManager::class.java)

        return try {
            val npxPath = findNpxPath() ?: run {
                notifyError("Cannot find npx binary. Install Node.js or check nvm.")
                setState(State.ERROR)
                return false
            }

            val command = GeneralCommandLine().apply {
                exePath = npxPath
                addParameters("--yes", "@agentclientprotocol/claude-agent-acp")
                project.basePath?.let { workDirectory = File(it) }
                withRedirectErrorStream(false)
                // Add node binary dir to PATH so the child npm process can find node
                File(npxPath).parentFile?.absolutePath?.let { nodeDir ->
                    val currentPath = System.getenv("PATH") ?: ""
                    environment["PATH"] = "$nodeDir:$currentPath"
                }
            }

            notifyInfo("Starting: ${command.commandLineString}")

            processHandler = OSProcessHandler(command)
            processHandler!!.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when (outputType) {
                        ProcessOutputType.STDOUT -> handleStdout(event.text)
                        ProcessOutputType.STDERR -> handleStderr(event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    notifyError("Agent terminated unexpectedly (exit ${event.exitCode})")
                    setState(State.ERROR)
                }
            })

            processHandler!!.startNotify()

            // Step 1 : handshake
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

    /** Délégué à [AgentBinaryResolver] (env var > settings > auto-discover > which). */
    private fun findNpxPath(): String? = AgentBinaryResolver.resolveNpx()

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
                    notifyInfo("(non-json stdout) $line")
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
        // Raw debug listener
        messageListeners.forEach { it(json) }

        val method = json.get("method")?.asString
        val idElem = json.get("id")?.takeIf { !it.isJsonNull }
        val id = idElem?.let { runCatching { it.asLong }.getOrNull() }
        val hasResult = json.has("result")
        val hasError = json.has("error")

        when {
            // Response to one of our requests
            id != null && method == null && (hasResult || hasError) -> {
                val handler = pendingRequests.remove(id)
                if (handler != null) {
                    handler(json)
                } else {
                    log.warn("Unhandled response id=$id")
                }
            }
            // Request from agent that needs a response (has id + method)
            id != null && method != null -> {
                when (method) {
                    "fs/write_text_file", "fs/writeTextFile" -> handleFileWrite(json)
                    "fs/read_text_file", "fs/readTextFile" -> handleFileRead(json)
                    "session/request_permission" -> handlePermissionRequest(json)
                    else -> {
                        log.warn("Unhandled agent request: $method id=$id")
                        // Reply with null result so agent doesn't hang
                        sendRawMessage("""{"jsonrpc":"2.0","id":$id,"result":null}""")
                    }
                }
            }
            // Notification from agent (no id)
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
                log.info("Initialized. Creating session...")
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
                    parseSessionCapabilities(result)
                    setState(State.READY)
                    log.info("Session ready: $sid")
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

    /**
     * Crée une nouvelle session ACP (n'invalide PAS les sessions précédentes côté agent —
     * l'agent ACP supporte plusieurs sessions concurrentes via leurs sessionId distincts).
     * Le `sessionId` field du service est mis à jour au sid le plus récent, mais les anciens
     * restent utilisables explicitement via `sendPrompt(text, sid)`.
     */
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
        log.info("Creating new session...")
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
        // Reset tracking pour ce prompt
        pendingVfsChanges.clear()
        toolCallPreCapturedBefore.clear()
        autoOpenedDiffThisPrompt = false
        currentExecutingSessionId = sid
        setExecuting(true)

        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) {
                notifyError("session/prompt failed: ${response.get("error")}")
            } else {
                log.info("Prompt completed: ${response.get("result")}")
            }
            setExecuting(false)
        }

        // Construit le tableau `prompt[]` : texte + attachments (resource_link / image)
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

    /**
     * Annule le prompt en cours via ACP `session/cancel`.
     * L'agent va terminer la réponse en cours et envoyer la response finale avec stopReason.
     */
    fun cancelPrompt() {
        val sid = currentExecutingSessionId ?: sessionId ?: return
        log.info("Cancelling prompt for session $sid")
        // session/cancel est une notification (pas de response attendue)
        val msg = """{"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"$sid"}}"""
        sendRawMessage(msg)
    }

    private fun setExecuting(value: Boolean) {
        if (isPromptExecuting == value) return
        isPromptExecuting = value
        executingListeners.forEach { it(value) }
    }

    fun addExecutingListener(l: (Boolean) -> Unit) { executingListeners.add(l) }

    private fun handlePermissionRequest(json: JsonObject) {
        val id = json.get("id")?.asLong ?: return
        val params = json.getAsJsonObject("params")
        val options = params?.getAsJsonArray("options")

        // Pick first option containing "allow" (any variant), else first
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

        // Acknowledge the request
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

        log.info("Session update [$type] sid=$sid: $update")

        val text = extractTextFromUpdate(update)

        when (type) {
            "agent_message_chunk", "agentMessageChunk" -> {
                if (!text.isNullOrEmpty()) {
                    messageChunkListeners.forEach { it(text, sid) }
                }
            }
            "agent_thought_chunk", "agentThoughtChunk" -> {
                if (!text.isNullOrEmpty()) {
                    thoughtChunkListeners.forEach { it(text, sid) }
                }
            }
            "tool_call", "tool_call_update", "toolCall", "toolCallUpdate" -> {
                handleToolCall(update, sid)
            }
            "usage_update", "usageUpdate", "available_commands_update" -> {
                // silent
            }
            "current_mode_update", "currentModeUpdate" -> {
                val modeId = update.get("currentModeId")?.asString
                    ?: update.get("modeId")?.asString
                if (modeId != null) updateSessionConfig { it.copy(currentModeId = modeId) }
            }
            else -> {
                if (!text.isNullOrEmpty()) {
                    messageChunkListeners.forEach { it("[$type] $text", sid) }
                }
            }
        }
    }

    /**
     * Traite un tool_call ou tool_call_update ACP.
     *
     * Format ACP (reverse-engineered depuis @agentclientprotocol/claude-agent-acp/dist/tools.js,
     * fonctions toolInfoFromToolUse et toolUpdateFromDiffToolResponse) :
     * ```
     * {
     *   "sessionUpdate": "tool_call" | "tool_call_update",
     *   "title": "Write file.txt",
     *   "kind": "edit",
     *   "content": [{"type": "diff", "path": "/abs/path", "oldText": null|"...", "newText": "..."}],
     *   "locations": [{"path": "/abs/path"}]
     * }
     * ```
     *
     * Stratégie : pré-capturer le BEFORE depuis le disque (l'ACP ne nous donne pas le contenu
     * complet du fichier avant modif — `oldText` est null pour Write et juste la portion remplacée
     * pour Edit). Le VFS listener déclenche ensuite le DiffViewer quand le fichier change réellement.
     */
    private fun handleToolCall(update: JsonObject, sid: String? = null) {
        log.info("Tool call structure: $update")

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

        // Extract toutes les paths affectées via content[type:"diff"] et locations[].path
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

        // Notifier les listeners avec l'info enrichie (path/command/kind/status)
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
        if (isGenericOnly) {
            log.info("Tool call generic title skipped: $title")
        } else {
            toolCallListeners.forEach { it(info) }
        }

        // Mémoriser les paths par toolCallId pour pouvoir les refresh même au tool_call_update
        // status=completed qui n'a pas de content/locations.
        if (toolCallId != null && paths.isNotEmpty()) {
            pathsByToolCallId.getOrPut(toolCallId) { mutableSetOf() }.addAll(paths)
        }

        // Tous les paths à refresh (cet update + mémorisés pour ce toolCallId)
        val allPaths = paths.toMutableSet()
        if (toolCallId != null) {
            pathsByToolCallId[toolCallId]?.let { allPaths.addAll(it) }
        }

        if (allPaths.isEmpty()) return

        // Pré-capture BEFORE depuis le disque avant que l'agent n'écrive.
        for (path in allPaths) {
            if (!shouldTrackFile(path)) continue
            if (!toolCallPreCapturedBefore.containsKey(path)) {
                val before = readFileContent(path)
                toolCallPreCapturedBefore[path] = before
                log.info("Pre-captured BEFORE via tool_call for $path: ${before.length} chars")
            }
        }

        // Refresh AGRESSIF avec retries car la limite inotify Linux fait souvent que VFS
        // ne détecte pas le change la 1ère fois. On retry plusieurs fois à intervalles
        // croissants pour s'assurer que VFS finit par voir le change.
        scope.launch {
            val delays = listOf(200L, 500L, 1000L, 2000L)
            for ((i, d) in delays.withIndex()) {
                delay(d)
                ApplicationManager.getApplication().invokeLater {
                    for (path in allPaths) {
                        try {
                            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                            // refresh(async=false, recursive=false) force la détection sync
                            vf?.refresh(false, false)
                        } catch (e: Exception) {
                            log.warn("refresh attempt #${i + 1} failed for $path", e)
                        }
                    }
                }
            }
        }

        // Cleanup mémoire après status=completed (avec délai pour permettre les retries de refresh)
        if (status == "completed" && toolCallId != null) {
            scope.launch {
                delay(5000)
                pathsByToolCallId.remove(toolCallId)
            }
        }
    }

    /** Recursively look for a "text" field in any content/object structure of the update. */
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
                log.info("→ ${message.take(200)}")
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

    // ── Sessions capabilities (model / mode / effort) ────────────────────────

    private fun parseSessionCapabilities(result: JsonObject) {
        var modelsList: List<SelectOption> = emptyList()
        var modesList: List<SelectOption> = emptyList()
        var configOptionsList: List<ConfigOption> = emptyList()
        var currentModel: String? = null
        var currentMode: String? = null
        val currentConfig = mutableMapOf<String, String>()

        // models : peut être sous "models" (SessionModelState) ou "availableModels"
        val modelsState = result.getAsJsonObject("models")
        if (modelsState != null) {
            modelsList = parseSelectOptions(modelsState.getAsJsonArray("availableModels"), idField = "modelId")
            currentModel = modelsState.get("currentModelId")?.asString
        }

        // modes : SessionModeState
        val modesState = result.getAsJsonObject("modes")
        if (modesState != null) {
            modesList = parseSelectOptions(modesState.getAsJsonArray("availableModes"), idField = "id")
            currentMode = modesState.get("currentModeId")?.asString
        }

        // configOptions : array de SessionConfigOption
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

        sessionConfig = SessionConfig(
            models = modelsList,
            modes = modesList,
            configOptions = configOptionsList,
            currentModelId = currentModel,
            currentModeId = currentMode,
            currentConfigValues = currentConfig
        )
        log.info("Parsed capabilities: ${modelsList.size} models, ${modesList.size} modes, ${configOptionsList.size} configs")
        sessionConfigListeners.forEach { it(sessionConfig) }
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

    internal fun updateSessionConfig(transform: (SessionConfig) -> SessionConfig) {
        sessionConfig = transform(sessionConfig)
        sessionConfigListeners.forEach { it(sessionConfig) }
    }

    fun setModel(modelId: String) {
        val sid = sessionId ?: return
        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) notifyError("setModel failed: ${response.get("error")}")
            else updateSessionConfig { it.copy(currentModelId = modelId) }
        }
        val msg = """{"jsonrpc":"2.0","id":$id,"method":"session/set_model","params":""" +
            """{"sessionId":"$sid","modelId":${escapeJson(modelId)}}}"""
        sendRawMessage(msg)
    }

    fun setMode(modeId: String) {
        val sid = sessionId ?: return
        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) notifyError("setMode failed: ${response.get("error")}")
            else updateSessionConfig { it.copy(currentModeId = modeId) }
        }
        val msg = """{"jsonrpc":"2.0","id":$id,"method":"session/set_mode","params":""" +
            """{"sessionId":"$sid","modeId":${escapeJson(modeId)}}}"""
        sendRawMessage(msg)
    }

    fun setConfigOption(optionId: String, value: String) {
        val sid = sessionId ?: return
        val id = nextRequestId.getAndIncrement()
        pendingRequests[id] = { response ->
            if (response.has("error")) notifyError("setConfigOption failed: ${response.get("error")}")
            else updateSessionConfig {
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
    fun addSessionConfigListener(l: (SessionConfig) -> Unit) { sessionConfigListeners.add(l) }

    fun addMessageListener(listener: (JsonObject) -> Unit) { messageListeners.add(listener) }
    fun addStderrListener(listener: (String) -> Unit) { stderrListeners.add(listener) }
    fun addInfoListener(listener: (String) -> Unit) { infoListeners.add(listener) }
    fun addErrorListener(listener: (String) -> Unit) { errorListeners.add(listener) }
    fun addStateListener(listener: (State) -> Unit) { stateListeners.add(listener) }

    fun stopAgent() {
        processHandler?.destroyProcess()
        setState(State.STOPPED)
    }

    fun isRunning(): Boolean = state == State.READY
}
