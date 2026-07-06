package com.agentnav.core

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.intellij.openapi.project.Project

/**
 * Session de chat possédée par UN panel. Wrappe un AgentBackend isolé + l'identité de la
 * session côté UI. C'est l'unité d'isolation : pas de listener global, pas de filtre par sid.
 *
 * Le panel instancie ChatSession, branche ses callbacks UI, puis appelle start().
 *
 * Le wrapping permettra plus tard d'attacher des services per-session (history scopé,
 * pending changes scopé, etc.) sans modifier l'API du panel.
 */
class ChatSession(
    private val project: Project,
    val profile: AgentProfile,
    resumeSid: String? = null,
    cwdOverride: String? = null
) {

    /**
     * Backend agent : 1 process claude (CLI) ou 1 session isolée sur le hub ACP (OpenCode,
     * agents custom). Même contrat AgentBackend dans les deux cas.
     */
    val backend: AgentBackend = when (profile.transport) {
        Transport.CLI_STREAM_JSON ->
            ClaudeCliBackend(project, profile, resumeSid = resumeSid, cwdOverride = cwdOverride)
        else -> AcpSessionBackend(project, profile)
    }

    /** Convenience : sessionId du backend (pour rename de tab, sessionLabel, etc.). */
    val sessionId: String? get() = backend.sessionId

    /** Lance le backend. À appeler APRÈS avoir branché les callbacks UI. */
    fun start() {
        backend.start()
    }

    /** Tear-down propre : kill process, déconnecte VFS listeners, cancel coroutines. */
    fun close() {
        backend.stop()
    }

    fun sendPrompt(text: String, attachments: List<PromptAttachment> = emptyList()) {
        backend.sendPrompt(text, attachments)
    }

    fun cancel() {
        backend.cancel()
    }

    fun replyToolResult(toolUseId: String, content: String) {
        backend.replyToolResult(toolUseId, content)
    }

    fun setMode(modeId: String) {
        backend.setMode(modeId)
    }

    fun setModel(modelId: String) {
        backend.setModel(modelId)
    }

    fun setEffort(level: String) {
        backend.setEffort(level)
    }
}
