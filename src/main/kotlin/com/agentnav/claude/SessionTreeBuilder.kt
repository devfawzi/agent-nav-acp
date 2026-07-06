package com.agentnav.claude

import com.google.gson.JsonParser
import java.io.File

/**
 * Construit l'arbre de conversation d'une session claude (IMPROVEMENTS #6) à partir des
 * `uuid`/`parentUuid` du .jsonl. Les branches naissent quand le TUI rewind puis continue :
 * plusieurs events partagent alors le même parent. Pur, testable.
 */
object SessionTreeBuilder {

    data class Node(
        val uuid: String,
        val role: String,          // "user" | "assistant"
        val text: String,          // extrait lisible (wrappers filtrés), peut être vide
        val children: MutableList<Node> = mutableListOf()
    ) {
        val isBranchPoint: Boolean get() = children.size > 1
    }

    data class Tree(val roots: List<Node>, val nodeCount: Int, val branchPoints: Int)

    fun build(file: File, maxTextLen: Int = 100): Tree {
        data class Raw(val uuid: String, val parentUuid: String?, val role: String, val text: String)

        val raws = mutableListOf<Raw>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach line@{ line ->
                if (line.isBlank() || !line.startsWith("{")) return@line
                val obj = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (_: Exception) { return@line }
                val type = obj.get("type")?.asString
                if (type != "user" && type != "assistant") return@line
                val uuid = obj.get("uuid")?.asString ?: return@line
                val parent = obj.get("parentUuid")?.takeIf { !it.isJsonNull }?.asString
                val text = extractText(obj)?.take(maxTextLen) ?: ""
                raws += Raw(uuid, parent, type, text)
            }
        }

        val nodes = raws.associate { it.uuid to Node(it.uuid, it.role, it.text) }
        val roots = mutableListOf<Node>()
        raws.forEach { raw ->
            val node = nodes.getValue(raw.uuid)
            val parent = raw.parentUuid?.let { nodes[it] }
            if (parent != null) parent.children.add(node) else roots.add(node)
        }
        val branchPoints = nodes.values.count { it.isBranchPoint }
        return Tree(roots, nodes.size, branchPoints)
    }

    private val WRAPPERS = listOf(
        "<local-command-caveat>", "<local-command-stdout>", "<local-command-stderr>",
        "<system-reminder>", "<command-name>", "<command-message>", "<command-args>",
        "<task-notification>", "<bash-stdout>", "<bash-stderr>", "<user-prompt-submit-hook>"
    )

    private fun extractText(obj: com.google.gson.JsonObject): String? {
        val message = obj.getAsJsonObject("message") ?: return null
        val content = message.get("content") ?: return null
        val text = when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
                .joinToString(" ") { it.asJsonObject.get("text")?.asString ?: "" }
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (WRAPPERS.any { text.startsWith(it) }) return null
        return text
    }
}
