package com.agentnav.claude

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Charge l'historique d'une session claude (.jsonl) pour le REPLAY dans le chat au resume :
 * uniquement les échanges user/assistant lisibles (wrappers système, thinking, tool calls
 * et sorties synthétiques filtrés). Pur, testable.
 */
object SessionHistoryLoader {

    sealed class Item {
        data class User(val text: String) : Item()
        data class Assistant(val text: String) : Item()
    }

    private val SYSTEM_WRAPPER_PREFIXES = listOf(
        "<local-command-caveat>", "<local-command-stdout>", "<local-command-stderr>",
        "<system-reminder>", "<command-name>", "<command-message>", "<command-args>",
        "<task-notification>", "<bash-stdout>", "<bash-stderr>", "<user-prompt-submit-hook>"
    )

    /** Localise le .jsonl d'une session (cwd encodé : `/` → `-`). */
    fun sessionFile(cwd: String?, sessionId: String): File? {
        val home = System.getProperty("user.home") ?: return null
        // Chemin direct si le cwd est connu, sinon scan de tous les projets.
        if (cwd != null) {
            val direct = File("$home/.claude/projects/${cwd.replace("/", "-")}/$sessionId.jsonl")
            if (direct.isFile) return direct
        }
        val root = File("$home/.claude/projects")
        return root.listFiles { f -> f.isDirectory }
            ?.map { File(it, "$sessionId.jsonl") }
            ?.firstOrNull { it.isFile }
    }

    /**
     * Parse le .jsonl → items User/Assistant dans l'ordre, limité aux [maxItems] DERNIERS
     * (les vieilles conversations peuvent avoir des milliers d'events).
     */
    fun load(file: File, maxItems: Int = 200): List<Item> {
        val items = ArrayDeque<Item>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach line@{ line ->
                if (line.isBlank() || !line.startsWith("{")) return@line
                val obj = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (_: Exception) { return@line }
                val item = when (obj.get("type")?.asString) {
                    "user" -> extractUserText(obj)
                        ?.takeUnless { isSystemWrapperOnly(it) }
                        ?.let { Item.User(it) }
                    "assistant" -> extractAssistantText(obj)?.let { Item.Assistant(it) }
                    else -> null
                } ?: return@line
                items.addLast(item)
                if (items.size > maxItems) items.removeFirst()
            }
        }
        return items.toList()
    }

    private fun isSystemWrapperOnly(text: String): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return true
        return SYSTEM_WRAPPER_PREFIXES.any { trimmed.startsWith(it) }
    }

    /** content string OU array de blocks text (skip tool_result). */
    private fun extractUserText(obj: JsonObject): String? {
        val content = obj.getAsJsonObject("message")?.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
                .joinToString("\n") { it.asJsonObject.get("text")?.asString ?: "" }
                .ifBlank { null }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    /** Blocks text de l'assistant (skip thinking/tool_use et messages synthétiques). */
    private fun extractAssistantText(obj: JsonObject): String? {
        val message = obj.getAsJsonObject("message") ?: return null
        if (message.get("model")?.asString == "<synthetic>") return null
        val content = message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return content
            .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
            .joinToString("\n") { it.asJsonObject.get("text")?.asString ?: "" }
            .takeIf { it.isNotBlank() }
    }
}
