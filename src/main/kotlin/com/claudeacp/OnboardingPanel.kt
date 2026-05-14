package com.claudeacp

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Panel affiché à la place du chat quand les prérequis ne sont pas remplis.
 * Donne les instructions d'install avec commandes copyables.
 */
class OnboardingPanel(private val onRetry: () -> Unit) {

    private val titleLabel = JLabel("Setup required").apply {
        font = font.deriveFont(Font.BOLD, 18f)
        foreground = JBColor.foreground()
        border = JBUI.Borders.empty(0, 0, 8, 0)
    }

    private val subtitleLabel = JLabel("To use AgentNav ACP, install the missing components below.").apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 0, 16, 0)
    }

    private val stepsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val retryButton = JButton("🔄 Recheck").apply {
        toolTipText = "Re-check whether the prerequisites are now installed"
        addActionListener { onRetry() }
    }

    private val openSettingsButton = JButton("⚙ Open Settings").apply {
        toolTipText = "Manually configure paths to claude / npx binaries"
        addActionListener {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(null, AgentSettingsConfigurable::class.java)
        }
    }

    fun getContent(): JComponent {
        val main = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(16, 20)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
        }
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        retryButton.alignmentX = Component.LEFT_ALIGNMENT
        content.add(titleLabel)
        content.add(subtitleLabel)
        content.add(stepsContainer)
        content.add(Box.createVerticalStrut(20))
        val buttonRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
            add(retryButton)
            add(Box.createHorizontalStrut(8))
            add(openSettingsButton)
        }
        content.add(buttonRow)

        val scroll = JBScrollPane(content).apply {
            border = null
            viewport.background = UIUtil.getPanelBackground()
        }
        main.add(scroll, BorderLayout.CENTER)

        return main
    }

    fun update(prerequisites: ClaudeACPService.Prerequisites) {
        stepsContainer.removeAll()

        if (prerequisites.npxPath == null) {
            stepsContainer.add(buildStep(
                stepIndex = 1,
                title = "Install Node.js",
                description = "The Claude Agent ACP server runs on Node.js. Install via nvm (recommended):",
                commands = listOf(
                    "curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/master/install.sh | bash",
                    "nvm install --lts"
                ),
                docUrl = "https://nodejs.org/"
            ))
            stepsContainer.add(Box.createVerticalStrut(12))
        }

        if (prerequisites.claudeCliPath == null) {
            stepsContainer.add(buildStep(
                stepIndex = if (prerequisites.npxPath == null) 2 else 1,
                title = "Install Claude Code CLI",
                description = "The Claude Code CLI provides authentication and access to Claude API.",
                commands = listOf(
                    "curl -fsSL https://claude.ai/install.sh | bash",
                    "claude  # then complete the login flow"
                ),
                docUrl = "https://docs.claude.com/en/docs/claude-code/quickstart"
            ))
            stepsContainer.add(Box.createVerticalStrut(12))
        }

        if (prerequisites.allOk) {
            stepsContainer.add(JLabel("✅ All prerequisites are installed.").apply {
                foreground = JBColor.GREEN
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        stepsContainer.revalidate()
        stepsContainer.repaint()
    }

    private fun buildStep(
        stepIndex: Int,
        title: String,
        description: String,
        commands: List<String>,
        docUrl: String?
    ): JPanel {
        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(12, 16)
            )
            alignmentX = Component.LEFT_ALIGNMENT
        }

        card.add(JLabel("$stepIndex. $title").apply {
            font = font.deriveFont(Font.BOLD, 14f)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        card.add(Box.createVerticalStrut(6))
        card.add(JLabel("<html>$description</html>").apply {
            foreground = JBColor.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
        })
        card.add(Box.createVerticalStrut(10))

        commands.forEach { cmd ->
            card.add(buildCommandRow(cmd))
            card.add(Box.createVerticalStrut(6))
        }

        if (docUrl != null) {
            card.add(Box.createVerticalStrut(4))
            card.add(buildLink(docUrl, "📖 Documentation"))
        }

        return card
    }

    private fun buildCommandRow(command: String): JPanel {
        val row = JPanel(BorderLayout()).apply {
            background = UIUtil.getEditorPaneBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4, 8)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val cmdLabel = JTextField(command).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            isEditable = false
            border = null
            background = UIUtil.getEditorPaneBackground()
        }

        val copyButton = JButton(AllIcons.Actions.Copy).apply {
            toolTipText = "Copy to clipboard"
            margin = JBUI.insets(2)
            isFocusPainted = false
            isContentAreaFilled = false
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(command), null)
                text = "✓"
                javax.swing.Timer(1200) {
                    icon = AllIcons.Actions.Copy
                    text = ""
                }.apply { isRepeats = false }.start()
            }
        }

        row.add(cmdLabel, BorderLayout.CENTER)
        row.add(copyButton, BorderLayout.EAST)
        return row
    }

    private fun buildLink(url: String, label: String): JComponent {
        val lbl = JBLabel("<html><a href='$url'>$label</a></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    try {
                        Desktop.getDesktop().browse(java.net.URI(url))
                    } catch (_: Exception) {
                    }
                }
            })
        }
        return lbl
    }
}
