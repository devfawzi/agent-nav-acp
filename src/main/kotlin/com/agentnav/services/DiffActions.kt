package com.agentnav.services

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Actions custom enregistrées dans le toolbar du DiffViewer IntelliJ pour les fichiers en
 * pending change : Accept le fichier entier, ou Reject (revert au before).
 *
 * Le hunk-by-hunk granulaire est déjà fourni par IntelliJ via les boutons `>>` / `<<`
 * dans la gutter du diff (revert d'un hunk droit→gauche = reject d'un hunk).
 */
class AcceptFileDiffAction(
    private val project: Project,
    private val path: String
) : AnAction("Accept All Changes", "Mark this file as reviewed and remove from pending changes", AllIcons.Actions.Checked) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val svc = project.getService(PendingChangesService::class.java)
        svc.accept(path)
    }
}

class RejectFileDiffAction(
    private val project: Project,
    private val path: String
) : AnAction("Reject All Changes", "Revert this file to the BEFORE state", AllIcons.Actions.Cancel) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val svc = project.getService(PendingChangesService::class.java)
        svc.reject(path)
    }
}

/**
 * Action toolbar qui ouvre un picker des palettes de couleurs diff prédéfinies. Au clic
 * sur une palette, on modifie le scheme IntelliJ actif → tous les diffs adoptent la
 * nouvelle apparence (rouge/vert plus voyants). Reset disponible.
 */
class DiffPalettePickerAction :
    AnAction("Diff color palette…", "Pick a preset palette or define custom colors", AllIcons.Actions.Colors) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val component = e.inputEvent?.component ?: return
        val service = DiffPaletteService.getInstance()
        val menu = javax.swing.JPopupMenu()
        DiffPaletteService.Palette.values().forEach { p ->
            val checked = if (p.name == service.selectedPalette) "  ✓" else ""
            val item = javax.swing.JMenuItem("${p.displayName}$checked").apply {
                toolTipText = p.description
                addActionListener {
                    if (p == DiffPaletteService.Palette.CUSTOM) {
                        // Ouvre l'éditeur de couleurs custom (avec live preview).
                        DiffCustomPaletteDialog().show()
                    } else {
                        service.apply(p)
                    }
                }
            }
            menu.add(item)
        }
        menu.show(component, 0, component.height)
    }
}

