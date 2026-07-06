package com.agentnav.claude

import com.agentnav.core.ToolCallInfo

/**
 * Mapping pur ToolUse → ToolCallInfo riche (detail lisible par tool, contenu des
 * Write/Edit pour le plan mode, questions AskUserQuestion…). Zéro dépendance IntelliJ,
 * testé contre les fixtures.
 */
object ToolCallMapper {

    fun fromToolUse(e: ClaudeEvent.ToolUse, sessionId: String?, permissionMode: String?): ToolCallInfo {
        val input = e.input
        val toolName = e.name

        // Tools interactifs : la card a besoin du contenu brut (plan / questions).
        val planContent = if (toolName == "ExitPlanMode") input?.get("plan")?.asString else null
        val userQuestionsJson = if (toolName == "AskUserQuestion") {
            input?.getAsJsonArray("questions")?.toString()
        } else null
        if (planContent != null || userQuestionsJson != null) {
            return ToolCallInfo(
                toolCallId = e.id,
                title = toolName,
                kind = "interactive",
                status = "in_progress",
                path = null,
                command = null,
                sessionId = sessionId,
                planContent = planContent,
                userQuestionsJson = userQuestionsJson
            )
        }

        val path = input?.get("file_path")?.asString
            ?: input?.get("path")?.asString
            ?: input?.get("filePath")?.asString
        val command = input?.get("command")?.asString

        val detail = when (toolName) {
            "Grep", "Glob" -> input?.get("pattern")?.asString?.let { pat ->
                val inPath = input.get("path")?.asString
                if (inPath != null) "$pat in $inPath" else pat
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
                    .mapNotNull { runCatching { todos[it].asJsonObject.get("activeForm")?.asString }.getOrNull() }
                    .firstOrNull()
                if (active != null) "${todos.size()} todos — $active" else "${todos.size()} todo(s)"
            }
            "Skill" -> input?.get("skill")?.asString
            "Bash" -> command ?: input?.get("description")?.asString
            "Read", "Edit", "Write", "MultiEdit", "NotebookEdit", "NotebookRead" -> path
            "ToolSearch" -> input?.get("query")?.asString
            "AskUserQuestion" -> "(question)"
            "ExitPlanMode" -> "(plan)"
            "TaskCreate" -> input?.get("subject")?.asString
            "TaskUpdate" -> {
                val tid = input?.get("taskId")?.asString
                val status = input?.get("status")?.asString
                listOfNotNull(tid?.let { "#$it" }, status).joinToString(" → ").ifEmpty { null }
            }
            "TaskList", "TaskGet" -> input?.get("taskId")?.asString?.let { "#$it" }
            else -> {
                if (toolName.startsWith("mcp__")) {
                    input?.entrySet()
                        ?.joinToString(", ", limit = 3, truncated = "…") { "${it.key}=${it.value.toString().take(40)}" }
                } else {
                    // Fallback générique : 1er champ primitif de l'input
                    input?.entrySet()
                        ?.firstOrNull { it.value.isJsonPrimitive }
                        ?.let { "${it.key}=${it.value.toString().take(60)}" }
                }
            }
        }

        val kind = when (toolName) {
            "Write", "Edit", "MultiEdit" -> "edit"
            "Bash" -> "execute"
            "Read" -> "read"
            else -> null
        }

        return ToolCallInfo(
            toolCallId = e.id,
            title = toolName,
            kind = kind,
            status = "in_progress",
            path = path,
            command = command,
            sessionId = sessionId,
            writeContent = input?.get("content")?.asString,
            editOldString = input?.get("old_string")?.asString,
            editNewString = input?.get("new_string")?.asString,
            permissionMode = permissionMode,
            detail = detail
        )
    }
}
