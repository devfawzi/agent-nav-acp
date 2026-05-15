package com.claudeacp

import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.io.File
import javax.swing.*

/**
 * Modal qui liste tous les fichiers de mémoire auto chargés par claude (depuis
 * `system:init.memory_paths`) + permet de les ouvrir / supprimer.
 *
 * Aide à savoir ce que claude "sait" persistemment et à nettoyer les entrées obsolètes.
 */
class MemoryInspectorDialog(
    private val project: Project,
    private val memoryPaths: Map<String, String>
) {

    private val frame = JDialog(null as Frame?, "Claude memory inspector", true).apply {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        size = Dimension(820, 560)
        setLocationRelativeTo(null)
    }

    private val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)
    }

    fun show() {
        reload()
        val root = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(JBScrollPane(listPanel).apply { border = null }, BorderLayout.CENTER)
        }
        frame.contentPane = root
        frame.isVisible = true
    }

    private fun reload() {
        listPanel.removeAll()
        if (memoryPaths.isEmpty()) {
            listPanel.add(JLabel(
                "<html><div style='padding:20px;color:gray;'>" +
                    "No memory paths reported by Claude. Send a prompt first so Claude " +
                    "emits its <code>system:init</code>." +
                    "</div></html>"
            ))
            listPanel.revalidate(); listPanel.repaint()
            return
        }
        for ((kind, path) in memoryPaths) {
            listPanel.add(buildKindHeader(kind, path))
            listPanel.add(Box.createVerticalStrut(4))
            val dir = File(path)
            if (!dir.isDirectory) {
                listPanel.add(emptyLabel("(directory does not exist: $path)"))
                continue
            }
            val files = dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".md") }
                .sortedBy { it.lastModified() }
                .toList()
                .reversed()
            if (files.isEmpty()) {
                listPanel.add(emptyLabel("(no .md memory files in $path)"))
                continue
            }
            files.forEach { f -> listPanel.add(buildFileRow(f)) }
        }
        listPanel.revalidate(); listPanel.repaint()
    }

    private fun buildKindHeader(kind: String, path: String): JComponent {
        val home = System.getProperty("user.home").orEmpty()
        val shortPath = if (home.isNotEmpty() && path.startsWith(home)) "~" + path.substring(home.length) else path
        return JLabel("<html><b>📁 $kind</b>  <span style='color:gray;font-size:10px;'>$shortPath</span></html>").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            border = JBUI.Borders.empty(6, 4, 4, 4)
        }
    }

    private fun emptyLabel(text: String): JComponent =
        JLabel("<html><span style='color:gray;font-style:italic;font-size:10px;'>$text</span></html>").apply {
            border = JBUI.Borders.empty(2, 16)
        }

    private fun buildFileRow(file: File): JComponent {
        val row = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(6, 10)
            )
            background = UIUtil.getTextFieldBackground()
            maximumSize = Dimension(Int.MAX_VALUE, 90)
        }
        val name = file.name
        val sizeKb = String.format("%.1f", file.length() / 1024.0)
        val preview = previewSummary(file)
        val label = JLabel(
            "<html><div style='width:540px;'>" +
                "<b>$name</b>  <span style='color:gray;font-size:10px;'>${sizeKb} KB · ${java.time.Instant.ofEpochMilli(file.lastModified())}</span><br>" +
                "<span style='color:gray;font-size:11px;'>${preview.replace("<", "&lt;").replace(">", "&gt;")}</span>" +
                "</div></html>"
        ).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
        }
        val openBtn = JButton("Open").apply {
            margin = JBUI.insets(2, 8)
            addActionListener { openInEditor(file) }
        }
        val deleteBtn = JButton("🗑").apply {
            margin = JBUI.insets(2, 8)
            foreground = JBColor(Color(0xc62828), Color(0xef9a9a))
            toolTipText = "Delete this memory file (irreversible)"
            addActionListener {
                val choice = JOptionPane.showConfirmDialog(
                    frame,
                    "Delete memory file ${file.name}?\n\nClaude will lose this knowledge on the next session.",
                    "Delete memory",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                )
                if (choice == JOptionPane.YES_OPTION) {
                    if (file.delete()) reload()
                }
            }
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = UIUtil.getTextFieldBackground()
            add(openBtn)
            add(deleteBtn)
        }
        row.add(label, BorderLayout.CENTER)
        row.add(actions, BorderLayout.EAST)
        return row
    }

    /** Lit les premières lignes du .md pour donner un aperçu de ce que claude "sait". */
    private fun previewSummary(file: File): String {
        return try {
            val text = file.bufferedReader(Charsets.UTF_8).useLines { it.take(20).toList().joinToString(" ") }
            text.take(220).replace("\n", " ").let { if (text.length > 220) "$it…" else it }
        } catch (e: Exception) {
            "(read error: ${e.message})"
        }
    }

    private fun openInEditor(file: File) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }
}
