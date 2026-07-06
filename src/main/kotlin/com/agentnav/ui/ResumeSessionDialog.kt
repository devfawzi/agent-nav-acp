package com.agentnav.ui

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

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

    private val onlyPluginSessionsCheckbox = JCheckBox("Only chats started in this plugin", true).apply {
        toolTipText = "Show only sessions whose entrypoint is sdk-cli (AgentNav ACP). Uncheck to see TUI/SDK-ts sessions too."
        isOpaque = false
    }

    private val searchField = JTextField(20).apply {
        toolTipText = "Filter sessions by content of the first user message or cwd"
    }

    fun show() {
        val clearAllBtn = JButton("🗑 Delete all shown").apply {
            margin = JBUI.insets(2, 8)
            toolTipText = "Delete the .jsonl files of all sessions currently displayed in the list (after filters)."
            foreground = JBColor(java.awt.Color(0xc62828), java.awt.Color(0xef9a9a))
            addActionListener { confirmDeleteAllShown() }
        }
        // BorderLayout : filtres à gauche, bouton "Delete all shown" toujours visible à droite.
        val leftToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            background = UIUtil.getPanelBackground()
            add(JLabel("🔍"))
            add(searchField)
            add(onlyCurrentProjectCheckbox)
            add(onlyPluginSessionsCheckbox)
        }
        val rightToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
            background = UIUtil.getPanelBackground()
            add(clearAllBtn)
        }
        val toolbar = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8, 8, 0, 8)
            add(leftToolbar, BorderLayout.CENTER)
            add(rightToolbar, BorderLayout.EAST)
        }

        onlyCurrentProjectCheckbox.addActionListener { reload() }
        onlyPluginSessionsCheckbox.addActionListener { reload() }
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

    /** State of currently visible sessions, used by the "Delete all shown" button. */
    private var currentVisibleSessions: List<ClaudeSessionsService.SessionInfo> = emptyList()

    private fun confirmDeleteAllShown() {
        val n = currentVisibleSessions.size
        if (n == 0) {
            JOptionPane.showMessageDialog(frame, "No sessions to delete.", "Delete all shown", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val choice = JOptionPane.showConfirmDialog(
            frame,
            "Delete $n session(s) from disk?\n\n" +
                "This removes the corresponding .jsonl files in ~/.claude/projects/.\n" +
                "Only sessions currently shown (after filters) will be deleted. Irreversible.",
            "Delete all shown sessions",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (choice == JOptionPane.YES_OPTION) {
            val deleted = sessionsService.deleteSessions(currentVisibleSessions.map { it.sessionId })
            JOptionPane.showMessageDialog(frame, "$deleted session(s) deleted.", "Delete all shown", JOptionPane.INFORMATION_MESSAGE)
            reload()
        }
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
        val sessions = raw.filter { info ->
            // Filter par entrypoint si toggle actif : on garde uniquement nos sessions plugin.
            if (onlyPluginSessionsCheckbox.isSelected && info.entrypoint != "sdk-cli") {
                return@filter false
            }
            if (query.isEmpty()) return@filter true
            info.firstUserMessage.lowercase().contains(query) ||
                (info.cwd?.lowercase()?.contains(query) == true)
        }
        currentVisibleSessions = sessions

        if (sessions.isEmpty()) {
            listPanel.add(JLabel(
                "<html><div style='padding:20px;color:gray;'>" +
                    "No sessions match the current filter.<br><br>" +
                    "Try unchecking <b>Only chats started in this plugin</b> to see sessions from " +
                    "Claude Code terminal/TUI too, or <b>Only this project</b> for other cwds." +
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
            maximumSize = Dimension(Int.MAX_VALUE, 130)
        }

        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\n", " ↵ ")

        // Titre principal : summary si dispo, sinon first user message (sans wrapper system).
        val title = info.summary?.let { escape(it) } ?: escape(info.firstUserMessage.take(200))
        val sizeKb = (info.sizeBytes / 1024.0).let { String.format("%.1f", it) }
        val cwdShort = info.cwd?.let { cwd ->
            val home = System.getProperty("user.home").orEmpty()
            if (home.isNotEmpty() && cwd.startsWith(home)) "~" + cwd.substring(home.length) else cwd
        } ?: "(unknown cwd)"
        val currentTag = if (isCurrentProject) " <span style='color:#4caf50'>● current project</span>" else ""

        // Bloc "first / last" : on n'affiche le last que s'il est distinct du first.
        val firstAt = info.formattedFirstUserAt() ?: info.formattedDate()
        val firstLine = "<span style='color:gray;font-size:10px;'>📝 first ($firstAt):</span> " +
            "<span style='font-size:11px;'>${escape(info.firstUserMessage.take(160))}</span>"
        val lastLine = info.lastUserMessage?.let { last ->
            val lastAt = info.formattedLastUserAt() ?: ""
            "<br><span style='color:gray;font-size:10px;'>🔁 last ($lastAt):</span> " +
                "<span style='font-size:11px;'>${escape(last.take(160))}</span>"
        } ?: ""

        val centerHtml = buildString {
            append("<html><div style='width:560px;'>")
            // Si summary, on l'affiche en gros, sinon le first.
            if (info.summary != null) {
                append("<b style='font-size:12px;'>$title</b><br>")
                append(firstLine)
                append(lastLine)
            } else {
                // Pas de summary : le first IS le titre
                append("<b style='font-size:12px;'>${escape(info.firstUserMessage.take(180))}</b>")
                append(lastLine)
            }
            append("<br><span style='color:gray;font-size:10px;'>")
            append("$cwdShort$currentTag<br>")
            append("${info.formattedDate()} · ${info.messageCount} msg · ${sizeKb} KB · ")
            append("<code>${info.sessionId.take(8)}…</code>")
            append("</span></div></html>")
        }
        val center = JLabel(centerHtml).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            verticalAlignment = SwingConstants.TOP
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
        val deleteBtn = JButton("🗑").apply {
            margin = JBUI.insets(2, 8)
            toolTipText = "Delete this session's .jsonl from disk (irreversible)"
            foreground = JBColor(java.awt.Color(0xc62828), java.awt.Color(0xef9a9a))
            addActionListener {
                val choice = JOptionPane.showConfirmDialog(
                    frame,
                    "Delete session ${info.sessionId.take(8)}…?\n\nThis removes the .jsonl from disk and cannot be undone.",
                    "Delete session",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                )
                if (choice == JOptionPane.YES_OPTION) {
                    sessionsService.deleteSession(info.sessionId)
                    reload()
                }
            }
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = UIUtil.getTextFieldBackground()
            add(copyBtn)
            add(deleteBtn)
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
