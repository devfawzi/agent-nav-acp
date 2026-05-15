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
/**
 * Callback invoqué par les cards interactives (ExitPlanMode, AskUserQuestion).
 *  - `toolUseId` : identifiant du tool_use claude attend une réponse pour
 *  - `replyText` : texte du tool_result à renvoyer
 *  - `switchModeTo` : si non-null, mode permission à activer avant la réponse
 *    (typiquement "acceptEdits" après Approve d'un plan).
 */
typealias InteractiveReplyHandler = (toolUseId: String, replyText: String, switchModeTo: String?) -> Unit

class ChatPanel(
    private val project: Project? = null,
    private val interactiveReplyHandler: InteractiveReplyHandler? = null
) {

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

        // ExitPlanMode : claude présente son plan et attend l'approbation user. Card dédiée.
        if (info.planContent != null && info.toolCallId != null && interactiveReplyHandler != null) {
            finalizePending()
            addMessage(ExitPlanModeCard(
                project,
                info.toolCallId,
                info.planContent,
                mdParser,
                mdRenderer,
                interactiveReplyHandler
            ))
            return
        }
        // AskUserQuestion : claude pose des questions structurées (radio/multi-select).
        if (info.userQuestionsJson != null && info.toolCallId != null && interactiveReplyHandler != null) {
            finalizePending()
            addMessage(AskUserQuestionCard(
                info.toolCallId,
                info.userQuestionsJson,
                interactiveReplyHandler
            ))
            return
        }

        // RunCommand déjà tracké → on continue à le router là (status=completed → setDone)
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

        // Plan mode : claude n'écrit pas réellement sur disque. On affiche le contenu
        // proposé inline dans le chat (preview cliquable) pour que l'user puisse naviguer.
        val isPlanMode = info.permissionMode == "plan"
        if (isPlanMode && info.status == "in_progress" && info.path != null &&
            project != null && (info.writeContent != null || info.editNewString != null)) {
            finalizePending()
            addMessage(PlanPreviewCard(project, info))
            return
        }

        // Skip les events sans info utile : status=completed des Edit/Write/Read renvoie
        // un title="tool"/"edit" générique sans path/command. On l'a déjà affiché au
        // tool_call_update précédent (qui avait le path), donc on ignore le completed.
        val genericTitles = setOf("tool", "edit", "write", "read", "bash", "find", "grep", "glob")
        val isUseless = info.path == null && info.command == null &&
            info.title.lowercase() in genericTitles
        if (isUseless) return

        // Tool call standard avec info utile (title spécifique ou path présent)
        if (currentToolBlock == null) {
            currentToolBlock = ToolCallsBlock()
            addMessage(currentToolBlock!!)
        }
        // Affiche le tool name + détail principal : path fichier OU pattern/url/description.
        val secondary = info.path?.let { relativizeForDisplay(it) } ?: info.detail
        currentToolBlock!!.addToolCall(info.title, secondary, info.path)
    }

    private fun relativizeForDisplay(path: String): String {
        val base = project?.basePath
        return if (base != null && path.startsWith(base)) {
            path.substring(base.length).trimStart('/')
        } else path
    }

    fun appendFileChange(change: PendingChangesService.PendingChange) {
        if (project == null) return
        finalizePending()
        addMessage(FileChangeCard(project, change))
    }

    /** Affiche un dialog inline Allow/Deny pour une permission request claude (Bash, MCP, etc.). */
    fun appendPermissionRequest(req: ClaudeACPService.PermissionRequest) {
        finalizePending()
        addMessage(PermissionRequestCard(req))
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

        toggle = JButton("🔧 Tools (0) ▾").apply {
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
        list.isVisible = true  // ouvert par défaut pour voir le fichier/commande sur lequel claude opère
    }

    fun addToolCall(title: String, secondary: String? = null, filePath: String? = null) {
        count++
        val icon: Icon = if (filePath != null) {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(File(filePath).name)
            fileType.icon ?: AllIcons.FileTypes.Any_type
        } else {
            AllIcons.Actions.Lightning
        }
        val labelHtml = if (secondary != null && secondary.isNotBlank()) {
            val truncated = if (secondary.length > 100) secondary.take(97) + "…" else secondary
            val escaped = truncated.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            "<html><b>$title</b> <span style='color:gray'>$escaped</span></html>"
        } else {
            "<html><b>$title</b></html>"
        }
        list.add(JLabel(labelHtml, icon, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor.foreground()
            border = JBUI.Borders.empty(2, 12)
            iconTextGap = 6
            toolTipText = filePath ?: secondary
        })
        list.revalidate()
        list.repaint()
        updateLabel()
    }

    private fun updateLabel() {
        toggle.text = if (list.isVisible) "🔧 Tools ($count) ▾" else "🔧 Tools ($count) ▸ click to expand"
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
    private val cardBg = JBColor(Color(0xeeeeee), Color(0x2a2d31))  // gris discret light/dark

    init {
        background = cardBg
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

        // Auto-freeze quand le change disparaît du service (= Accept All / Reject All globaux)
        service.addListener {
            javax.swing.SwingUtilities.invokeLater {
                if (service.get(change.path) == null && acceptBtn.isEnabled) {
                    freeze("processed")
                }
            }
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = cardBg
            add(statusLabel)
            add(viewBtn)
            add(acceptBtn)
            add(rejectBtn)
        }

        val main = JPanel(BorderLayout()).apply {
            background = cardBg
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

    private val cardBg = JBColor(Color(0xeeeeee), Color(0x2a2d31))

    private val commandArea = JTextArea(initialCommand).apply {
        isEditable = false
        isFocusable = true
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        foreground = JBColor.foreground()
        background = cardBg
        border = null
    }

    private val statusLabel = JLabel("⏳ Running").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor(Color(0xc89c00), Color(0xffb74d))
        border = JBUI.Borders.empty(0, 8, 0, 0)
        verticalAlignment = SwingConstants.TOP
    }

    init {
        background = cardBg
        border = RoundedBorder(JBColor.border(), 8)
        alignmentX = Component.LEFT_ALIGNMENT

        val icon = JLabel(AllIcons.Debugger.Console).apply {
            border = JBUI.Borders.emptyRight(6)
            verticalAlignment = SwingConstants.TOP
        }

        val main = JPanel(BorderLayout()).apply {
            background = cardBg
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

/**
 * Card affichée en mode "plan" quand claude propose une modif de fichier mais ne l'écrit
 * pas sur disque. Affiche le path + un bouton pour ouvrir le contenu proposé dans un
 * éditeur virtuel scratch (l'user peut le copier/coller s'il veut l'appliquer).
 */
private class PlanPreviewCard(
    project: Project,
    private val info: ClaudeACPService.ToolCallInfo
) : JPanel(BorderLayout()) {

    private val cardBg = JBColor(Color(0xfff8e7), Color(0x3a3520))  // jaune pâle : "proposé"

    init {
        background = cardBg
        border = RoundedBorder(JBColor(Color(0xc89c00), Color(0xffb74d)), 8)
        alignmentX = Component.LEFT_ALIGNMENT

        val path = info.path ?: "(unknown)"
        val displayName = run {
            val base = project.basePath
            if (base != null && path.startsWith(base)) path.substring(base.length).trimStart('/')
            else java.io.File(path).name
        }

        val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            .getFileTypeByFileName(java.io.File(path).name)
        val icon = fileType.icon ?: com.intellij.icons.AllIcons.FileTypes.Any_type

        val isWrite = info.writeContent != null
        val previewBody = info.writeContent
            ?: buildString {
                appendLine("--- old")
                appendLine(info.editOldString ?: "")
                appendLine("+++ new")
                append(info.editNewString ?: "")
            }
        val lines = previewBody.count { it == '\n' } + 1

        val titleHtml = if (isWrite) {
            "<html>📋 <b>Plan: create</b> $displayName  <span style='color:gray'>($lines lines)</span></html>"
        } else {
            "<html>📋 <b>Plan: edit</b> $displayName</html>"
        }
        val label = JLabel(titleHtml, icon, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            iconTextGap = 6
            toolTipText = path
        }

        val openBtn = JButton("Open preview").apply {
            margin = JBUI.insets(2, 8)
            addActionListener { openInEditor(project, displayName, previewBody, fileType) }
        }

        val main = JPanel(BorderLayout()).apply {
            background = cardBg
            border = JBUI.Borders.empty(4, 10)
            add(label, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                background = cardBg
                add(openBtn)
            }, BorderLayout.EAST)
        }
        add(main, BorderLayout.CENTER)
    }

    private fun openInEditor(
        project: Project,
        name: String,
        body: String,
        fileType: com.intellij.openapi.fileTypes.FileType
    ) {
        // Light virtual file = fichier en mémoire ouvrable dans un onglet IntelliJ sans
        // toucher au disque. L'user peut lire, copier, ou save manuellement (Ctrl+S).
        val lightFile = com.intellij.testFramework.LightVirtualFile(
            "[plan] $name",
            fileType,
            body
        )
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .openFile(lightFile, true)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * Card affichant le plan que claude propose en fin de mode plan (tool ExitPlanMode).
 * Boutons :
 *  - Approve & execute : switch en acceptEdits + tool_result "User approved"
 *  - Reject : envoie tool_result "User rejected" (claude restera en plan, prêt à réviser)
 *
 * Une fois cliqué, les boutons sont disabled (un seul reply possible) et la card affiche
 * le verdict pour conserver la trace dans le scroll.
 */
private class ExitPlanModeCard(
    private val project: Project?,
    private val toolUseId: String,
    private val planMarkdown: String,
    parser: Parser,
    renderer: HtmlRenderer,
    private val replyHandler: InteractiveReplyHandler
) : JPanel(BorderLayout()) {

    private val cardBg = JBColor(Color(0xfff8e7), Color(0x3a3520))
    private val borderColor = JBColor(Color(0xc89c00), Color(0xffb74d))
    private val statusLabel = JLabel("").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(2, 8, 0, 0)
    }
    private val approveBtn = JButton("✅ Approve & execute")
    private val rejectBtn = JButton("✗ Reject")

    init {
        background = cardBg
        border = RoundedBorder(borderColor, 8)
        alignmentX = Component.LEFT_ALIGNMENT
        layout = BorderLayout()

        val title = JLabel("<html>📋 <b>Plan ready — review &amp; approve</b></html>").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            border = JBUI.Borders.empty(6, 10, 2, 10)
        }

        val planHtml = renderer.render(parser.parse(planMarkdown))
        val pane = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            text = "<html><body style='font-family:sans-serif; font-size:11px;'>$planHtml</body></html>"
            background = cardBg
            foreground = JBColor.foreground()
            border = JBUI.Borders.empty(0, 12, 4, 12)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }

        approveBtn.margin = JBUI.insets(2, 8)
        rejectBtn.margin = JBUI.insets(2, 8)
        approveBtn.addActionListener {
            lockButtons("✓ Approved — switching to acceptEdits and proceeding…")
            // Switch mode AVANT la réponse pour que claude exécute vraiment les Write/Edit.
            replyHandler(
                toolUseId,
                "User approved the plan. Proceed with the implementation.",
                "acceptEdits"
            )
        }
        rejectBtn.addActionListener {
            lockButtons("✗ Rejected — claude stays in plan mode")
            replyHandler(
                toolUseId,
                "User rejected the plan. Please revise it or wait for new instructions.",
                null
            )
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = cardBg
            border = JBUI.Borders.empty(2, 10, 8, 10)
            add(approveBtn)
            add(rejectBtn)
            add(statusLabel)
        }

        val center = JPanel(BorderLayout()).apply {
            background = cardBg
            add(title, BorderLayout.NORTH)
            add(pane, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        add(center, BorderLayout.CENTER)
    }

    private fun lockButtons(message: String) {
        approveBtn.isEnabled = false
        rejectBtn.isEnabled = false
        statusLabel.text = message
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * Card affichant les questions structurées posées par claude (tool AskUserQuestion).
 * Chaque question a un titre + des options (single ou multi-select). Submit envoie un
 * tool_result texte décrivant les choix de l'user.
 */
private class AskUserQuestionCard(
    private val toolUseId: String,
    questionsJson: String,
    private val replyHandler: InteractiveReplyHandler
) : JPanel(BorderLayout()) {

    private val cardBg = JBColor(Color(0xeaf3ff), Color(0x1f2d3d))
    private val borderColor = JBColor(Color(0x5b89d9), Color(0x4a6fa5))
    private val statusLabel = JLabel("").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(2, 8, 0, 0)
    }
    private val submitBtn = JButton("Submit answers")
    private val skipBtn = JButton("Skip")
    private val questionGroups = mutableListOf<QuestionGroup>()

    private data class QuestionGroup(
        val question: String,
        val header: String?,
        val multiSelect: Boolean,
        val options: List<OptionEntry>,
        /** Pour single-select : tous radios partagent un ButtonGroup. Pour multi : null. */
        val buttonGroup: ButtonGroup?
    )

    private data class OptionEntry(val label: String, val description: String?, val button: JToggleButton)

    init {
        background = cardBg
        border = RoundedBorder(borderColor, 8)
        alignmentX = Component.LEFT_ALIGNMENT
        layout = BorderLayout()

        val title = JLabel("<html>❓ <b>Claude has a few questions</b></html>").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            border = JBUI.Borders.empty(6, 10, 4, 10)
        }

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = cardBg
            border = JBUI.Borders.empty(0, 12, 4, 12)
        }

        try {
            val arr = com.google.gson.JsonParser.parseString(questionsJson).asJsonArray
            arr.forEachIndexed { qIdx, qEl ->
                if (!qEl.isJsonObject) return@forEachIndexed
                val q = qEl.asJsonObject
                val questionText = q.get("question")?.asString ?: "Question ${qIdx + 1}"
                val header = q.get("header")?.asString
                val multi = q.get("multiSelect")?.asBoolean == true
                val opts = q.getAsJsonArray("options") ?: com.google.gson.JsonArray()

                if (qIdx > 0) body.add(Box.createVerticalStrut(8))
                if (!header.isNullOrBlank()) {
                    body.add(JLabel("<html><b>$header</b></html>").apply {
                        font = font.deriveFont(Font.PLAIN, 11f)
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                }
                body.add(JLabel("<html><div style='width:520px;'>$questionText</div></html>").apply {
                    font = font.deriveFont(Font.PLAIN, 12f)
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.empty(2, 0, 4, 0)
                })

                val group = if (!multi) ButtonGroup() else null
                val entries = mutableListOf<OptionEntry>()
                opts.forEachIndexed { oIdx, oEl ->
                    if (!oEl.isJsonObject) return@forEachIndexed
                    val o = oEl.asJsonObject
                    val label = o.get("label")?.asString ?: "Option ${oIdx + 1}"
                    val desc = o.get("description")?.asString
                    val labelHtml = if (desc.isNullOrBlank()) label
                    else "<html><b>$label</b><br><span style='color:gray;font-size:10px;'>$desc</span></html>"
                    val btn: JToggleButton = if (multi) {
                        JCheckBox(labelHtml).apply {
                            isOpaque = false
                            alignmentX = Component.LEFT_ALIGNMENT
                            border = JBUI.Borders.empty(1, 4)
                            if (oIdx == 0) isSelected = false
                        }
                    } else {
                        JRadioButton(labelHtml).apply {
                            isOpaque = false
                            alignmentX = Component.LEFT_ALIGNMENT
                            border = JBUI.Borders.empty(1, 4)
                            if (oIdx == 0) isSelected = true
                            group?.add(this)
                        }
                    }
                    body.add(btn)
                    entries.add(OptionEntry(label, desc, btn))
                }
                questionGroups.add(QuestionGroup(questionText, header, multi, entries, group))
            }
        } catch (e: Exception) {
            body.add(JLabel("<html><span style='color:red;'>Failed to parse questions: ${e.message}</span></html>"))
        }

        submitBtn.margin = JBUI.insets(2, 8)
        skipBtn.margin = JBUI.insets(2, 8)
        submitBtn.addActionListener { submit() }
        skipBtn.addActionListener {
            lockButtons("✗ Skipped")
            replyHandler(
                toolUseId,
                "User declined to answer the questions. Proceed with reasonable defaults.",
                null
            )
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = cardBg
            border = JBUI.Borders.empty(2, 10, 8, 10)
            add(submitBtn)
            add(skipBtn)
            add(statusLabel)
        }

        val center = JPanel(BorderLayout()).apply {
            background = cardBg
            add(title, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        add(center, BorderLayout.CENTER)
    }

    private fun submit() {
        val sb = StringBuilder("User answered:\n")
        questionGroups.forEachIndexed { qIdx, qg ->
            val picked = qg.options.filter { it.button.isSelected }.map { it.label }
            sb.append("Q${qIdx + 1} (")
            sb.append(qg.header ?: qg.question.take(60))
            sb.append("): ")
            sb.append(if (picked.isEmpty()) "(no choice)" else picked.joinToString(", "))
            sb.append('\n')
        }
        lockButtons("✓ Submitted")
        replyHandler(toolUseId, sb.toString().trimEnd(), null)
    }

    private fun lockButtons(message: String) {
        submitBtn.isEnabled = false
        skipBtn.isEnabled = false
        questionGroups.flatMap { it.options }.forEach { it.button.isEnabled = false }
        statusLabel.text = message
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * Card affichée quand claude demande l'autorisation d'utiliser un tool (Bash, MCP, etc.)
 * via le `sdk_control_request` subtype:"permission" (activé par --permission-prompt-tool stdio).
 *
 * Boutons Allow / Deny. Quand l'user choisit, on appelle le respondAllow/respondDeny du
 * service qui renvoie un control_response à claude pour débloquer ou refuser le tool.
 */
private class PermissionRequestCard(
    private val req: ClaudeACPService.PermissionRequest
) : JPanel(BorderLayout()) {

    private val cardBg = JBColor(Color(0xfff4d6), Color(0x3a2f1a))
    private val borderColor = JBColor(Color(0xd97706), Color(0xfbbf24))
    private val statusLabel = JLabel("").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(2, 8, 0, 0)
    }
    private val allowBtn = JButton("✅ Allow")
    private val denyBtn = JButton("✗ Deny")
    @Volatile private var done = false

    init {
        background = cardBg
        border = RoundedBorder(borderColor, 8)
        alignmentX = Component.LEFT_ALIGNMENT

        val toolPreview = buildPreview(req.toolName, req.toolInput)
        val title = JLabel(
            "<html>🔐 <b>Claude wants to use <code>${req.toolName}</code></b></html>"
        ).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            border = JBUI.Borders.empty(6, 10, 2, 10)
        }
        val previewArea = JTextArea(toolPreview).apply {
            isEditable = false
            isFocusable = true
            lineWrap = true
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            foreground = JBColor.foreground()
            background = cardBg
            border = JBUI.Borders.empty(0, 12, 4, 12)
        }

        allowBtn.margin = JBUI.insets(2, 8)
        denyBtn.margin = JBUI.insets(2, 8)
        allowBtn.addActionListener {
            if (done) return@addActionListener
            done = true
            lockButtons("✓ Allowed")
            req.respondAllow()
        }
        denyBtn.addActionListener {
            if (done) return@addActionListener
            done = true
            lockButtons("✗ Denied")
            req.respondDeny("Denied by user")
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = cardBg
            border = JBUI.Borders.empty(2, 10, 8, 10)
            add(allowBtn)
            add(denyBtn)
            add(statusLabel)
        }

        val center = JPanel(BorderLayout()).apply {
            background = cardBg
            add(title, BorderLayout.NORTH)
            add(previewArea, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        add(center, BorderLayout.CENTER)
    }

    private fun buildPreview(toolName: String, rawInput: String?): String {
        if (rawInput.isNullOrBlank()) return ""
        return try {
            val obj = com.google.gson.JsonParser.parseString(rawInput).asJsonObject
            when (toolName) {
                "Bash" -> obj.get("command")?.asString ?: rawInput
                "Read", "Edit", "Write", "MultiEdit" ->
                    obj.get("file_path")?.asString
                        ?: obj.get("path")?.asString
                        ?: rawInput
                else -> obj.entrySet().joinToString("\n") { "${it.key}: ${it.value}" }
            }
        } catch (_: Exception) {
            rawInput
        }
    }

    private fun lockButtons(message: String) {
        allowBtn.isEnabled = false
        denyBtn.isEnabled = false
        statusLabel.text = message
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
