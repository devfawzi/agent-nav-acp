package com.claudeacp

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JOptionPane

class ClaudeACPToolWindowFactory : ToolWindowFactory, DumbAware {

    private val sessionCounter = AtomicInteger(1)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        addNewChatContent(project, toolWindow)
        toolWindow.setTitleActions(listOf(
            NewChatAction(project, toolWindow),
            ResumeChatAction(project, toolWindow),
            RenameChatAction(toolWindow)
        ))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    private fun addNewChatContent(
        project: Project,
        toolWindow: ToolWindow,
        resumeSid: String? = null,
        resumeCwd: String? = null,
        initialTitle: String? = null
    ): Content {
        val n = sessionCounter.getAndIncrement()
        val panel = ClaudeACPToolWindowPanel(project, isFirstChat = (n == 1))
        val displayTitle = initialTitle ?: "Chat $n"
        val content = ContentFactory.getInstance().createContent(
            panel.getContent(),
            displayTitle,
            false
        )
        content.isCloseable = sessionCounter.get() > 2
        content.putUserData(PANEL_KEY, panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content, true)

        if (toolWindow.contentManager.contents.size > 1) {
            toolWindow.contentManager.contents.forEach { it.isCloseable = true }
        }

        // Callback pour permettre au panel de renommer son content (sur 1er prompt ou rename manuel)
        panel.renameContentCallback = { newName ->
            content.displayName = newName
        }

        val acpService = project.getService(ClaudeACPService::class.java)

        if (resumeSid != null) {
            // Reprise : spawn un nouveau process avec --resume <sid>. Pour Chat 1, on doit
            // stopper le process initial créé par startAgent() pour repartir sur le resume.
            // Pour Chat 2+, on resume directement (le service supporte les multi-process CLI).
            if (n == 1 && acpService.state != ClaudeACPService.State.STOPPED) {
                acpService.stopAgent()
            }
            acpService.resumeCliSession(resumeSid, cwdOverride = resumeCwd) { newSid ->
                panel.setSessionId(newSid)
            }
            return content
        }

        if (n > 1) {
            // Crée la session immédiatement si possible, sinon attend que le service soit READY.
            if (acpService.state == ClaudeACPService.State.READY ||
                acpService.state == ClaudeACPService.State.CREATING_SESSION) {
                acpService.newSession { newSid -> panel.setSessionId(newSid) }
            } else {
                val listener = object : (ClaudeACPService.State) -> Unit {
                    override fun invoke(s: ClaudeACPService.State) {
                        if (s == ClaudeACPService.State.READY) {
                            acpService.removeStateListener(this)
                            acpService.newSession { newSid -> panel.setSessionId(newSid) }
                        }
                    }
                }
                acpService.addStateListener(listener)
            }
        }

        return content
    }

    private inner class ResumeChatAction(
        private val project: Project,
        private val toolWindow: ToolWindow
    ) : AnAction("Resume Previous Chat", "Reopen a Claude Code session from history", AllIcons.Vcs.History) {
        override fun actionPerformed(e: AnActionEvent) {
            val sessionsService = project.getService(ClaudeSessionsService::class.java)
            // Le dialog charge lui-même (current project + toggle "all projects") et filtre.
            ResumeSessionDialog(project, sessionsService) { picked ->
                val title = picked.firstUserMessage.take(40).let {
                    if (picked.firstUserMessage.length > 40) "$it…" else it
                }
                addNewChatContent(
                    project, toolWindow,
                    resumeSid = picked.sessionId,
                    // Crucial : utilise le cwd d'origine pour que `claude --resume <sid>` retrouve
                    // la conv (sinon "No conversation found with session ID" et proc exit).
                    resumeCwd = picked.cwd,
                    initialTitle = title
                )
            }.show()
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class NewChatAction(
        private val project: Project,
        private val toolWindow: ToolWindow
    ) : AnAction("New Chat", "Start a new Claude chat", AllIcons.General.Add) {
        override fun actionPerformed(e: AnActionEvent) {
            addNewChatContent(project, toolWindow)
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class RenameChatAction(
        private val toolWindow: ToolWindow
    ) : AnAction("Rename Chat", "Rename the current chat tab", AllIcons.Actions.Edit) {
        override fun actionPerformed(e: AnActionEvent) {
            val content = toolWindow.contentManager.selectedContent ?: return
            val current = content.displayName ?: "Chat"
            val newName = JOptionPane.showInputDialog(
                null,
                "Rename chat:",
                current
            )?.trim().orEmpty()
            if (newName.isNotEmpty()) {
                content.displayName = newName
                content.getUserData(PANEL_KEY)?.renameChat(newName)
            }
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = toolWindow.contentManager.selectedContent != null
        }
    }

    companion object {
        val PANEL_KEY: com.intellij.openapi.util.Key<ClaudeACPToolWindowPanel> =
            com.intellij.openapi.util.Key.create("claude-acp-tool-panel")
    }
}
