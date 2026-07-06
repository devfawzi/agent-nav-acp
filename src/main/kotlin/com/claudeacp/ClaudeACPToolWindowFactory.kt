package com.claudeacp

import com.claudeacp.core.AgentState
import com.claudeacp.core.ChatSession
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
        // Restaure la palette diff persistée au démarrage de la tool window (1er chat).
        DiffPaletteService.getInstance().restoreOnStartup()

        addNewChatContent(project, toolWindow)
        toolWindow.setTitleActions(listOf(
            NewChatAction(project, toolWindow),
            ResumeChatAction(project, toolWindow),
            RenameChatAction(toolWindow),
            MemoryInspectorAction(project),
            ShowLogsAction(project),
            SettingsAction(project)
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
        // Chaque chat = sa propre session backend isolée. Le sessionId est connu dès la
        // construction (preAssignedSid CLI). resumeSid != null = reprise d'une conv stockée.
        val profile = AgentProfilesService.getInstance().getActiveProfile()
        val chatSession = ChatSession(
            project = project,
            profile = profile,
            resumeSid = resumeSid,
            cwdOverride = resumeCwd
        )
        val panel = ClaudeACPToolWindowPanel(project, chatSession, isFirstChat = (n == 1))
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

        // Cleanup à la fermeture du tab : kill process backend + libère VFS listeners.
        // On passe par panel.closeSession() pour respecter le ChatSession courant
        // (l'user peut avoir swap d'agent → chatSession local n'est plus le bon).
        content.setDisposer {
            panel.closeSession()
        }

        panel.renameContentCallback = { newName ->
            content.displayName = newName
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

    private inner class MemoryInspectorAction(
        private val project: Project
    ) : AnAction("Inspect Claude Memory", "Show files Claude auto-loads as memory + delete entries", AllIcons.Actions.SearchWithHistory) {
        override fun actionPerformed(e: AnActionEvent) {
            val acpService = project.getService(ClaudeACPService::class.java)
            val paths = acpService.lastMemoryPaths
            if (paths.isEmpty()) {
                // Fallback : on tente avec le dossier standard du projet courant
                val basePath = project.basePath
                val home = System.getProperty("user.home") ?: ""
                if (basePath != null && home.isNotEmpty()) {
                    val encoded = basePath.replace("/", "-")
                    val fallback = mapOf("auto" to "$home/.claude/projects/$encoded/memory/")
                    MemoryInspectorDialog(project, fallback).show()
                    return
                }
            }
            MemoryInspectorDialog(project, paths).show()
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ShowLogsAction(
        private val project: Project
    ) : AnAction("Show Plugin Logs", "Live logs (control requests/responses, state changes, errors)", AllIcons.Debugger.Console) {
        override fun actionPerformed(e: AnActionEvent) {
            PluginLogDialog(project).show()
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class SettingsAction(
        private val project: Project
    ) : AnAction("Open AgentNav Settings", "Open the AgentNav ACP settings page", AllIcons.General.GearPlain) {
        override fun actionPerformed(e: AnActionEvent) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, AgentSettingsConfigurable::class.java)
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
