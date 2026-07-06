package com.agentnav.services

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.settings.*

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Quick actions éditeur (IMPROVEMENTS #7) : right-click sur une sélection →
 * attache le code au chat actif + pré-remplit un prompt ciblé. Style Cursor.
 */
abstract class BaseQuickClaudeAction(private val promptPrefix: String) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ref = EditorSelectionGrabber.grabCurrentSelection(project) ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("AgentNav") ?: return
        tw.show {
            val content = tw.contentManager.selectedContent ?: return@show
            val panel = content.getUserData(AgentNavToolWindowFactory.PANEL_KEY) ?: return@show
            panel.addAttachmentExternal(ref)
            if (promptPrefix.isNotEmpty()) panel.prefillPrompt(promptPrefix)
        }
    }
}

/** Attache la sélection et focus l'input — l'user formule sa question. */
class AskClaudeAction : BaseQuickClaudeAction("")

class ExplainWithClaudeAction : BaseQuickClaudeAction(
    "Explain this code — what it does, how it works, and any pitfalls: ")

class RefactorWithClaudeAction : BaseQuickClaudeAction(
    "Refactor this code to improve readability and structure, without changing behavior: ")
