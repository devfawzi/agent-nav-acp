package com.claudeacp

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.AbstractBorder

/**
 * Chat avec :
 * - Bulles utilisateur (alignées droite, fond bleu)
 * - Réponse assistant directe sans container, rendue en Markdown
 * - Bloc Thinking repliable
 * - Bloc Tool calls compact
 * - Card "fichier modifié" avec View/Accept/Reject + lignes +/-
 * - Pas de scroll horizontal (wrap dynamique à la largeur du viewport)
 */
class ChatPanel(private val project: Project? = null) {

    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        alignmentX = Component.LEFT_ALIGNMENT
    }

    // Container avec messages à NORTH : empêche le viewport scrollPane de centrer verticalement
    private val scrollContainer = JPanel(BorderLayout()).apply {
        background = UIUtil.getPanelBackground()
        add(messagesPanel, BorderLayout.NORTH)
    }

    private val scrollPane = JBScrollPane(scrollContainer).apply {
        border = null
        verticalScrollBar.unitIncrement = 16
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        viewport.background = UIUtil.getPanelBackground()
    }

    private var currentAssistantMessage: AssistantMessage? = null
    private var currentThinkingBlock: ThinkingBlock? = null
    private var currentToolBlock: ToolCallsBlock? = null
    private val runCommandBlocks = mutableMapOf<String, RunCommandBlock>()

    private val mdParser: Parser = Parser.builder().build()
    private val mdRenderer: HtmlRenderer = HtmlRenderer.builder().build()

    fun getContent(): JComponent = scrollPane

    fun clear() {
        messagesPanel.removeAll()
        currentAssistantMessage = null
        currentThinkingBlock = null
        currentToolBlock = null
        runCommandBlocks.clear()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    fun appendUserMessage(text: String) {
        finalizePending()
        addMessage(UserMessage(text))
    }

    fun appendAssistantChunk(text: String) {
        currentThinkingBlock = null
        currentToolBlock = null
        if (currentAssistantMessage == null) {
            currentAssistantMessage = AssistantMessage(mdParser, mdRenderer)
            addMessage(currentAssistantMessage!!)
        }
        currentAssistantMessage!!.appendText(text)
    }

    fun appendThinkingChunk(text: String) {
        currentAssistantMessage = null
        currentToolBlock = null
        if (currentThinkingBlock == null) {
            currentThinkingBlock = ThinkingBlock()
            addMessage(currentThinkingBlock!!)
        }
        currentThinkingBlock!!.appendText(text)
    }

    fun appendToolCall(info: ClaudeACPService.ToolCallInfo) {
        currentAssistantMessage = null
        currentThinkingBlock = null

        // Si on tracke déjà ce toolCallId comme RunCommand, on continue à le router là
        // (le status=completed n'a plus de kind ni command, donc on ne pourrait pas re-détecter)
        if (info.toolCallId != null && runCommandBlocks.containsKey(info.toolCallId)) {
            val block = runCommandBlocks[info.toolCallId] ?: return
            if (info.command != null) block.setCommand(info.command)
            if (info.status == "completed") block.setDone()
            return
        }

        val isCommand = (info.kind?.lowercase() == "execute") ||
            info.command != null ||
            info.title.startsWith("Terminal", ignoreCase = true)

        if (isCommand && info.toolCallId != null) {
            val block = RunCommandBlock(info.command ?: info.title)
            runCommandBlocks[info.toolCallId] = block
            currentToolBlock = null
            addMessage(block)
            if (info.command != null) block.setCommand(info.command)
            if (info.status == "completed") block.setDone()
            return
        }

        // Tool call standard (file ops, search, etc.)
        if (currentToolBlock == null) {
            currentToolBlock = ToolCallsBlock()
            addMessage(currentToolBlock!!)
        }
        currentToolBlock!!.addToolCall(info.title, info.path)
    }

    fun appendFileChange(change: PendingChangesService.PendingChange) {
        if (project == null) return
        finalizePending()
        addMessage(FileChangeCard(project, change))
    }

    fun appendInfo(text: String) {
        finalizePending()
        addMessage(InfoMessage(text, JBColor.GRAY))
    }

    fun appendError(text: String) {
        finalizePending()
        addMessage(InfoMessage("❌ $text", JBColor.RED))
    }

    fun appendStderr(text: String) {
        finalizePending()
        addMessage(InfoMessage("⚠️ $text", JBColor.ORANGE))
    }

    private fun finalizePending() {
        currentAssistantMessage = null
        currentThinkingBlock = null
        currentToolBlock = null
    }

    private fun addMessage(msg: JComponent) {
        msg.alignmentX = Component.LEFT_ALIGNMENT
        messagesPanel.add(msg)
        messagesPanel.add(Box.createVerticalStrut(2))
        messagesPanel.revalidate()
        messagesPanel.repaint()
        ApplicationManager.getApplication().invokeLater {
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
    }
}

// ── Messages ──────────────────────────────────────────────────────────────────

private class UserMessage(text: String) : JPanel(BorderLayout()) {
    init {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(2, 0)

        val bubbleColor = JBColor(Color(0x2c5282), Color(0x1e3a5f))

        // Bulle : override getMaximumSize pour empêcher BoxLayout vertical de l'étirer.
        val bubble = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension {
                val pref = preferredSize
                return Dimension(pref.width, pref.height)
            }
        }.apply {
            background = bubbleColor
            border = RoundedBorder(bubbleColor, 10)
        }

        val area = JTextArea(text).apply {
            isEditable = false
            isFocusable = true
            lineWrap = true
            wrapStyleWord = true
            columns = 40  // hint largeur ~40 chars, wrap au-delà
            background = bubbleColor
            foreground = Color.WHITE
            caretColor = Color.WHITE
            selectionColor = Color(0x4a6fa5)
            border = JBUI.Borders.empty(6, 10)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        }
        bubble.add(area, BorderLayout.CENTER)

        // Wrapper qui aligne la bulle à droite ; sa hauteur = celle de la bulle (pas étirée)
        val wrapper = object : JPanel() {
            override fun getMaximumSize(): Dimension {
                return Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        }.apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = UIUtil.getPanelBackground()
            add(Box.createHorizontalGlue())
            add(bubble)
        }
        add(wrapper, BorderLayout.CENTER)
    }
}

/**
 * Réponse Claude directe (sans bulle, sans header), rendue en Markdown via JEditorPane HTML.
 * Le wrap est automatique à la largeur du viewport.
 */
private class AssistantMessage(
    private val parser: Parser,
    private val renderer: HtmlRenderer
) : JPanel(BorderLayout()) {
    private val buffer = StringBuilder()
    private val pane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        isFocusable = true
        background = UIUtil.getPanelBackground()
        foreground = JBColor.foreground()
        border = JBUI.Borders.empty(0, 2)
        font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }

    init {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(0, 0, 0, 0)
        add(pane, BorderLayout.CENTER)
        render()
    }

    // Empêche BoxLayout vertical du parent d'étirer le message à 1000px de haut
    override fun getMaximumSize(): Dimension {
        return Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    fun appendText(text: String) {
        buffer.append(text)
        render()
    }

    private fun render() {
        val md = buffer.toString()
        val htmlBody = try {
            renderer.render(parser.parse(md))
        } catch (_: Exception) {
            md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        }
        val color = JBColor.foreground()
        val rgb = String.format("#%02x%02x%02x", color.red, color.green, color.blue)

        // Swing HTML parser est très limité : pas de rgba(), pas de border-radius, pas de
        // overflow-x. Garder un CSS minimaliste avec uniquement les propriétés supportées.
        val html = "<html><body style=\"font-family:sans-serif;font-size:11px;color:$rgb;\">$htmlBody</body></html>"

        try {
            pane.contentType = "text/html"
            pane.text = html
            pane.caretPosition = 0
        } catch (e: Exception) {
            // Fallback : afficher en texte brut si le rendering HTML crash
            try {
                pane.contentType = "text/plain"
                pane.text = md
            } catch (_: Exception) {
                // ignore
            }
        }
    }
}

private class ThinkingBlock : JPanel(BorderLayout()) {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    private val area = JTextArea().apply {
        isEditable = false
        isFocusable = true
        lineWrap = true
        wrapStyleWord = true
        columns = 50
        background = UIUtil.getPanelBackground()
        foreground = JBColor.GRAY
        font = Font(Font.SANS_SERIF, Font.ITALIC, 12)
        border = JBUI.Borders.empty(6, 12)
    }
    private val toggle: JButton

    init {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(2, 0)

        toggle = JButton("🧠 Thinking ▸").apply {
            margin = JBUI.insets(2, 6)
            isFocusPainted = false
            isContentAreaFilled = false
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
            addActionListener {
                area.isVisible = !area.isVisible
                text = if (area.isVisible) "🧠 Thinking ▾" else "🧠 Thinking ▸"
            }
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = UIUtil.getPanelBackground()
            add(toggle)
        }
        add(header, BorderLayout.NORTH)
        add(area, BorderLayout.CENTER)
        area.isVisible = false
    }

    fun appendText(text: String) {
        area.append(text)
    }
}

private class ToolCallsBlock : JPanel(BorderLayout()) {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    private val list = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
    }
    private val toggle: JButton
    private var count = 0

    init {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(2, 0)

        toggle = JButton("🔧 Tools (0) ▸").apply {
            margin = JBUI.insets(2, 6)
            isFocusPainted = false
            isContentAreaFilled = false
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
            addActionListener {
                list.isVisible = !list.isVisible
                updateLabel()
            }
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = UIUtil.getPanelBackground()
            add(toggle)
        }
        add(header, BorderLayout.NORTH)
        add(list, BorderLayout.CENTER)
        list.isVisible = false // fermé par défaut
    }

    fun addToolCall(title: String, path: String? = null) {
        count++
        val icon: Icon = if (path != null) {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(File(path).name)
            fileType.icon ?: AllIcons.FileTypes.Any_type
        } else {
            AllIcons.Actions.Lightning
        }
        list.add(JLabel(title, icon, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor.foreground()
            border = JBUI.Borders.empty(2, 12)
            iconTextGap = 6
        })
        list.revalidate()
        list.repaint()
        updateLabel()
    }

    private fun updateLabel() {
        toggle.text = if (list.isVisible) "🔧 Tools ($count) ▾" else "🔧 Tools ($count) ▸"
    }
}

/**
 * Card affichée dans le chat quand l'agent modifie un fichier.
 * Affiche : nom du fichier, lignes +/-, et boutons View/Accept/Reject.
 * Boutons synchronisés avec le PendingChangesService.
 */
private class FileChangeCard(
    project: Project,
    private val change: PendingChangesService.PendingChange
) : JPanel(BorderLayout()) {

    private val service = project.getService(PendingChangesService::class.java)
    private val diffManager = project.getService(DiffViewerManager::class.java)

    init {
        background = UIUtil.getTextFieldBackground()
        border = RoundedBorder(JBColor.border(), 8)
        alignmentX = Component.LEFT_ALIGNMENT

        val displayName = relativizePath(project, change.path)
        val added = countAddedLines(change.before, change.lastSnapshotAfter)
        val removed = countRemovedLines(change.before, change.lastSnapshotAfter)

        // Icône native IntelliJ pour le type de fichier
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(File(change.path).name)
        val fileIcon = fileType.icon ?: AllIcons.FileTypes.Any_type

        val label = JLabel(
            "<html><b>$displayName</b>  " +
                "<span style='color:#4caf50'>+$added</span>  " +
                "<span style='color:#e53935'>−$removed</span></html>",
            fileIcon,
            SwingConstants.LEFT
        ).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            iconTextGap = 6
        }

        val viewBtn = JButton("View").apply {
            margin = JBUI.insets(2, 8)
        }
        val acceptBtn = JButton("✓").apply {
            toolTipText = "Accept"
            margin = JBUI.insets(2, 6)
        }
        val rejectBtn = JButton("↩").apply {
            toolTipText = "Reject"
            margin = JBUI.insets(2, 6)
        }
        val statusLabel = JLabel("").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, 11f)
            border = JBUI.Borders.emptyRight(6)
        }

        fun freeze(action: String) {
            viewBtn.isEnabled = false
            acceptBtn.isEnabled = false
            rejectBtn.isEnabled = false
            statusLabel.text = action
        }

        viewBtn.addActionListener { diffManager.showDiffForFile(change.path) }
        acceptBtn.addActionListener {
            service.accept(change.path)
            freeze("accepted")
        }
        rejectBtn.addActionListener {
            service.reject(change.path)
            freeze("rejected")
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = UIUtil.getTextFieldBackground()
            add(statusLabel)
            add(viewBtn)
            add(acceptBtn)
            add(rejectBtn)
        }

        val main = JPanel(BorderLayout()).apply {
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.empty(4, 10)
            add(label, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }

        add(main, BorderLayout.CENTER)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private fun relativizePath(project: Project, path: String): String {
        val base = project.basePath
        return if (base != null && path.startsWith(base)) {
            path.substring(base.length).trimStart('/')
        } else File(path).name
    }

    private fun countAddedLines(before: String, after: String): Int {
        val beforeLines = before.split("\n").toSet()
        return after.split("\n").count { it.isNotEmpty() && it !in beforeLines }
    }

    private fun countRemovedLines(before: String, after: String): Int {
        val afterLines = after.split("\n").toSet()
        return before.split("\n").count { it.isNotEmpty() && it !in afterLines }
    }
}

/**
 * Bloc dédié à une commande Bash/Terminal.
 * Affiche : icône terminal + commande + statut (Running / Done).
 */
private class RunCommandBlock(initialCommand: String) : JPanel(BorderLayout()) {

    private val commandArea = JTextArea(initialCommand).apply {
        isEditable = false
        isFocusable = true
        lineWrap = true
        wrapStyleWord = false  // wrap au char (commandes shell, pas du langage naturel)
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        foreground = JBColor.foreground()
        background = UIUtil.getTextFieldBackground()
        border = null
    }

    private val statusLabel = JLabel("⏳ Running").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor(Color(0xc89c00), Color(0xffb74d))
        border = JBUI.Borders.empty(0, 8, 0, 0)
        verticalAlignment = SwingConstants.TOP
    }

    init {
        background = UIUtil.getTextFieldBackground()
        border = RoundedBorder(JBColor.border(), 8)
        alignmentX = Component.LEFT_ALIGNMENT

        val icon = JLabel(AllIcons.Debugger.Console).apply {
            border = JBUI.Borders.emptyRight(6)
            verticalAlignment = SwingConstants.TOP
        }

        val main = JPanel(BorderLayout()).apply {
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.empty(6, 10)
            add(icon, BorderLayout.WEST)
            add(commandArea, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.EAST)
        }

        add(main, BorderLayout.CENTER)
    }

    fun setCommand(command: String) {
        commandArea.text = command
    }

    fun setDone() {
        statusLabel.text = "✓ Done"
        statusLabel.foreground = JBColor(Color(0x2e7d32), Color(0x81c784))
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private class InfoMessage(text: String, color: Color) : JPanel(BorderLayout()) {
    init {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(2, 6)
        add(JLabel(text).apply {
            foreground = color
            font = font.deriveFont(Font.ITALIC, 11f)
        }, BorderLayout.WEST)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private class RoundedBorder(private val color: Color, private val radius: Int) : AbstractBorder() {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
    }

    override fun getBorderInsets(c: Component): Insets = Insets(4, 4, 4, 4)
    override fun isBorderOpaque(): Boolean = false
}
