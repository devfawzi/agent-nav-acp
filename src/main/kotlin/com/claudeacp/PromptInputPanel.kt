package com.claudeacp

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.border.AbstractBorder

/**
 * Zone d'input du prompt avec toolbar footer (Mode, Model, Effort).
 * Inspirée de Cursor : grand textarea + barre de paramètres en bas.
 */
class PromptInputPanel(private val project: Project) {

    private val acpService = project.getService(ClaudeACPService::class.java)

    private val textArea = JTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        border = JBUI.Borders.empty(8, 10)
        background = UIUtil.getTextFieldBackground()
    }

    private val modeButton = createMenuButton("Mode")
    private val modelButton = createMenuButton("Model")
    private val effortButton = createMenuButton("Effort")

    private val sendButton = JButton("➤").apply {
        toolTipText = "Send (Enter — Shift+Enter for new line)"
        margin = JBUI.insets(4, 10)
        font = font.deriveFont(Font.BOLD)
    }

    @Volatile
    private var isExecuting = false

    private var onSend: ((String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    fun getContent(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                RoundedBorder(JBColor.border(), 10),
                JBUI.Borders.empty(4)
            )
        }

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    send()
                }
                // Shift+Enter laisse le comportement par défaut (nouvelle ligne)
            }
        })

        val scrollText = JScrollPane(textArea).apply {
            border = null
            preferredSize = Dimension(0, 70)
        }

        // Footer toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = UIUtil.getPanelBackground()
        }
        toolbar.add(modeButton)
        toolbar.add(modelButton)
        toolbar.add(effortButton)

        val sendPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 2)).apply {
            background = UIUtil.getPanelBackground()
            add(sendButton)
        }
        val footer = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(toolbar, BorderLayout.WEST)
            add(sendPanel, BorderLayout.EAST)
        }

        sendButton.addActionListener { onSendButtonClick() }

        root.add(scrollText, BorderLayout.CENTER)
        root.add(footer, BorderLayout.SOUTH)

        acpService.addSessionConfigListener { updateButtons(it) }
        updateButtons(acpService.sessionConfig)

        // Réagir au state executing du service : si exécution → bouton Stop, sinon Send
        acpService.addExecutingListener { executing ->
            javax.swing.SwingUtilities.invokeLater { setExecutingState(executing) }
        }

        return root
    }

    fun onSend(handler: (String) -> Unit) {
        onSend = handler
    }

    fun onCancel(handler: () -> Unit) {
        onCancel = handler
    }

    fun setReady(ready: Boolean) {
        sendButton.isEnabled = ready
        textArea.isEnabled = ready
    }

    private fun setExecutingState(executing: Boolean) {
        isExecuting = executing
        if (executing) {
            sendButton.text = "⏹"
            sendButton.toolTipText = "Stop the current prompt"
            sendButton.foreground = java.awt.Color(0xc62828)
        } else {
            sendButton.text = "➤"
            sendButton.toolTipText = "Send (Enter — Shift+Enter for new line)"
            sendButton.foreground = null
        }
    }

    private fun onSendButtonClick() {
        if (isExecuting && textArea.text.trim().isEmpty()) {
            // Click vide pendant exec = annuler
            onCancel?.invoke()
            return
        }
        send()
    }

    private fun send() {
        val txt = textArea.text.trim()
        if (txt.isEmpty()) {
            // En executing sans texte : c'est un Stop
            if (isExecuting) onCancel?.invoke()
            return
        }
        // Si un prompt est en cours, on l'interrompt avant d'envoyer le nouveau
        if (isExecuting) {
            onCancel?.invoke()
        }
        onSend?.invoke(txt)
        textArea.text = ""
    }

    private fun updateButtons(config: ClaudeACPService.SessionConfig) {
        // Mode
        val currentMode = config.modes.firstOrNull { it.id == config.currentModeId }
        modeButton.text = "Mode: ${currentMode?.name ?: "default"} ▾"
        modeButton.isEnabled = config.modes.isNotEmpty()

        // Model
        val currentModel = config.models.firstOrNull { it.id == config.currentModelId }
        modelButton.text = "Model: ${currentModel?.name ?: "Default"} ▾"
        modelButton.isEnabled = config.models.isNotEmpty()

        // Effort (config option de catégorie "thought_level" ou id contenant "effort"/"thought")
        val effortOpt = config.configOptions.firstOrNull {
            it.id.lowercase().contains("thought") || it.id.lowercase().contains("effort")
                || it.name.lowercase().contains("effort") || it.name.lowercase().contains("thinking")
        }
        if (effortOpt != null) {
            val curr = effortOpt.options.firstOrNull { it.id == effortOpt.currentValue }
            effortButton.text = "Effort: ${curr?.name ?: effortOpt.currentValue ?: "default"} ▾"
            effortButton.isEnabled = true
        } else {
            effortButton.text = "Effort: -"
            effortButton.isEnabled = false
        }

        // Menus
        wireModelMenu(config)
        wireModeMenu(config)
        wireEffortMenu(effortOpt)
    }

    private fun wireModelMenu(config: ClaudeACPService.SessionConfig) {
        for (l in modelButton.actionListeners) modelButton.removeActionListener(l)
        modelButton.addActionListener {
            val menu = JPopupMenu()
            config.models.forEach { opt ->
                val item = JMenuItem(opt.name + if (opt.id == config.currentModelId) "  ✓" else "")
                item.toolTipText = opt.description
                item.addActionListener { acpService.setModel(opt.id) }
                menu.add(item)
            }
            menu.show(modelButton, 0, modelButton.height)
        }
    }

    private fun wireModeMenu(config: ClaudeACPService.SessionConfig) {
        for (l in modeButton.actionListeners) modeButton.removeActionListener(l)
        modeButton.addActionListener {
            val menu = JPopupMenu()
            config.modes.forEach { opt ->
                val item = JMenuItem(opt.name + if (opt.id == config.currentModeId) "  ✓" else "")
                item.toolTipText = opt.description
                item.addActionListener { acpService.setMode(opt.id) }
                menu.add(item)
            }
            menu.show(modeButton, 0, modeButton.height)
        }
    }

    private fun wireEffortMenu(effort: ClaudeACPService.ConfigOption?) {
        for (l in effortButton.actionListeners) effortButton.removeActionListener(l)
        if (effort == null) return
        effortButton.addActionListener {
            val menu = JPopupMenu()
            effort.options.forEach { opt ->
                val item = JMenuItem(opt.name + if (opt.id == effort.currentValue) "  ✓" else "")
                item.toolTipText = opt.description
                item.addActionListener { acpService.setConfigOption(effort.id, opt.id) }
                menu.add(item)
            }
            menu.show(effortButton, 0, effortButton.height)
        }
    }

    private fun createMenuButton(label: String): JButton {
        return JButton("$label ▾").apply {
            margin = JBUI.insets(2, 6)
            isFocusPainted = false
            font = font.deriveFont(Font.PLAIN, 11f)
        }
    }

    private class RoundedBorder(private val color: Color, private val radius: Int) : AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
        }
        override fun getBorderInsets(c: Component): Insets = Insets(4, 4, 4, 4)
    }
}
