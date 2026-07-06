package com.agentnav.claude

import com.agentnav.core.McpServerInfo
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Événements typés du flux stream-json de Claude Code (2.1+).
 *
 * Zéro dépendance IntelliJ : parseable et testable en JVM pure contre les fixtures
 * capturées sur le vrai CLI (src/test/resources/fixtures/claude/<version>/).
 */
sealed class ClaudeEvent {

    /** system:init — arrive après le 1er user message. Source de vérité de la session. */
    data class Init(
        val sessionId: String?,
        val model: String?,
        val permissionMode: String?,
        val slashCommands: List<String>,
        val mcpServers: List<McpServerInfo>,
        val mcpTools: List<String>,
        val skills: List<String>,
        val agents: List<String>,
        val memoryPaths: Map<String, String>
    ) : ClaudeEvent()

    /** system:status — émis après un set_permission_mode réussi. */
    data class Status(val permissionMode: String?) : ClaudeEvent()

    /** system:hook_started / system:hook_response (hooks user SessionStart, etc.). */
    data class Hook(val subtype: String, val hookName: String?) : ClaudeEvent()

    /** Autre subtype system non géré spécifiquement (inoffensif). */
    data class SystemOther(val subtype: String?) : ClaudeEvent()

    /** Bloc texte assistant. isSynthetic=true pour les sorties de slash commands builtin
     *  (message.model == "<synthetic>", coût 0) — à rendre comme sortie système, pas comme
     *  bulle assistant. */
    data class AssistantText(val text: String, val isSynthetic: Boolean) : ClaudeEvent()

    data class AssistantThinking(val text: String) : ClaudeEvent()

    data class ToolUse(val id: String?, val name: String, val input: JsonObject?) : ClaudeEvent()

    /** tool_result renvoyé dans un event user. errorText != null seulement si isError. */
    data class ToolResult(val toolUseId: String, val isError: Boolean, val errorText: String?) : ClaudeEvent()

    /** Fin de turn (event result) : usage cumulable + erreur éventuelle. */
    data class TurnResult(
        val isError: Boolean,
        val errorMessage: String?,
        val totalCostUsd: Double,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long,
        val cacheCreationTokens: Long
    ) : ClaudeEvent()

    /** Réponse à un de NOS control_request (set_model, set_permission_mode, interrupt). */
    data class ControlResponse(val requestId: String?, val success: Boolean, val error: String?) : ClaudeEvent()

    /**
     * Demande de permission. Formats unifiés :
     *  - claude 2.1+ : control_request subtype="can_use_tool", request_id AU TOP-LEVEL,
     *    input dans request.input, permission_suggestions pour "Allow always" (legacy=false)
     *  - legacy SDK : sdk_control_request request.subtype="permission", request_id DANS
     *    request, input dans request.tool_input (legacy=true)
     */
    data class CanUseTool(
        val requestId: String,
        val toolName: String,
        val input: JsonElement?,
        val blockedPath: String?,
        val permissionSuggestionsJson: String?,
        val legacy: Boolean
    ) : ClaudeEvent()

    /** control_request d'un subtype inconnu — le backend répond allow_once permissif. */
    data class UnknownControlRequest(val requestId: String?, val subtype: String?) : ClaudeEvent()

    object RateLimit : ClaudeEvent()
    object StreamEvent : ClaudeEvent()

    /** Type d'event inconnu — log + rendu dégradé, jamais de crash ni de silence. */
    data class Unknown(val type: String?, val raw: String) : ClaudeEvent()

    /** Ligne illisible (JSON invalide ou extraction impossible). */
    data class ParseError(val message: String, val raw: String) : ClaudeEvent()
}

object ClaudeStreamParser {

    /**
     * Une ligne NDJSON → 0..n événements typés (un event assistant peut contenir plusieurs
     * blocks text/thinking/tool_use). Ne lève JAMAIS d'exception : ParseError en dernier recours.
     */
    fun parse(line: String): List<ClaudeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("{")) {
            return listOf(ClaudeEvent.Unknown(null, trimmed.take(300)))
        }
        val json = try {
            JsonParser.parseString(trimmed).asJsonObject
        } catch (e: Exception) {
            return listOf(ClaudeEvent.ParseError(e.message ?: "invalid JSON", trimmed.take(300)))
        }
        return try {
            dispatch(json)
        } catch (e: Exception) {
            listOf(ClaudeEvent.ParseError("extract failed: ${e.message}", trimmed.take(300)))
        }
    }

    private fun dispatch(json: JsonObject): List<ClaudeEvent> =
        when (val type = json.get("type")?.asString) {
            "system" -> listOf(parseSystem(json))
            "assistant" -> parseAssistant(json)
            "user" -> parseUser(json)
            "result" -> listOf(parseResult(json))
            "control_request" -> listOf(parseControlRequest(json))
            "sdk_control_request" -> listOf(parseSdkControlRequest(json))
            "control_response" -> listOf(parseControlResponse(json))
            "rate_limit_event" -> listOf(ClaudeEvent.RateLimit)
            "stream_event" -> listOf(ClaudeEvent.StreamEvent)
            else -> listOf(ClaudeEvent.Unknown(type, json.toString().take(300)))
        }

    // ─── system ──────────────────────────────────────────────────────────────

    private fun parseSystem(json: JsonObject): ClaudeEvent {
        return when (val subtype = json.get("subtype")?.asString) {
            "init" -> ClaudeEvent.Init(
                sessionId = json.get("session_id")?.asString,
                model = json.get("model")?.asString,
                permissionMode = json.get("permissionMode")?.asString,
                slashCommands = json.getAsJsonArray("slash_commands")
                    ?.mapNotNull { it.asString }.orEmpty(),
                mcpServers = json.getAsJsonArray("mcp_servers")?.mapNotNull { el ->
                    if (!el.isJsonObject) null else {
                        val o = el.asJsonObject
                        val name = o.get("name")?.asString ?: return@mapNotNull null
                        McpServerInfo(name, o.get("status")?.asString ?: "unknown")
                    }
                }.orEmpty(),
                mcpTools = json.getAsJsonArray("tools")?.mapNotNull { it.asString }
                    ?.filter { it.startsWith("mcp__") }.orEmpty(),
                skills = json.getAsJsonArray("skills")?.mapNotNull { it.asString }.orEmpty(),
                agents = json.getAsJsonArray("agents")?.mapNotNull { it.asString }.orEmpty(),
                memoryPaths = json.getAsJsonObject("memory_paths")?.entrySet()
                    ?.associate { (k, v) -> k to (runCatching { v.asString }.getOrNull() ?: "") }
                    .orEmpty()
            )
            "status" -> ClaudeEvent.Status(json.get("permissionMode")?.asString)
            "hook_started", "hook_response" ->
                ClaudeEvent.Hook(subtype, json.get("hook_name")?.asString)
            else -> ClaudeEvent.SystemOther(subtype)
        }
    }

    // ─── assistant / user ────────────────────────────────────────────────────

    private fun parseAssistant(json: JsonObject): List<ClaudeEvent> {
        val message = json.getAsJsonObject("message") ?: return emptyList()
        val isSynthetic = message.get("model")?.asString == "<synthetic>"
        val content = message.getAsJsonArray("content") ?: return emptyList()
        val events = mutableListOf<ClaudeEvent>()
        content.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val block = item.asJsonObject
            when (block.get("type")?.asString) {
                "text" -> block.get("text")?.asString?.takeIf { it.isNotEmpty() }
                    ?.let { events += ClaudeEvent.AssistantText(it, isSynthetic) }
                "thinking" -> (block.get("thinking")?.asString ?: block.get("text")?.asString)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { events += ClaudeEvent.AssistantThinking(it) }
                "tool_use" -> block.get("name")?.asString?.let { name ->
                    events += ClaudeEvent.ToolUse(
                        id = block.get("id")?.asString,
                        name = name,
                        input = block.getAsJsonObject("input")
                    )
                }
            }
        }
        return events
    }

    private fun parseUser(json: JsonObject): List<ClaudeEvent> {
        val content = json.getAsJsonObject("message")?.getAsJsonArray("content")
            ?: return emptyList()
        val events = mutableListOf<ClaudeEvent>()
        content.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val block = item.asJsonObject
            if (block.get("type")?.asString != "tool_result") return@forEach
            val toolUseId = block.get("tool_use_id")?.asString ?: return@forEach
            val isError = block.get("is_error")?.asBoolean == true
            val errorText = if (!isError) null else block.get("content")?.let { c ->
                when {
                    c.isJsonPrimitive -> c.asString
                    c.isJsonArray -> c.asJsonArray.joinToString("\n") { el ->
                        runCatching { el.asJsonObject.get("text")?.asString }.getOrNull() ?: ""
                    }
                    else -> null
                }
            }?.takeIf { it.isNotBlank() }
            events += ClaudeEvent.ToolResult(toolUseId, isError, errorText)
        }
        return events
    }

    // ─── result ──────────────────────────────────────────────────────────────

    private fun parseResult(json: JsonObject): ClaudeEvent {
        val usage = json.getAsJsonObject("usage")
        val isError = json.get("is_error")?.asBoolean ?: false
        val errorMessage = if (!isError) null else {
            val errorsArr = json.getAsJsonArray("errors")
            if (errorsArr != null && errorsArr.size() > 0) {
                errorsArr.mapNotNull { runCatching { it.asString }.getOrNull() }.joinToString("; ")
            } else {
                json.get("result")?.asString ?: "unknown error"
            }
        }
        return ClaudeEvent.TurnResult(
            isError = isError,
            errorMessage = errorMessage,
            totalCostUsd = json.get("total_cost_usd")
                ?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0,
            inputTokens = usage?.get("input_tokens")?.asLong ?: 0L,
            outputTokens = usage?.get("output_tokens")?.asLong ?: 0L,
            cacheReadTokens = usage?.get("cache_read_input_tokens")?.asLong ?: 0L,
            cacheCreationTokens = usage?.get("cache_creation_input_tokens")?.asLong ?: 0L
        )
    }

    // ─── control ─────────────────────────────────────────────────────────────

    private fun parseControlRequest(json: JsonObject): ClaudeEvent {
        val request = json.getAsJsonObject("request")
        return when (request?.get("subtype")?.asString) {
            "can_use_tool" -> {
                // request_id AU TOP-LEVEL, input s'appelle "input" (claude 2.1+).
                val requestId = json.get("request_id")?.asString
                    ?: return ClaudeEvent.UnknownControlRequest(null, "can_use_tool")
                ClaudeEvent.CanUseTool(
                    requestId = requestId,
                    toolName = request.get("tool_name")?.asString ?: "tool",
                    input = request.get("input"),
                    blockedPath = request.get("blocked_path")?.asString,
                    permissionSuggestionsJson = request.get("permission_suggestions")?.toString(),
                    legacy = false
                )
            }
            "permission" -> parsePermissionLegacy(json)
            else -> ClaudeEvent.UnknownControlRequest(
                json.get("request_id")?.asString, request?.get("subtype")?.asString
            )
        }
    }

    private fun parseSdkControlRequest(json: JsonObject): ClaudeEvent {
        val request = json.getAsJsonObject("request")
            ?: return ClaudeEvent.UnknownControlRequest(null, null)
        if (request.get("subtype")?.asString != "permission") {
            return ClaudeEvent.UnknownControlRequest(
                request.get("request_id")?.asString, request.get("subtype")?.asString
            )
        }
        return parsePermissionLegacy(json)
    }

    /** Format legacy : request_id DANS request, input = request.tool_input. */
    private fun parsePermissionLegacy(json: JsonObject): ClaudeEvent {
        val request = json.getAsJsonObject("request")
            ?: return ClaudeEvent.UnknownControlRequest(null, "permission")
        val requestId = request.get("request_id")?.asString
            ?: return ClaudeEvent.UnknownControlRequest(null, "permission")
        return ClaudeEvent.CanUseTool(
            requestId = requestId,
            toolName = request.get("tool_name")?.asString ?: "tool",
            input = request.get("tool_input"),
            blockedPath = null,
            permissionSuggestionsJson = null,
            legacy = true
        )
    }

    private fun parseControlResponse(json: JsonObject): ClaudeEvent {
        val response = json.getAsJsonObject("response")
        val subtype = response?.get("subtype")?.asString
        val isError = subtype == "error" || (response != null && response.get("error") != null)
        return ClaudeEvent.ControlResponse(
            // request_id observé au top-level (2.1.123) mais aussi dans response selon les
            // versions — accepter les deux, les fixtures tranchent.
            requestId = json.get("request_id")?.asString
                ?: response?.get("request_id")?.asString,
            success = !isError,
            error = if (isError) {
                response?.get("error")?.asString ?: response?.toString() ?: "unknown error"
            } else null
        )
    }
}
