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
            RenameChatAction(toolWindow)
        ))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    private fun addNewChatContent(project: Project, toolWindow: ToolWindow): Content {
        val n = sessionCounter.getAndIncrement()
        val panel = ClaudeACPToolWindowPanel(project, isFirstChat = (n == 1))
        val content = ContentFactory.getInstance().createContent(
            panel.getContent(),
            "Chat $n",
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

        if (n > 1) {
            val acpService = project.getService(ClaudeACPService::class.java)
            acpService.newSession { newSid ->
                panel.setSessionId(newSid)
            }
        }

        return content
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
