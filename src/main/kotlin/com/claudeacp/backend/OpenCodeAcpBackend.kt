package com.claudeacp.backend

import com.claudeacp.AgentProfile
import com.claudeacp.ClaudeACPService
import com.claudeacp.PromptAttachment
import com.claudeacp.core.AgentBackend
import com.claudeacp.core.AgentState
import com.claudeacp.core.PermissionRequest
import com.claudeacp.core.SessionConfig
import com.claudeacp.core.ToolCallInfo
import com.claudeacp.core.UsageStats
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Bridge minimal entre l'interface AgentBackend et le legacy ClaudeACPService pour les
 * profils ACP/OpenCode. Permet au panel de manipuler N'IMPORTE QUEL profile via la même
 * API AgentBackend sans branchement spécial.
 *
 * Implémentation : on s'abonne aux listeners globaux du service et on filtre par notre
 * sessionId. C'est la même fragilité que l'ancien code — mais c'est OK pour ACP qui :
 *   1. N'a qu'UNE session active à la fois en pratique (1 process OpenCode partagé)
 *   2. Sera refactorisé proprement en follow-up (cf task #9)
 *
 * Permissions : pour l'instant on **ne propose pas** de card UI sur ACP. Le service
 * auto-accepte (cf handlePermissionRequest historique). À refactorer en follow-up.
 */
class OpenCodeAcpBackend(
    private val project: Project,
    private val profile: AgentProfile
) : AgentBackend {

    private val log = thisLogger()
    private val service = project.getService(ClaudeACPService::class.java)

    @Volatile
    private var _sessionId: String? = null
    override val sessionId: String? get() = _sessionId

    override val state: AgentState get() = service.state
    override val config: SessionConfig get() = service.getSessionConfig(_sessionId)
    override val usage: UsageStats get() = service.getSessionUsage(_sessionId)

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

    // ─── Filtrage par sid (legacy pattern) ──────────────────────────────────
    private fun matchesMySession(sid: String?): Boolean {
        val my = _sessionId ?: return false
        return sid == my
    }

    private val stateListener: (AgentState) -> Unit = { s -> onStateChange?.invoke(s) }
    private val sessionCreatedListener: (String) -> Unit = { sid ->
        if (_sessionId == null) {
            _sessionId = sid
            onSessionReady?.invoke(sid)
        }
    }
    private val textChunkListener: (String, String?) -> Unit = { text, sid ->
        if (matchesMySession(sid)) onTextChunk?.invoke(text)
    }
    private val thoughtChunkListener: (String, String?) -> Unit = { text, sid ->
        if (matchesMySession(sid)) onThoughtChunk?.invoke(text)
    }
    private val toolCallListener: (ToolCallInfo) -> Unit = { info ->
        if (matchesMySession(info.sessionId)) onToolCall?.invoke(info)
    }
    private val sessionConfigListener: (String?, SessionConfig) -> Unit = { sid, conf ->
        if (sid != null && matchesMySession(sid)) onConfigChange?.invoke(conf)
    }
    private val executingListener: (Boolean, String?) -> Unit = { exec, sid ->
        if (matchesMySession(sid)) onExecuting?.invoke(exec)
    }
    private val usageListener: (String, UsageStats) -> Unit = { sid, stats ->
        if (matchesMySession(sid)) onUsage?.invoke(stats)
    }
    private val errorListener: (String) -> Unit = { msg -> onError?.invoke(msg) }
    private val infoListener: (String) -> Unit = { msg -> onInfo?.invoke(msg) }
    private val stderrListener: (String) -> Unit = { msg -> onStderr?.invoke(msg) }
    private val toolResultErrorListener: (String, String?) -> Unit = { msg, sid ->
        if (matchesMySession(sid)) onToolResultError?.invoke(msg)
    }

    override fun start() {
        // Abonnement à TOUS les listeners du service global, filtrés par notre sessionId.
        service.addStateListener(stateListener)
        service.addSessionCreatedListener(sessionCreatedListener)
        service.addMessageChunkListener(textChunkListener)
        service.addThoughtChunkListener(thoughtChunkListener)
        service.addToolCallListener(toolCallListener)
        service.addSessionConfigListener(sessionConfigListener)
        service.addExecutingListener(executingListener)
        service.addUsageListener(usageListener)
        service.addErrorListener(errorListener)
        service.addInfoListener(infoListener)
        service.addStderrListener(stderrListener)
        service.addToolResultErrorListener(toolResultErrorListener)

        // Lance le service s'il ne tourne pas encore (1er chat). Sinon newSession pour 2e+ chat.
        if (service.state == AgentState.STOPPED || service.state == AgentState.ERROR) {
            service.startAgent()
        } else {
            service.newSession { sid ->
                if (_sessionId == null) {
                    _sessionId = sid
                    onSessionReady?.invoke(sid)
                }
            }
        }
    }

    override fun stop() {
        service.removeStateListener(stateListener)
        service.removeSessionCreatedListener(sessionCreatedListener)
        // (les autres listeners sont mutableListOf donc on les enlève par référence)
        // Note : le service n'expose pas de remove pour tous les listeners — ils restent
        // jusqu'au reload du plugin. Pour ACP c'est OK le temps du refactor follow-up.
    }

    override fun sendPrompt(text: String, attachments: List<PromptAttachment>) {
        service.sendPrompt(text, _sessionId, attachments)
    }

    override fun cancel() {
        service.cancelPrompt(_sessionId)
    }

    override fun replyToolResult(toolUseId: String, content: String) {
        service.replyToolResult(toolUseId, content, _sessionId)
    }

    override fun setMode(modeId: String) {
        service.setMode(modeId, _sessionId)
    }

    override fun setModel(modelId: String) {
        service.setModel(modelId, _sessionId)
    }

    override fun setEffort(level: String) {
        service.setConfigOption("thinking", level, _sessionId)
    }
}
