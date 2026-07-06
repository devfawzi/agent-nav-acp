package com.agentnav.acp

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend ACP isolé : 1 instance = 1 session sur le process partagé du hub.
 * Le hub route directement les session/update et session/request_permission de NOTRE
 * sessionId — aucun listener global, aucun filtre.
 */
class AcpSessionBackend(
    private val project: Project,
    val profile: AgentProfile
) : AgentBackend {

    private val log = thisLogger()
    private val pluginLog get() = PluginLogService.getInstance(project)
    private val hub get() = AcpProcessHub.getInstance(project)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var _sessionId: String? = null
    override val sessionId: String? get() = _sessionId

    @Volatile private var _state: AgentState = AgentState.STOPPED
    override val state: AgentState get() = _state

    @Volatile private var _config: SessionConfig = SessionConfig()
    override val config: SessionConfig get() = _config

    @Volatile private var _usage: UsageStats = UsageStats()
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

    @Volatile private var executing = false
    private val toolCallPreCapturedBefore = ConcurrentHashMap<String, String>()
    private val pathsByToolCallId = ConcurrentHashMap<String, MutableSet<String>>()

    // ─── Cycle de vie ────────────────────────────────────────────────────────

    override fun start() {
        if (_state != AgentState.STOPPED && _state != AgentState.ERROR) return
        setState(AgentState.STARTING)
        setState(AgentState.INITIALIZING)
        hub.openSession(this)
    }

    override fun stop() {
        hub.closeSession(this)
        scope.cancel()
        setState(AgentState.STOPPED)
    }

    // ─── Callbacks internes appelés par le hub ───────────────────────────────

    internal fun handleSessionCreated(sid: String, result: JsonObject) {
        _sessionId = sid
        parseSessionCapabilities(result)
        setState(AgentState.READY)
        pluginLog.info("acp", "🟢 ACP session ready sid=$sid profile=${profile.id}")
        onSessionReady?.invoke(sid)
    }

    internal fun handleProcessDied(exitCode: Int, message: String?) {
        setExecuting(false)
        setState(AgentState.ERROR)
        onError?.invoke(message ?: "${profile.displayName} process died (exit $exitCode)")
    }

    internal fun dispatchStderr(text: String) {
        onStderr?.invoke(text)
    }

    internal fun dispatchUpdate(params: JsonObject) {
        val update = params.getAsJsonObject("update") ?: return
        val type = update.get("sessionUpdate")?.asString ?: return
        val text = extractTextFromUpdate(update)
        when (type) {
            "agent_message_chunk", "agentMessageChunk" ->
                if (!text.isNullOrEmpty()) onTextChunk?.invoke(text)
            "agent_thought_chunk", "agentThoughtChunk" ->
                if (!text.isNullOrEmpty()) onThoughtChunk?.invoke(text)
            "tool_call", "tool_call_update", "toolCall", "toolCallUpdate" ->
                handleToolCall(update)
            "usage_update", "usageUpdate", "available_commands_update" -> {}
            "current_mode_update", "currentModeUpdate" -> {
                val modeId = update.get("currentModeId")?.asString ?: update.get("modeId")?.asString
                if (modeId != null) updateConfig { it.copy(currentModeId = modeId) }
            }
            else -> if (!text.isNullOrEmpty()) onTextChunk?.invoke("[$type] $text")
        }
    }

    /**
     * Permission ACP → card Allow/Deny (le legacy auto-acceptait — corrigé).
     * Réponse : outcome selected(optionId) ou cancelled. Timeout 120s → deny.
     */
    internal fun dispatchPermission(rpcId: Long, params: JsonObject) {
        val options = params.getAsJsonArray("options")
        fun optionId(vararg kinds: String): String? = options?.firstOrNull {
            val obj = it.asJsonObject
            val kind = (obj.get("kind")?.asString ?: "").lowercase()
            val name = (obj.get("name")?.asString ?: "").lowercase()
            kinds.any { k -> k in kind || k in name }
        }?.asJsonObject?.get("optionId")?.asString

        val allowId = optionId("allow_once", "allow") ?: options?.firstOrNull()
            ?.asJsonObject?.get("optionId")?.asString ?: "allow"
        val allowAlwaysId = optionId("allow_always", "always")
        val rejectId = optionId("reject", "deny")

        val toolCall = params.getAsJsonObject("toolCall")
        val toolName = toolCall?.get("title")?.asString ?: toolCall?.get("kind")?.asString ?: "tool"
        val toolInput = toolCall?.getAsJsonObject("rawInput")?.toString()

        val answered = AtomicBoolean(false)
        fun respondOnce(json: String) {
            if (answered.compareAndSet(false, true)) hub.respond(profile.id, rpcId, json)
        }

        val request = PermissionRequest(
            requestId = rpcId.toString(),
            toolName = toolName,
            toolInput = toolInput,
            sessionId = _sessionId,
            respondAllow = {
                respondOnce("""{"outcome":{"outcome":"selected","optionId":"$allowId"}}""")
            },
            respondDeny = { _ ->
                respondOnce(
                    if (rejectId != null) """{"outcome":{"outcome":"selected","optionId":"$rejectId"}}"""
                    else """{"outcome":{"outcome":"cancelled"}}"""
                )
            },
            respondAllowAlways = allowAlwaysId?.let { id ->
                { respondOnce("""{"outcome":{"outcome":"selected","optionId":"$id"}}""") }
            }
        )
        // Timeout : ne pas bloquer l'agent indéfiniment si l'user ne répond pas.
        scope.launch {
            delay(120_000)
            if (!answered.get()) {
                log.warn("ACP permission timeout (120s) → deny")
                request.respondDeny("timeout")
            }
        }
        onPermission?.invoke(request) ?: run {
            // Pas de card câblée → deny prudent.
            log.warn("No onPermission handler wired → deny")
            request.respondDeny("no handler")
        }
    }

    // ─── Actions AgentBackend ────────────────────────────────────────────────

    override fun sendPrompt(text: String, attachments: List<PromptAttachment>) {
        val sid = _sessionId ?: run { onError?.invoke("No active ACP session"); return }
        project.getService(PromptHistoryService::class.java).startPrompt(text, sid)
        toolCallPreCapturedBefore.clear()
        setExecuting(true)

        val parts = mutableListOf("""{"type":"text","text":${AcpJson.escape(text)}}""")
        for (att in attachments) {
            when (att) {
                is PromptAttachment.FileLink -> {
                    val mime = att.mimeType?.let { ""","mimeType":${AcpJson.escape(it)}""" } ?: ""
                    parts.add("""{"type":"resource_link","uri":${AcpJson.escape("file://" + att.absolutePath)},"name":${AcpJson.escape(att.displayName)}$mime}""")
                }
                is PromptAttachment.Image ->
                    parts.add("""{"type":"image","data":${AcpJson.escape(att.base64Data)},"mimeType":${AcpJson.escape(att.mimeType)}}""")
                is PromptAttachment.CodeRef -> {
                    val block = "\n[Code reference from ${att.absolutePath}:${att.lineRange}]\n```${att.language.orEmpty()}\n${att.content}\n```\n"
                    parts.add("""{"type":"text","text":${AcpJson.escape(block)}}""")
                }
            }
        }
        val promptArray = parts.joinToString(",", prefix = "[", postfix = "]")
        hub.request(profile.id, "session/prompt", """{"sessionId":"$sid","prompt":$promptArray}""") { response ->
            if (response.has("error")) onError?.invoke("session/prompt failed: ${response.get("error")}")
            setExecuting(false)
        }
    }

    override fun cancel() {
        val sid = _sessionId ?: return
        hub.notify(profile.id, "session/cancel", """{"sessionId":"$sid"}""")
    }

    override fun replyToolResult(toolUseId: String, content: String) {
        // Pas d'équivalent ACP (ExitPlanMode/AskUserQuestion sont des concepts claude CLI).
        log.info("replyToolResult ignored on ACP transport")
    }

    override fun setMode(modeId: String) {
        val sid = _sessionId ?: return
        hub.request(profile.id, "session/set_mode",
            """{"sessionId":"$sid","modeId":${AcpJson.escape(modeId)}}""") { response ->
            if (response.has("error")) onError?.invoke("setMode failed: ${response.get("error")}")
            else updateConfig { it.copy(currentModeId = modeId) }
        }
    }

    override fun setModel(modelId: String) {
        val sid = _sessionId ?: return
        hub.request(profile.id, "session/set_model",
            """{"sessionId":"$sid","modelId":${AcpJson.escape(modelId)}}""") { response ->
            if (response.has("error")) onError?.invoke("setModel failed: ${response.get("error")}")
            else updateConfig { it.copy(currentModelId = modelId) }
        }
    }

    override fun setEffort(level: String) {
        val sid = _sessionId ?: return
        hub.request(profile.id, "session/set_config_option",
            """{"sessionId":"$sid","id":"thinking","value":${AcpJson.escape(level)}}""") { response ->
            if (response.has("error")) onError?.invoke("setEffort failed: ${response.get("error")}")
            else updateConfig { it.copy(currentConfigValues = it.currentConfigValues + ("thinking" to level)) }
        }
    }

    // ─── Tool calls + suivi fichiers (repris du legacy handleToolCall) ──────

    private fun handleToolCall(update: JsonObject) {
        val toolCallId = update.get("toolCallId")?.asString
        val status = update.get("status")?.asString
        val title = update.get("title")?.asString ?: update.get("kind")?.asString ?: "tool"
        val kind = update.get("kind")?.asString
            ?: update.getAsJsonObject("toolCall")?.get("kind")?.asString
        val rawInput = update.getAsJsonObject("rawInput")
        val pathFromInput = rawInput?.get("file_path")?.asString
            ?: rawInput?.get("path")?.asString ?: rawInput?.get("filePath")?.asString
        val command = rawInput?.get("command")?.asString

        val paths = mutableSetOf<String>()
        update.getAsJsonArray("content")?.forEach { item ->
            if (item.isJsonObject && item.asJsonObject.get("type")?.asString == "diff")
                item.asJsonObject.get("path")?.asString?.let { paths.add(it) }
        }
        update.getAsJsonArray("locations")?.forEach { item ->
            if (item.isJsonObject) item.asJsonObject.get("path")?.asString?.let { paths.add(it) }
        }

        val detail = when (title) {
            "Grep", "Glob" -> rawInput?.get("pattern")?.asString
            "WebFetch" -> rawInput?.get("url")?.asString
            "WebSearch" -> rawInput?.get("query")?.asString
            "Task" -> rawInput?.get("description")?.asString
                ?: rawInput?.get("subagent_type")?.asString
                ?: rawInput?.get("prompt")?.asString?.take(80)
            "TodoWrite" -> rawInput?.getAsJsonArray("todos")?.let { "${it.size()} item(s)" }
            "Skill" -> rawInput?.get("skill")?.asString
            "ToolSearch" -> rawInput?.get("query")?.asString
            "AskUserQuestion" -> "(question)"
            "ExitPlanMode" -> "(plan)"
            else -> if (title.startsWith("mcp__"))
                rawInput?.entrySet()?.joinToString(", ", limit = 3, truncated = "…") { "${it.key}=${it.value}" }
            else null
        }

        val info = ToolCallInfo(
            toolCallId = toolCallId, title = title, kind = kind, status = status,
            path = pathFromInput ?: paths.firstOrNull(), command = command,
            sessionId = _sessionId, detail = detail
        )
        val genericTitles = setOf("tool", "edit", "write", "read", "bash", "find", "grep")
        val isGenericOnly = title.lowercase() in genericTitles &&
            info.path == null && info.command == null && info.detail == null && status != "completed"
        if (!isGenericOnly) onToolCall?.invoke(info)

        if (toolCallId != null && paths.isNotEmpty())
            pathsByToolCallId.getOrPut(toolCallId) { mutableSetOf() }.addAll(paths)

        val allPaths = paths.toMutableSet()
        if (toolCallId != null) pathsByToolCallId[toolCallId]?.let { allPaths.addAll(it) }
        if (allPaths.isEmpty()) return

        for (path in allPaths) {
            if (!shouldTrackFile(path)) continue
            toolCallPreCapturedBefore.computeIfAbsent(path) { readFileContent(it) }
        }

        if (status == "completed") {
            scheduleRefreshAndFallback(allPaths.toSet())
            if (toolCallId != null) scope.launch { delay(5000); pathsByToolCallId.remove(toolCallId) }
        }
    }

    /** Retries VFS refresh + fallback addOrUpdate manuel (repris du legacy). */
    private fun scheduleRefreshAndFallback(allPaths: Set<String>) {
        scope.launch {
            for ((i, d) in listOf(200L, 800L).withIndex()) {
                delay(d)
                ApplicationManager.getApplication().invokeLater {
                    for (path in allPaths) {
                        try {
                            LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                                ?.refresh(false, false)
                        } catch (e: Exception) {
                            log.warn("refresh #${i + 1} failed for $path", e)
                        }
                    }
                }
            }
            delay(400)
            ApplicationManager.getApplication().invokeLater {
                val pending = project.getService(PendingChangesService::class.java)
                val history = project.getService(PromptHistoryService::class.java)
                for (path in allPaths) {
                    if (!shouldTrackFile(path)) continue
                    val before = toolCallPreCapturedBefore.remove(path) ?: continue
                    try {
                        val after = File(path).readText(Charsets.UTF_8)
                        if (before == after) continue
                        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: continue
                        history.captureFileBefore(path, before)
                        history.captureFileAfter(path, after)
                        pending.addOrUpdate(path, before, after, vf, _sessionId)
                        project.getService(DiffViewerManager::class.java).scheduleRefresh()
                    } catch (e: Exception) {
                        log.warn("Fallback addOrUpdate failed for $path", e)
                    }
                }
            }
        }
    }

    // ─── Helpers (repris du legacy) ──────────────────────────────────────────

    private fun shouldTrackFile(path: String): Boolean {
        val basePath = project.basePath ?: return false
        if (!path.startsWith(basePath)) return false
        val ignored = listOf(
            "/build/", "/.gradle/", "/.idea/", "/.git/", "/node_modules/",
            "/.intellijPlatform/", "/target/", "/out/", "/dist/", "/.next/",
            "/__pycache__/", "/venv/", "/.venv/"
        )
        return ignored.none { path.contains(it) }
    }

    private fun readFileContent(filepath: String): String = try {
        val file = LocalFileSystem.getInstance().findFileByPath(filepath)
        if (file != null && file.exists()) String(file.contentsToByteArray())
        else File(filepath).takeIf { it.exists() }?.readText() ?: ""
    } catch (e: Exception) {
        log.warn("Could not read file: $filepath", e); ""
    }

    private fun extractTextFromUpdate(update: JsonObject): String? {
        update.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { return it }
        val content = update.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonObject -> content.asJsonObject.get("text")?.asString
            content.isJsonArray -> buildString {
                content.asJsonArray.forEach { item ->
                    if (item.isJsonObject) item.asJsonObject.get("text")?.asString?.let { append(it) }
                    else if (item.isJsonPrimitive) append(item.asString)
                }
            }.ifEmpty { null }
            else -> null
        }
    }

    private fun parseSessionCapabilities(result: JsonObject) {
        var models: List<SelectOption> = emptyList()
        var modes: List<SelectOption> = emptyList()
        var configOptions: List<ConfigOption> = emptyList()
        var currentModel: String? = null
        var currentMode: String? = null
        val currentConfig = mutableMapOf<String, String>()

        result.getAsJsonObject("models")?.let { m ->
            models = parseSelectOptions(m.getAsJsonArray("availableModels"), "modelId")
            currentModel = m.get("currentModelId")?.asString
        }
        result.getAsJsonObject("modes")?.let { m ->
            modes = parseSelectOptions(m.getAsJsonArray("availableModes"), "id")
            currentMode = m.get("currentModeId")?.asString
        }
        result.getAsJsonArray("configOptions")?.let { arr ->
            configOptions = arr.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull null
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: id
                val type = obj.get("type")?.asString ?: "select"
                val payload = obj.getAsJsonObject("payload") ?: obj
                val opts = parseSelectOptions(payload.getAsJsonArray("options"), "id")
                val current = payload.get("currentValue")?.asString
                if (current != null) currentConfig[id] = current
                ConfigOption(id, name, type, opts, current)
            }
        }
        _config = SessionConfig(
            models = models, modes = modes, configOptions = configOptions,
            currentModelId = currentModel, currentModeId = currentMode,
            currentConfigValues = currentConfig
        )
        onConfigChange?.invoke(_config)
    }

    private fun parseSelectOptions(arr: com.google.gson.JsonArray?, idField: String): List<SelectOption> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val id = obj.get(idField)?.asString ?: obj.get("id")?.asString ?: return@mapNotNull null
            val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: id
            SelectOption(id, name, obj.get("description")?.asString)
        }
    }

    private fun updateConfig(transform: (SessionConfig) -> SessionConfig) {
        _config = transform(_config)
        onConfigChange?.invoke(_config)
    }

    private fun setExecuting(value: Boolean) {
        if (executing == value) return
        executing = value
        onExecuting?.invoke(value)
        if (!value) project.getService(PromptHistoryService::class.java).endPrompt()
    }

    private fun setState(newState: AgentState) {
        val previous = _state
        _state = newState
        pluginLog.info("acp", "state $previous → $newState (sid=$_sessionId)")
        onStateChange?.invoke(newState)
    }
}
