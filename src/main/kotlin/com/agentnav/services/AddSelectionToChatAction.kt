package com.agentnav.services

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Action "Add selection to AgentNav chat" — déclenchable depuis l'éditeur via raccourci
 * keymap ou right-click. Prend la sélection courante, crée un PromptAttachment.CodeRef et
 * l'ajoute au chat actif (= l'onglet sélectionné de la tool window AgentNav ACP).
 *
 * Ouvre la tool window si elle est fermée. Si pas de sélection : no-op + notification.
 */
class AddSelectionToChatAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = hasSelection
        e.presentation.text = "Add Selection to AgentNav Chat"
        e.presentation.description = "Attach the current editor selection (with file:line range) to the active AgentNav chat."
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ref = EditorSelectionGrabber.grabCurrentSelection(project) ?: return

        // Ouvre la tool window AgentNav et trouve le panel actif.
        val twMgr = ToolWindowManager.getInstance(project)
        val tw = twMgr.getToolWindow("AgentNav ACP") ?: return
        tw.show {
            val content = tw.contentManager.selectedContent ?: return@show
            val panel = content.getUserData(AgentNavToolWindowFactory.PANEL_KEY) ?: return@show
            panel.addAttachmentExternal(ref)
        }
    }
}
