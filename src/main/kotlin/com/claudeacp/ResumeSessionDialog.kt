package com.claudeacp

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Modal dialog qui liste les sessions Claude Code stockées sur disque (toutes ou seulement
 * celles du projet courant) et permet d'en reprendre une via `claude --resume <sid>`.
 * Affiche pour chaque session : 1er user message, cwd, nb messages, date, sid court.
 */
class ResumeSessionDialog(
    private val project: Project,
    private val sessionsService: ClaudeSessionsService,
    private val onResume: (ClaudeSessionsService.SessionInfo) -> Unit
) {

    private val frame = JDialog(null as Frame?, "Resume Claude Code session", true).apply {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        size = Dimension(820, 580)
        setLocationRelativeTo(null)
    }

    private val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)
    }

    private val onlyCurrentProjectCheckbox = JCheckBox("Only this project", true).apply {
        toolTipText = "Limit to sessions whose cwd matches the current project base path."
        isOpaque = false
    }

    private val searchField = JTextField(20).apply {
        toolTipText = "Filter sessions by content of the first user message or cwd"
    }

    fun show() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8, 8, 0, 8)
            add(JLabel("🔍"))
            add(searchField)
            add(onlyCurrentProjectCheckbox)
        }

        onlyCurrentProjectCheckbox.addActionListener { reload() }
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = reload()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = reload()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = reload()
        })

        val root = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(listPanel).apply { border = null }, BorderLayout.CENTER)
        }

        reload()
        frame.contentPane = root
        frame.isVisible = true
    }

    private fun reload() {
        listPanel.removeAll()

        val raw = if (onlyCurrentProjectCheckbox.isSelected) {
            sessionsService.listSessions(limit = 200)
        } else {
            sessionsService.listAllSessions(limit = 500)
        }
        val basePath = project.basePath
        val query = searchField.text?.trim()?.lowercase().orEmpty()
        val sessions = raw.filter {
            if (query.isEmpty()) return@filter true
            it.firstUserMessage.lowercase().contains(query) ||
                (it.cwd?.lowercase()?.contains(query) == true)
        }

        if (sessions.isEmpty()) {
            listPanel.add(JLabel(
                "<html><div style='padding:20px;color:gray;'>" +
                    "No sessions match the current filter.<br><br>" +
                    "Try unchecking <b>Only this project</b> to see all stored Claude sessions, " +
                    "or clear the search field." +
                    "</div></html>"
            ).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
        } else {
            sessions.forEach { info ->
                val isCurrent = basePath != null && info.cwd == basePath
                listPanel.add(buildRow(info, isCurrent))
                listPanel.add(Box.createVerticalStrut(4))
            }
        }

        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun buildRow(info: ClaudeSessionsService.SessionInfo, isCurrentProject: Boolean): JComponent {
        val row = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10)
            )
            background = UIUtil.getTextFieldBackground()
            maximumSize = Dimension(Int.MAX_VALUE, 90)
        }

        val previewHtml = info.firstUserMessage
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\n", "  ")
            .take(200)
        val sizeKb = (info.sizeBytes / 1024.0).let { String.format("%.1f", it) }
        val cwdShort = info.cwd?.let { cwd ->
            // Raccourcit /home/fawzi/... → ~/...
            val home = System.getProperty("user.home").orEmpty()
            if (home.isNotEmpty() && cwd.startsWith(home)) "~" + cwd.substring(home.length) else cwd
        } ?: "(unknown cwd)"
        val currentTag = if (isCurrentProject) " <span style='color:#4caf50'>● current project</span>" else ""
        val center = JLabel(
            "<html><div style='width:560px;'>" +
                "<b>${previewHtml}</b><br>" +
                "<span style='color:gray;font-size:10px;'>" +
                "$cwdShort$currentTag<br>" +
                "${info.formattedDate()} · ${info.messageCount} msg · ${sizeKb} KB · " +
                "<code>${info.sessionId.take(8)}…</code>" +
                "</span></div></html>"
        ).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
        }

        val resumeBtn = JButton("▶ Resume").apply {
            margin = JBUI.insets(2, 10)
            toolTipText = if (isCurrentProject)
                "Reopen this session in the plugin"
            else
                "Reopen this session in the plugin (cwd will switch to ${info.cwd})"
            addActionListener {
                frame.dispose()
                onResume(info)
            }
        }
        val copyBtn = JButton("Copy sid").apply {
            margin = JBUI.insets(2, 8)
            toolTipText = "Copy claude --resume ${info.sessionId} to clipboard"
            addActionListener {
                val cmd = "claude --resume ${info.sessionId}"
                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    java.awt.datatransfer.StringSelection(cmd), null
                )
                val prev = text
                text = "✓ copied"
                javax.swing.Timer(1200) { text = prev; (it.source as javax.swing.Timer).stop() }.start()
            }
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = UIUtil.getTextFieldBackground()
            add(copyBtn)
            add(resumeBtn)
        }

        row.add(center, BorderLayout.CENTER)
        row.add(actions, BorderLayout.EAST)

        // Double-clic n'importe où sur la ligne = Resume
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    frame.dispose()
                    onResume(info)
                }
            }
        })

        return row
    }
}
