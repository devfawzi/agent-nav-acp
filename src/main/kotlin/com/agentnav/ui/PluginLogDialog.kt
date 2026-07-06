package com.agentnav.ui

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Modal qui affiche les entries du `PluginLogService` en live avec filter level/category.
 * Utile pour debug des control_requests, permission flow, parse fails sans fouiller idea.log.
 */
class PluginLogDialog(private val project: Project) {

    private val service = PluginLogService.getInstance(project)
    private val frame = JDialog(null as Frame?, "AgentNav ACP — logs", false).apply {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        size = Dimension(900, 560)
        setLocationRelativeTo(null)
    }

    private val area = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = UIUtil.getTextFieldBackground()
        border = JBUI.Borders.empty(6)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }

    private val levelFilter = JComboBox(arrayOf("ALL", "DEBUG", "INFO", "WARN", "ERROR")).apply {
        // Default DEBUG : on a beaucoup de logs détaillés sur les interactions claude
        // (permission flow, control_request, VFS tracking). Mieux vaut tout voir d'emblée
        // pour diagnostiquer les bugs ; l'user filtre s'il veut moins.
        selectedItem = "DEBUG"
    }
    private val categoryFilter = JTextField(20).apply { toolTipText = "Filter by category substring" }
    private val autoScrollCheck = JCheckBox("Auto-scroll", true).apply { isOpaque = false }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val listener: (PluginLogService.Entry) -> Unit = { _ ->
        ApplicationManager.getApplication().invokeLater { render() }
    }

    fun show() {
        levelFilter.addActionListener { render() }
        categoryFilter.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = render()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = render()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = render()
        })

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            background = UIUtil.getPanelBackground()
            add(JLabel("Level:"))
            add(levelFilter)
            add(JLabel("Category:"))
            add(categoryFilter)
            add(autoScrollCheck)
            add(JButton("Clear").apply {
                margin = JBUI.insets(2, 8)
                addActionListener { service.clear(); render() }
            })
            add(JButton("Copy all").apply {
                margin = JBUI.insets(2, 8)
                addActionListener {
                    val raw = service.snapshot().joinToString("\n") { e ->
                        "${timeFormatter.format(e.timestamp)} [${e.level}] ${e.category}: ${e.message}"
                    }
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(java.awt.datatransfer.StringSelection(raw), null)
                }
            })
        }

        val root = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(area), BorderLayout.CENTER)
        }
        frame.contentPane = root
        service.addListener(listener)
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent) {
                service.removeListener(listener)
            }
        })
        render()
        frame.isVisible = true
    }

    private fun render() {
        val wantedLevel = levelFilter.selectedItem as String
        val needle = categoryFilter.text?.trim()?.lowercase().orEmpty()
        val snapshot = service.snapshot().filter { e ->
            (wantedLevel == "ALL" || e.level.name == wantedLevel) &&
                (needle.isEmpty() || e.category.lowercase().contains(needle))
        }
        val sb = StringBuilder("<html><body style='font-family:monospaced;font-size:11px;'>")
        for (e in snapshot) {
            val color = when (e.level) {
                PluginLogService.Level.ERROR -> "#ef5350"
                PluginLogService.Level.WARN -> "#ffa726"
                PluginLogService.Level.INFO -> JBColor.foreground().rgbHex()
                PluginLogService.Level.DEBUG -> "#9aa0a6"
            }
            val ts = timeFormatter.format(e.timestamp)
            val msg = e.message
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            sb.append("<div style='color:$color;'>")
                .append("<span style='color:gray;'>$ts</span> ")
                .append("<b>[${e.level.name.first()}]</b> ")
                .append("<span style='color:#7a9fd1;'>${e.category}</span>: ")
                .append(msg)
                .append("</div>")
        }
        sb.append("</body></html>")
        area.text = sb.toString()
        if (autoScrollCheck.isSelected) {
            area.caretPosition = area.document.length
        }
    }

    private fun Color.rgbHex(): String = String.format("#%02x%02x%02x", red, green, blue)
}
