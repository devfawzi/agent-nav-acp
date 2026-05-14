package com.claudeacp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Font
import java.awt.Point
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JList
import javax.swing.text.JTextComponent

/**
 * Popup autocomplete déclenché par `@` : utilise `JBPopupFactory.createPopupChooserBuilder`
 * qui fournit :
 *  - Navigation native clavier (↑/↓/Enter/Esc)
 *  - Filtrage live au clavier
 *  - Sélection souris (clic = choisir)
 *  - Look IntelliJ standard
 *
 * On positionne via `RelativePoint(textArea, point)` calculé à partir de l'index du `@`.
 */
class FileMentionPopup(
    private val project: Project,
    private val anchor: JTextComponent,
    private val onSelect: (FileEntry) -> Unit
) {

    data class FileEntry(
        val virtualFile: VirtualFile,
        val absolutePath: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val icon: Icon
    )

    private val allEntries: List<FileEntry> by lazy { buildIndex() }
    private var currentPopup: JBPopup? = null

    /** Index du `@` dans le textarea (sert au positionnement). */
    @Volatile
    var anchorIndex: Int = -1

    fun show(query: String) {
        // Si déjà visible avec la même query, on ne refait rien
        if (isVisible()) return
        if (allEntries.isEmpty()) return

        val initial = filterEntries(query)
        if (initial.isEmpty()) return

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(initial)
            .setRenderer(EntryRenderer())
            .setNamerForFiltering { it.relativePath }
            .setRequestFocus(true)              // focus → navigation clavier native
            .setResizable(false)
            .setMovable(false)
            .setItemChosenCallback { entry ->
                onSelect(entry)
                currentPopup = null
                // Redonne le focus au textarea
                anchor.requestFocusInWindow()
            }
            .setCancelCallback {
                currentPopup = null
                anchor.requestFocusInWindow()
                true
            }
            .setMinSize(java.awt.Dimension(380, 280))
            .createPopup()

        currentPopup = popup
        popup.show(buildAnchorPoint())

        // Pré-tape la query dans le speed-search interne du popup
        if (query.isNotEmpty()) {
            try {
                javax.swing.SwingUtilities.invokeLater {
                    val component = popup.content
                    // Le speed search se déclenche en simulant des frappes ; on laisse l'user
                    // continuer à taper et le popup filtre automatiquement.
                }
            } catch (_: Exception) {
            }
        }
    }

    fun hide() {
        currentPopup?.cancel()
        currentPopup = null
    }

    fun isVisible(): Boolean {
        val p = currentPopup ?: return false
        return !p.isDisposed
    }

    /** Géré nativement par le popup quand il a le focus. */
    fun handleKey(e: java.awt.event.KeyEvent): Boolean = false

    private fun buildAnchorPoint(): RelativePoint {
        return try {
            val idx = if (anchorIndex in 0..anchor.document.length) anchorIndex
            else anchor.caretPosition
            val rect = anchor.modelToView2D(idx)
            val point = Point(rect.x.toInt(), (rect.y + rect.height).toInt() + 2)
            RelativePoint(anchor, point)
        } catch (_: Exception) {
            RelativePoint(anchor, Point(0, anchor.height))
        }
    }

    private fun filterEntries(query: String): List<FileEntry> {
        if (query.isEmpty()) return allEntries.take(80)
        val q = query.lowercase()
        val starts = mutableListOf<FileEntry>()
        val contains = mutableListOf<FileEntry>()
        val fuzzy = mutableListOf<FileEntry>()
        for (e in allEntries) {
            val name = File(e.relativePath).name.lowercase()
            val rel = e.relativePath.lowercase()
            when {
                name.startsWith(q) || rel.startsWith(q) -> starts.add(e)
                rel.contains(q) -> contains.add(e)
                fuzzyMatch(rel, q) -> fuzzy.add(e)
            }
            if (starts.size + contains.size + fuzzy.size >= 120) break
        }
        return (starts + contains + fuzzy).take(80)
    }

    private fun fuzzyMatch(text: String, query: String): Boolean {
        var i = 0
        for (c in text) {
            if (i < query.length && c == query[i]) i++
            if (i == query.length) return true
        }
        return false
    }

    private fun buildIndex(): List<FileEntry> = ReadAction.compute<List<FileEntry>, Exception> {
        val result = mutableListOf<FileEntry>()
        val basePath = project.basePath ?: return@compute result
        val ignoredSegments = setOf(
            "build", ".gradle", ".idea", ".git", "node_modules",
            ".intellijPlatform", "target", "out", "dist", ".next",
            "__pycache__", "venv", ".venv", ".kotlin"
        )
        val ftm = FileTypeManager.getInstance()
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            try {
                if (!vf.isValid) return@iterateContent true
                val path = vf.path
                if (ignoredSegments.any { "/$it/" in path || path.endsWith("/$it") }) {
                    return@iterateContent true
                }
                val rel = if (path.startsWith(basePath)) path.substring(basePath.length).trimStart('/')
                else vf.name
                if (rel.isBlank()) return@iterateContent true
                val icon: Icon = if (vf.isDirectory) {
                    com.intellij.icons.AllIcons.Nodes.Folder
                } else {
                    ftm.getFileTypeByFileName(vf.name).icon ?: com.intellij.icons.AllIcons.FileTypes.Any_type
                }
                result.add(FileEntry(vf, path, rel, vf.isDirectory, icon))
                if (result.size > 5000) return@iterateContent false
            } catch (_: Exception) {
            }
            true
        }
        result.sortedWith(compareBy({ it.relativePath.count { c -> c == '/' } }, { it.relativePath }))
    }

    private class EntryRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val entry = value as? FileEntry
            if (entry != null) {
                text = entry.relativePath
                icon = entry.icon
                iconTextGap = 6
            }
            font = font.deriveFont(Font.PLAIN, 12f)
            border = JBUI.Borders.empty(2, 6)
            return this
        }
    }
}
