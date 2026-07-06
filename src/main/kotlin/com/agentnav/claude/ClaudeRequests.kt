package com.agentnav.claude

import com.agentnav.core.PromptAttachment
import com.google.gson.JsonElement

/**
 * Builders des messages ENVOYÉS sur stdin de claude (stream-json bidirectionnel).
 * Un seul endroit où les schémas wire vivent — golden-testés (ClaudeRequestsTest).
 */
object ClaudeRequests {

    /** Message user avec pièces jointes (images base64, @file mentions, code refs). */
    fun userMessage(text: String, attachments: List<PromptAttachment> = emptyList()): String {
        val contentArr = mutableListOf<String>()
        contentArr.add("""{"type":"text","text":${escape(text)}}""")
        for (att in attachments) {
            when (att) {
                is PromptAttachment.Image -> contentArr.add(
                    """{"type":"image","source":{"type":"base64","media_type":${escape(att.mimeType)},"data":${escape(att.base64Data)}}}"""
                )
                is PromptAttachment.FileLink -> contentArr.add(
                    """{"type":"text","text":${escape(" @${att.absolutePath}")}}"""
                )
                is PromptAttachment.CodeRef -> {
                    val block = buildString {
                        append("\n[Code reference from ")
                        append(att.absolutePath); append(":"); append(att.lineRange)
                        append("]\n```"); append(att.language.orEmpty()); append("\n")
                        append(att.content); append("\n```\n")
                    }
                    contentArr.add("""{"type":"text","text":${escape(block)}}""")
                }
            }
        }
        val contentJson = contentArr.joinToString(",", prefix = "[", postfix = "]")
        return """{"type":"user","message":{"role":"user","content":$contentJson}}"""
    }

    /** Réponse à un tool interactif (ExitPlanMode approve, AskUserQuestion submit). */
    fun toolResult(toolUseId: String, content: String): String =
        """{"type":"user","message":{"role":"user","content":[""" +
            """{"type":"tool_result","tool_use_id":${escape(toolUseId)},""" +
            """"content":${escape(content)}}]}}"""

    /** Interrompt le turn sans tuer le process (équivalent Esc du TUI). */
    fun interrupt(requestId: String): String =
        """{"type":"control_request","request_id":${escape(requestId)},"request":{"subtype":"interrupt"}}"""

    fun setModel(requestId: String, modelId: String): String =
        """{"type":"control_request","request_id":${escape(requestId)},""" +
            """"request":{"subtype":"set_model","model":${escape(modelId)}}}"""

    fun setPermissionMode(requestId: String, modeId: String): String =
        """{"type":"control_request","request_id":${escape(requestId)},""" +
            """"request":{"subtype":"set_permission_mode","mode":${escape(modeId)}}}"""

    /**
     * Allow d'une permission can_use_tool. `updatedInput` OBLIGATOIRE (Zod claude 2.1+) —
     * on renvoie l'input original tel quel. `permissionSuggestionsJson` non-null = "Allow
     * always" : claude mémorise les règles suggérées (scope session/localSettings).
     */
    fun permissionAllow(
        requestId: String,
        originalInput: JsonElement?,
        permissionSuggestionsJson: String? = null
    ): String {
        val updatedInput = originalInput?.takeIf { it.isJsonObject }?.toString() ?: "{}"
        val permsClause = if (permissionSuggestionsJson != null) {
            ""","updatedPermissions":$permissionSuggestionsJson"""
        } else ""
        return """{"type":"control_response","response":{"subtype":"success",""" +
            """"request_id":${escape(requestId)},"response":""" +
            """{"behavior":"allow","updatedInput":$updatedInput$permsClause}}}"""
    }

    fun permissionDeny(requestId: String, message: String?): String =
        """{"type":"control_response","response":{"subtype":"success",""" +
            """"request_id":${escape(requestId)},"response":""" +
            """{"behavior":"deny","message":${escape(message ?: "Denied by user")}}}}"""

    /**
     * Renomme la session côté claude — persisté dans le .jsonl, visible dans le picker
     * /resume du CLI. Découvert par sonde 2026-07-06 (claude 2.1.201) : le champ est
     * `title` (avec `name`, claude répond "undefined is not an object …title.trim").
     */
    fun renameSession(requestId: String, title: String): String =
        """{"type":"control_request","request_id":${escape(requestId)},""" +
            """"request":{"subtype":"rename_session","title":${escape(title)}}}"""

    /** Réponse permissive aux control_request de subtype inconnu (ne pas bloquer claude). */
    fun allowOnceDecision(requestId: String): String =
        """{"type":"control_response","request_id":${escape(requestId)},"response":{"decision":"allow_once"}}"""

    private fun escape(text: String): String =
        "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
}
