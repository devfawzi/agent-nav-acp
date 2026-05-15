package com.claudeacp

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Toolkit
import java.awt.datatransfer.Transferable
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.AbstractBorder

/**
 * Input du prompt avec :
 *  - Textarea principal
 *  - Footer : bouton 📎 attach + Mode/Model/Effort + Send/Stop
 *  - Chips d'attachments au-dessus du textarea
 *  - @ pour autocomplete fichiers/dossiers du projet
 *  - Paste image (Ctrl/Cmd+V)
 *  - Drag & drop de fichiers
 */
class PromptInputPanel(
    private val project: Project,
    private val getMySessionId: () -> String?
) {

    private val log = thisLogger()
    private val acpService = project.getService(ClaudeACPService::class.java)

    private val textArea = JTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        border = JBUI.Borders.empty(8, 10)
        background = UIUtil.getTextFieldBackground()
    }

    // Cached config — utilisée pour résoudre les slash commands au moment du send.
    @Volatile
    private var currentConfig: ClaudeACPService.SessionConfig = ClaudeACPService.SessionConfig()

    /** Label discret à droite du textarea pour rappeler que `/` ouvre les commandes. */
    private val slashHintLabel = JLabel("type / for commands").apply {
        font = font.deriveFont(Font.ITALIC, 10f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.emptyRight(6)
    }

    private val attachButton = JButton(AllIcons.General.Add).apply {
        toolTipText = "Attach files or images"
        margin = JBUI.emptyInsets()
        preferredSize = Dimension(28, 28)
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
    }

    private val agentButton = JButton("Agent ▾").apply {
        margin = JBUI.insets(2, 6)
        isFocusPainted = false
        font = font.deriveFont(Font.PLAIN, 11f)
        toolTipText = "Switch ACP agent"
    }

    private val sendButton = JButton("➤").apply {
        toolTipText = "Send (Enter — Shift+Enter for new line)"
        margin = JBUI.emptyInsets()
        preferredSize = Dimension(28, 28)
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
        font = font.deriveFont(Font.BOLD, 14f)
    }

    private val attachmentsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
        background = UIUtil.getTextFieldBackground()
        isVisible = false
    }

    @Volatile
    private var isExecuting = false

    /** Une fois true, le bouton agent affiche un cadenas et ne s'ouvre plus.
     *  Set par le panel parent au 1er prompt envoyé. */
    @Volatile
    private var agentLocked = false

    private val attachments = mutableListOf<PromptAttachment>()

    private var onSend: ((String, List<PromptAttachment>) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var onSlashCommand: ((cmd: String, args: String) -> Unit)? = null

    /** Slash commands interceptés par le plugin (ne sont PAS envoyés à claude). */
    private val PLUGIN_SLASH_COMMANDS = setOf("mode", "model", "effort", "skill", "skills", "mcp")

    private val fileMentionPopup = FileMentionPopup(project, textArea) { entry ->
        replaceMentionToken(entry)
    }

    private val slashPopup: SlashCommandPopup = SlashCommandPopup(textArea) { picked ->
        onSlashPopupPick(picked)
    }

    /**
     * Quand l'user choisit une slash command dans le popup `/`, le comportement dépend du type :
     *  - Commande PLUGIN (mode/model/effort/skill/mcp) → déclenche directement la SlashPickerCard
     *    interactive dans le chat. Pas besoin d'Enter. L'user voit immédiatement les options.
     *  - Commande claude (/init, /review, /security-review, skills user) → insère `/<cmd> ` dans
     *    le textarea pour que l'user ajoute des args si besoin et envoie via Enter.
     */
    private fun onSlashPopupPick(picked: String) {
        textArea.text = ""  // vide le textarea (qui contenait `/<filter>`)
        if (picked.lowercase() in PLUGIN_SLASH_COMMANDS) {
            onSlashCommand?.invoke(picked.lowercase(), "")
            textArea.requestFocusInWindow()
        } else {
            // Commande claude : insère et focus pour que user complete + Send
            val insertion = "/$picked "
            textArea.text = insertion
            textArea.caretPosition = insertion.length
            textArea.requestFocusInWindow()
        }
    }

    fun getContent(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                RoundedBorder(JBColor.border(), 10),
                JBUI.Borders.empty(4)
            )
        }

        setupTextAreaKeys()
        setupShiftEnterAction()  // force Shift+Enter = newline (sinon IntelliJ keymap intercepte)
        setupClipboardPaste()
        setupSmartPasteAction()  // intercepte Ctrl/Cmd+V au niveau IntelliJ Action System
        setupDragAndDrop(root)

        // Hauteur du textArea bornée pour que le footer (Send/Stop/Model/...) reste TOUJOURS
        // visible même si l'user tape un long prompt. Le scroll vertical s'active dans le
        // textArea au-delà. Sans ce cap, BoxLayout étire le scrollPane et pousse le footer
        // hors écran.
        val scrollText = JScrollPane(
            textArea,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = null
            preferredSize = Dimension(0, 72)
            minimumSize = Dimension(0, 36)
            maximumSize = Dimension(Int.MAX_VALUE, 140)
        }

        val centerStack = object : JPanel() {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getTextFieldBackground()
            add(attachmentsPanel)
            add(scrollText)
        }

        // Footer minimaliste : à gauche [+] [Agent ▾] ; à droite [▶/⏹].
        // Tout le reste (Mode/Model/Effort/Skills/MCP) passe en slash commands dans le chat.
        val leftToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            background = UIUtil.getPanelBackground()
            add(attachButton)
            add(agentButton)
        }
        updateAgentButtonLabel()
        agentButton.addActionListener { showAgentMenu() }

        val rightToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = UIUtil.getPanelBackground()
            add(slashHintLabel)
            add(sendButton)
        }
        val footer = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(leftToolbar, BorderLayout.WEST)
            add(rightToolbar, BorderLayout.EAST)
        }

        attachButton.addActionListener { openFilePicker() }
        sendButton.addActionListener { onSendButtonClick() }

        root.add(centerStack, BorderLayout.CENTER)
        root.add(footer, BorderLayout.SOUTH)

        // La config (Model/Mode/Effort) et l'état d'exécution sont push par le panel parent
        // via refreshConfig() et setExecutingState() — filtrés par sessionId pour éviter le
        // cross-talk entre chats.
        updateButtons(ClaudeACPService.SessionConfig())

        return root
    }

    /** Refresh des dropdowns Model/Mode/Effort pour la config courante de notre session. */
    fun refreshConfig(config: ClaudeACPService.SessionConfig) {
        updateButtons(config)
    }

    fun onSend(handler: (String, List<PromptAttachment>) -> Unit) {
        onSend = handler
    }

    fun onCancel(handler: () -> Unit) {
        onCancel = handler
    }

    /** Callback invoqué pour les slash commands interceptés (/mode /model /effort /skill /mcp). */
    fun onSlashCommand(handler: (cmd: String, args: String) -> Unit) {
        onSlashCommand = handler
    }

    fun setReady(ready: Boolean) {
        sendButton.isEnabled = ready
        textArea.isEnabled = ready
    }

    /** Verrouille le bouton agent (appelé par le panel parent au 1er prompt envoyé). */
    fun lockAgent() {
        agentLocked = true
        updateAgentButtonLabel()
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    private fun setupTextAreaKeys() {
        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Les popups interceptent d'abord (Up/Down/Enter/Tab/Esc)
                if (slashPopup.handleKey(e)) return
                if (fileMentionPopup.handleKey(e)) return

                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    if (!isExecuting) send()
                }
            }

            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) return
                // Skip update si l'event était une touche de navigation des popups — sinon
                // on reclear la liste et la sélection retombe à 0.
                val isNav = e.keyCode in setOf(
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN,
                    KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
                    KeyEvent.VK_ENTER, KeyEvent.VK_TAB
                )
                if (isNav) return
                updateMentionPopup()
                updateSlashPopup()
            }
        })
    }

    /**
     * Affiche le popup slash si le textarea commence par `/` et que le caret est dans le
     * token correspondant. Le filtre = ce qui est après le `/` jusqu'au caret.
     */
    private fun updateSlashPopup() {
        val text = textArea.text
        val pos = textArea.caretPosition
        // Conditions : `/` est en position 0 du texte (slash command de toute le ligne).
        // Si l'user met un espace AVANT le `/`, on ne déclenche pas (probable mention).
        if (!text.startsWith("/")) {
            slashPopup.hide()
            return
        }
        // Si le caret est avant la fin du 1er mot ou juste après
        val tokenEnd = text.indexOf(' ').let { if (it == -1) text.length else it }
        if (pos > tokenEnd) {
            slashPopup.hide()
            return
        }
        val filter = text.substring(1, pos)
        // Refresh la liste des commandes à chaque fois (la config peut avoir changé)
        slashPopup.setEntries(buildSlashEntries())
        slashPopup.anchorIndex = 0
        slashPopup.show(filter)
    }

    private fun buildSlashEntries(): List<SlashCommandPopup.Entry> {
        val plugin = listOf(
            SlashCommandPopup.Entry(
                name = "model",
                description = "Switch Claude model (Opus / Sonnet / Haiku)",
                isPlugin = true,
                submenuProvider = { buildModelSubmenu() }
            ),
            SlashCommandPopup.Entry(
                name = "mode",
                description = "Switch permission mode",
                isPlugin = true,
                submenuProvider = { buildModeSubmenu() }
            ),
            SlashCommandPopup.Entry(
                name = "effort",
                description = "Change extended thinking effort",
                isPlugin = true,
                submenuProvider = { buildEffortSubmenu() }
            ),
            SlashCommandPopup.Entry(
                name = "mcp",
                description = "Browse MCP servers & tools",
                isPlugin = true,
                submenuProvider = { buildMcpSubmenu() }
            ),
            SlashCommandPopup.Entry(
                name = "skill",
                description = "Browse skills & slash commands",
                isPlugin = true,
                submenuProvider = { buildSkillsSubmenu() }
            )
        )
        val skillSet = currentConfig.skills.toSet()
        val claudeCmds = currentConfig.slashCommands.sorted().map { name ->
            val isUserSkill = name in skillSet
            SlashCommandPopup.Entry(
                name = name,
                description = if (isUserSkill) "skill (user)" else "Claude built-in",
                isPlugin = false
            )
        }
        return plugin + claudeCmds
    }

    private fun buildModelSubmenu(): List<SlashCommandPopup.Entry> {
        val sid = getMySessionId()
        return currentConfig.models.map { opt ->
            SlashCommandPopup.Entry(
                name = opt.name,
                description = opt.description ?: opt.id,
                isPlugin = false,
                checked = opt.id == currentConfig.currentModelId,
                onActivate = { acpService.setModel(opt.id, targetSessionId = sid) }
            )
        }
    }

    private fun buildModeSubmenu(): List<SlashCommandPopup.Entry> {
        val sid = getMySessionId()
        return currentConfig.modes.map { opt ->
            SlashCommandPopup.Entry(
                name = opt.name,
                description = opt.description ?: opt.id,
                isPlugin = false,
                checked = opt.id == currentConfig.currentModeId,
                onActivate = { acpService.setMode(opt.id, targetSessionId = sid) }
            )
        }
    }

    private fun buildEffortSubmenu(): List<SlashCommandPopup.Entry> {
        val sid = getMySessionId()
        val effortOpt = currentConfig.configOptions.firstOrNull {
            it.id.lowercase().contains("thought") || it.id.lowercase().contains("effort")
                || it.name.lowercase().contains("effort") || it.name.lowercase().contains("thinking")
        } ?: return listOf(SlashCommandPopup.Entry("(unavailable)", "Not exposed by this agent", false))
        return effortOpt.options.map { opt ->
            SlashCommandPopup.Entry(
                name = opt.name,
                description = opt.description ?: opt.id,
                isPlugin = false,
                checked = opt.id == effortOpt.currentValue,
                onActivate = { acpService.setConfigOption(effortOpt.id, opt.id, targetSessionId = sid) }
            )
        }
    }

    private fun buildMcpSubmenu(): List<SlashCommandPopup.Entry> {
        val out = mutableListOf<SlashCommandPopup.Entry>()
        // On affiche seulement les SERVERS (pas les tools un par un — trop long).
        // Compte les tools par server pour donner l'info en description.
        val toolsByServer: Map<String, Int> = currentConfig.mcpTools
            .mapNotNull { it.removePrefix("mcp__").substringBefore("__").ifEmpty { null } }
            .groupingBy { it }
            .eachCount()

        currentConfig.mcpServers.forEach { srv ->
            val icon = when (srv.status) {
                "connected" -> "🟢"
                "needs-auth" -> "🔑"
                "failed", "error" -> "❌"
                else -> "⚪"
            }
            // claude normalise les noms : "claude.ai Gmail" → "claude_ai_Gmail" pour le tool name.
            val normalized = srv.name.replace(Regex("[^A-Za-z0-9]"), "_")
            val toolCount = toolsByServer[normalized] ?: toolsByServer[srv.name] ?: 0
            out += SlashCommandPopup.Entry(
                name = srv.name,
                description = "${srv.status} · $toolCount tool(s) — click to mention in prompt",
                isPlugin = false,
                icon = icon,
                onActivate = {
                    val text = when (srv.status) {
                        "needs-auth" -> "Authenticate the ${srv.name} MCP server. "
                        else -> "Use the ${srv.name} MCP server to "
                    }
                    insertText(text)
                }
            )
        }
        // Action de bas : ouvrir la Settings MCP pour voir le détail des tools par server
        out += SlashCommandPopup.Entry(
            name = "⚙ Manage MCP servers…",
            description = "Open Settings: list servers + their tools, configure --mcp-config",
            isPlugin = false,
            onActivate = {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, AgentSettingsConfigurable::class.java)
            }
        )
        if (currentConfig.mcpServers.isEmpty()) {
            out.add(0, SlashCommandPopup.Entry(
                name = "(no MCP detected yet)",
                description = "Send a first prompt to let Claude load its MCP config, or open Settings",
                isPlugin = false
            ))
        }
        return out
    }

    private fun buildSkillsSubmenu(): List<SlashCommandPopup.Entry> {
        val skillSet = currentConfig.skills.toSet()
        val items = currentConfig.slashCommands.sorted().map { name ->
            val isUserSkill = name in skillSet
            SlashCommandPopup.Entry(
                name = name,
                description = if (isUserSkill) "user skill" else "Claude built-in",
                isPlugin = false,
                icon = if (isUserSkill) "🧩" else "⚙",
                onActivate = { insertText("/$name ") }
            )
        }
        return items.ifEmpty {
            listOf(SlashCommandPopup.Entry(
                name = "(no skills)",
                description = "Send a first prompt so Claude loads its skill list",
                isPlugin = false
            ))
        }
    }

    private fun updateMentionPopup() {
        val text = textArea.text
        val pos = textArea.caretPosition
        var i = pos - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '@') {
                val token = text.substring(i + 1, pos)
                if (token.contains(' ') || token.contains('\n')) {
                    fileMentionPopup.hide()
                    return
                }
                fileMentionPopup.anchorIndex = i
                fileMentionPopup.show(token)
                return
            }
            if (c == ' ' || c == '\n' || c == '\t') break
            i--
        }
        fileMentionPopup.hide()
    }

    private fun replaceMentionToken(entry: FileMentionPopup.FileEntry) {
        val text = textArea.text
        val pos = textArea.caretPosition
        var atIdx = pos - 1
        while (atIdx >= 0 && text[atIdx] != '@') atIdx--
        if (atIdx < 0) return

        val before = text.substring(0, atIdx)
        val after = text.substring(pos)
        val insert = "@${entry.relativePath} "
        textArea.text = before + insert + after
        textArea.caretPosition = (before + insert).length

        // Ajoute aussi un attachment FileLink (le texte conserve la mention, et un resource_link est joint)
        addAttachment(entryToAttachment(entry))
    }

    private fun entryToAttachment(entry: FileMentionPopup.FileEntry): PromptAttachment.FileLink {
        val mime = FileTypeManager.getInstance()
            .getFileTypeByFileName(File(entry.absolutePath).name).defaultExtension
            .let { if (it.isNotBlank()) "text/x-$it" else null }
        return PromptAttachment.FileLink(
            absolutePath = entry.absolutePath,
            isDirectory = entry.isDirectory,
            displayName = entry.relativePath,
            mimeType = mime,
            icon = entry.icon
        )
    }

    // ── Clipboard paste image (via TransferHandler — plus robuste qu'inputMap) ──

    private fun setupClipboardPaste() {
        textArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                // Accept tout : image, file list, uri-list, text
                return support.dataFlavors.any { fl ->
                    fl == DataFlavor.imageFlavor ||
                        fl == DataFlavor.javaFileListFlavor ||
                        fl == DataFlavor.stringFlavor ||
                        (fl.mimeType?.startsWith("image/") == true) ||
                        (fl.mimeType?.startsWith("text/uri-list") == true)
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun importData(support: TransferSupport): Boolean {
                val tr = support.transferable
                log.info("Paste flavors: " + tr.transferDataFlavors.joinToString { it.mimeType ?: it.toString() })

                try {
                    // 1) Image AWT directe
                    if (tr.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        val img = tr.getTransferData(DataFlavor.imageFlavor) as? Image
                        if (img != null) {
                            addImageFromAwt(img, "pasted-${System.currentTimeMillis() % 100000}.png")
                            return true
                        }
                    }
                    // 2) Fichier(s) dans le clipboard
                    if (tr.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        val files = tr.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        if (!files.isNullOrEmpty()) {
                            files.forEach { addFileAttachment(it) }
                            return true
                        }
                    }
                    // 3) MIME image bytes
                    for (mime in listOf("image/png", "image/jpeg", "image/gif")) {
                        val flavor = runCatching { DataFlavor("$mime;class=java.io.InputStream") }.getOrNull()
                            ?: runCatching { DataFlavor(mime) }.getOrNull() ?: continue
                        if (tr.isDataFlavorSupported(flavor)) {
                            val data = tr.getTransferData(flavor)
                            val bytes: ByteArray? = when (data) {
                                is ByteArray -> data
                                is java.io.InputStream -> data.use { it.readBytes() }
                                else -> null
                            }
                            if (bytes != null && bytes.isNotEmpty()) {
                                val ext = mime.substringAfter("/")
                                addAttachment(PromptAttachment.Image(
                                    displayName = "pasted-${System.currentTimeMillis() % 100000}.$ext",
                                    mimeType = mime,
                                    base64Data = Base64.getEncoder().encodeToString(bytes)
                                ))
                                return true
                            }
                        }
                    }
                    // 4) text/uri-list (paste fichier depuis Files Linux)
                    val uriFlavor = runCatching { DataFlavor("text/uri-list;class=java.lang.String") }.getOrNull()
                    if (uriFlavor != null && tr.isDataFlavorSupported(uriFlavor)) {
                        val text = tr.getTransferData(uriFlavor) as? String
                        if (text != null) {
                            val files = parseUriList(text)
                            if (files.isNotEmpty()) {
                                files.forEach { addFileAttachment(it) }
                                return true
                            }
                        }
                    }
                    // 5) Texte plain : check si ça vient d'une sélection éditeur (Cursor-like)
                    if (tr.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        val s = tr.getTransferData(DataFlavor.stringFlavor) as? String
                        if (s != null) {
                            val looksLikeCode = s.contains('\n') || s.length > 80
                            if (looksLikeCode) {
                                val ref = EditorSelectionGrabber.tryMatchClipboard(project, s)
                                if (ref != null) { addAttachment(ref); return true }
                            }
                            textArea.replaceSelection(s)
                            return true
                        }
                    }
                } catch (e: Exception) {
                    log.warn("Paste failed", e)
                }
                return false
            }
        }
    }

    /**
     * Force Shift+Enter à insérer un newline dans le textArea. Le KeyListener ne suffit pas
     * parce qu'IntelliJ peut router Shift+Enter vers une action globale (StartNewLine etc.)
     * avant qu'on l'attrape, ce qui fait que rien ne se passe dans notre JTextArea.
     */
    private fun setupShiftEnterAction() {
        val newlineAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                textArea.replaceSelection("\n")
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }
        val shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
        newlineAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(shiftEnter, null)),
            textArea
        )
    }

    /**
     * Enregistre une AnAction IntelliJ qui intercepte Ctrl+V / Cmd+V SUR le textArea
     * AVANT le keymap natif de l'IDE. Sans ça, IntelliJ peut router le paste vers une
     * autre action qui ne passe pas par notre TransferHandler.
     */
    private fun setupSmartPasteAction() {
        val pasteAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val cb = try {
                    Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                } catch (_: Exception) { null }
                if (cb == null) {
                    textArea.paste()
                    return
                }
                if (!handleTransferable(cb)) {
                    textArea.paste()
                }
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }
        val ctrlV = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)
        val cmdV = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK)
        pasteAction.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(ctrlV, null),
                KeyboardShortcut(cmdV, null)
            ),
            textArea
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleTransferable(tr: Transferable): Boolean {
        try {
            log.info("Paste flavors: " + tr.transferDataFlavors.joinToString { it.mimeType ?: it.toString() })

            // 1) Image AWT
            if (tr.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val img = tr.getTransferData(DataFlavor.imageFlavor) as? Image
                if (img != null) {
                    addImageFromAwt(img, "pasted-${System.currentTimeMillis() % 100000}.png")
                    return true
                }
            }
            // 2) Fichiers
            if (tr.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                val files = tr.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                if (!files.isNullOrEmpty()) {
                    files.forEach { addFileAttachment(it) }
                    return true
                }
            }
            // 3) MIME image bytes (Linux Wayland / GNOME)
            for (mime in listOf("image/png", "image/jpeg", "image/gif")) {
                val flavor = runCatching { DataFlavor("$mime;class=java.io.InputStream") }.getOrNull()
                    ?: runCatching { DataFlavor(mime) }.getOrNull() ?: continue
                if (tr.isDataFlavorSupported(flavor)) {
                    val data = tr.getTransferData(flavor)
                    val bytes: ByteArray? = when (data) {
                        is ByteArray -> data
                        is java.io.InputStream -> data.use { it.readBytes() }
                        else -> null
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val ext = mime.substringAfter("/")
                        addAttachment(PromptAttachment.Image(
                            displayName = "pasted-${System.currentTimeMillis() % 100000}.$ext",
                            mimeType = mime,
                            base64Data = Base64.getEncoder().encodeToString(bytes)
                        ))
                        return true
                    }
                }
            }
            // 4) text/uri-list
            val uriFlavor = runCatching { DataFlavor("text/uri-list;class=java.lang.String") }.getOrNull()
            if (uriFlavor != null && tr.isDataFlavorSupported(uriFlavor)) {
                val text = tr.getTransferData(uriFlavor) as? String
                if (text != null) {
                    val files = parseUriList(text)
                    if (files.isNotEmpty()) {
                        files.forEach { addFileAttachment(it) }
                        return true
                    }
                }
            }
            // 5) Texte standard : check sélection éditeur d'abord (Cursor-like)
            if (tr.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val s = tr.getTransferData(DataFlavor.stringFlavor) as? String
                if (s != null) {
                    val looksLikeCode = s.contains('\n') || s.length > 80
                    if (looksLikeCode) {
                        val ref = EditorSelectionGrabber.tryMatchClipboard(project, s)
                        if (ref != null) { addAttachment(ref); return true }
                    }
                    textArea.replaceSelection(s)
                    return true
                }
            }
        } catch (e: Exception) {
            log.warn("handleTransferable failed", e)
        }
        return false
    }

    private fun addImageFromAwt(img: Image, name: String) {
        val buffered = imageToBuffered(img)
        val base64 = bufferedToBase64Png(buffered)
        addAttachment(PromptAttachment.Image(displayName = name, mimeType = "image/png", base64Data = base64))
    }

    private fun parseUriList(text: String): List<File> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { uri ->
                runCatching {
                    val u = java.net.URI(uri)
                    if (u.scheme == "file") File(u) else null
                }.getOrNull()
            }
    }

    private fun imageToBuffered(img: Image): BufferedImage {
        if (img is BufferedImage) return img
        val w = img.getWidth(null).coerceAtLeast(1)
        val h = img.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return buffered
    }

    private fun bufferedToBase64Png(img: BufferedImage): String {
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    // ── Drag and drop ────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun setupDragAndDrop(target: JComponent) {
        // CRITIQUE : désactiver le drop par défaut du JTextArea (qui insère le path en texte).
        // Désactive aussi le drag handler par défaut.
        textArea.dropTarget = null
        textArea.dragEnabled = false

        val handler = object : java.awt.dnd.DropTargetAdapter() {
            override fun drop(dtde: DropTargetDropEvent) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                    val tr = dtde.transferable
                    log.info("Drop flavors: " + tr.transferDataFlavors.joinToString { it.mimeType ?: it.toString() })

                    // 1) javaFileListFlavor (Windows, macOS, certains Linux DE)
                    if (tr.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        val files = tr.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        if (!files.isNullOrEmpty()) {
                            files.forEach { addFileAttachment(it) }
                            dtde.dropComplete(true)
                            return
                        }
                    }

                    // 2) text/uri-list (Linux Nautilus / Files)
                    val uriListFlavor = runCatching {
                        DataFlavor("text/uri-list;class=java.lang.String")
                    }.getOrNull()
                    if (uriListFlavor != null && tr.isDataFlavorSupported(uriListFlavor)) {
                        val text = tr.getTransferData(uriListFlavor) as? String
                        if (text != null) {
                            val files = parseUriList(text)
                            if (files.isNotEmpty()) {
                                files.forEach { addFileAttachment(it) }
                                dtde.dropComplete(true)
                                return
                            }
                        }
                    }

                    // 3) imageFlavor direct
                    if (tr.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        val img = tr.getTransferData(DataFlavor.imageFlavor) as? Image
                        if (img != null) {
                            addImageFromAwt(img, "dropped-${System.currentTimeMillis() % 100000}.png")
                            dtde.dropComplete(true)
                            return
                        }
                    }

                    dtde.dropComplete(false)
                } catch (e: Exception) {
                    log.warn("Drop failed", e)
                    dtde.dropComplete(false)
                }
            }
        }
        // Attache notre dropTarget sur le panel root ET sur le textarea, sinon le drop
        // qui atterrit sur le textarea est intercepté par son handler par défaut.
        DropTarget(target, DnDConstants.ACTION_COPY, handler, true)
        DropTarget(textArea, DnDConstants.ACTION_COPY, handler, true)
    }

    // ── File picker (bouton 📎) ──────────────────────────────────────────────

    private fun openFilePicker() {
        val desc = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withTitle("Attach files or images")
        val files = FileChooser.chooseFiles(desc, project, null)
        files.forEach { vf -> addFileAttachment(File(vf.path)) }
    }

    private fun addFileAttachment(file: File) {
        try {
            val name = file.name.lowercase()
            val isImage = listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp")
                .any { name.endsWith(it) }
            log.info("addFileAttachment: path=${file.absolutePath}, exists=${file.exists()}, isImage=$isImage")
            if (isImage) {
                val bytes = file.readBytes()
                val mime = when {
                    name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
                    name.endsWith(".gif") -> "image/gif"
                    name.endsWith(".webp") -> "image/webp"
                    name.endsWith(".bmp") -> "image/bmp"
                    else -> "image/png"
                }
                addAttachment(
                    PromptAttachment.Image(
                        displayName = file.name,
                        mimeType = mime,
                        base64Data = Base64.getEncoder().encodeToString(bytes)
                    )
                )
            } else {
                val ftm = FileTypeManager.getInstance()
                val icon = ftm.getFileTypeByFileName(file.name).icon
                    ?: AllIcons.FileTypes.Any_type
                addAttachment(
                    PromptAttachment.FileLink(
                        absolutePath = file.absolutePath,
                        isDirectory = file.isDirectory,
                        displayName = file.name,
                        icon = icon
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    // ── Attachments management ───────────────────────────────────────────────

    private fun addAttachment(att: PromptAttachment) {
        attachments.add(att)
        refreshAttachmentsPanel()
    }

    private fun removeAttachment(att: PromptAttachment) {
        attachments.remove(att)
        refreshAttachmentsPanel()
    }

    private fun refreshAttachmentsPanel() {
        attachmentsPanel.removeAll()
        attachments.forEach { att ->
            attachmentsPanel.add(AttachmentChip(att) { removeAttachment(att) })
        }
        attachmentsPanel.isVisible = attachments.isNotEmpty()
        attachmentsPanel.revalidate()
        attachmentsPanel.repaint()
    }

    // ── Send / Stop ──────────────────────────────────────────────────────────

    /** Public : appelé par le panel parent quand un prompt commence/finit pour NOTRE session. */
    fun setExecutingState(executing: Boolean) {
        isExecuting = executing
        if (executing) {
            sendButton.text = "⏹"
            sendButton.toolTipText = "Stop the current prompt"
            sendButton.foreground = Color(0xc62828)
        } else {
            sendButton.text = "➤"
            sendButton.toolTipText = "Send (Enter — Shift+Enter for new line)"
            sendButton.foreground = null
        }
    }

    private fun onSendButtonClick() {
        // Stop = arrêter l'exécution en cours, point. Pas d'envoi de message en plus.
        // L'user récupère un état idle ; s'il veut envoyer le texte déjà tapé, il cliquera Send
        // une fois le bouton revenu à ➤.
        if (isExecuting) {
            onCancel?.invoke()
            return
        }
        send()
    }

    private fun send() {
        if (isExecuting) return  // garde-fou : ne jamais envoyer pendant qu'on exécute
        val txt = textArea.text.trim()
        if (txt.isEmpty() && attachments.isEmpty()) return

        // Intercept des slash commands plugin (/mode /model /effort /skill /mcp) :
        // ne PAS envoyer à claude, déclencher un picker UI à la place.
        if (txt.startsWith("/")) {
            val first = txt.substringBefore(' ').substring(1).lowercase()
            if (first in PLUGIN_SLASH_COMMANDS) {
                val args = txt.substringAfter(' ', "").trim()
                onSlashCommand?.invoke(first, args)
                textArea.text = ""
                return
            }
        }

        onSend?.invoke(txt, attachments.toList())
        textArea.text = ""
        attachments.clear()
        refreshAttachmentsPanel()
    }

    // ── Config cache (les anciens dropdowns sont remplacés par les slash commands) ───

    private fun updateButtons(config: ClaudeACPService.SessionConfig) {
        currentConfig = config
    }

    /** Exposé pour ClaudeACPToolWindowPanel afin de résoudre les slash commands. */
    fun getCurrentConfig(): ClaudeACPService.SessionConfig = currentConfig

    /** Insère du texte au début du textarea et focus. Utilisé par les SlashPickerCards. */
    fun insertText(text: String) {
        textArea.text = text + textArea.text
        textArea.caretPosition = text.length
        textArea.requestFocusInWindow()
    }

    /** Remplace le textarea par `text` et envoie immédiatement comme prompt. */
    fun sendDirectly(text: String) {
        if (isExecuting) return
        textArea.text = text
        send()
    }

    /** Ajoute un attachment depuis une source externe (ex: AddSelectionToChatAction). */
    fun addAttachmentExternal(att: PromptAttachment) {
        addAttachment(att)
        textArea.requestFocusInWindow()
    }

    private fun updateAgentButtonLabel() {
        val active = AgentProfilesService.getInstance().getActiveProfile()
        agentButton.text = if (agentLocked) "🔒 ${active.displayName}" else "${active.displayName} ▾"
        agentButton.toolTipText = if (agentLocked) {
            "Agent locked for this conversation. Start a new chat to use another agent."
        } else {
            "Switch ACP agent (locks once the conversation starts)"
        }
    }

    private fun showAgentMenu() {
        if (agentLocked) return
        val service = AgentProfilesService.getInstance()
        val active = service.getActiveProfile()
        val menu = JPopupMenu()
        service.getAllProfiles().forEach { profile ->
            val label = profile.displayName + if (profile.id == active.id) "  ✓" else ""
            val item = JMenuItem(label)
            item.toolTipText = profile.fullCommandLine()
            item.addActionListener {
                if (profile.id != active.id) {
                    // Single-process : switcher d'agent kill le process courant et donc TOUS les
                    // autres chats qui l'utilisaient. On prévient l'user avant.
                    if (acpService.state == ClaudeACPService.State.READY ||
                        acpService.state == ClaudeACPService.State.INITIALIZING ||
                        acpService.state == ClaudeACPService.State.CREATING_SESSION) {
                        val choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                            "Switching to '${profile.displayName}' will stop the current " +
                                "'${active.displayName}' process and end all other chats using it.\n\n" +
                                "Continue?",
                            "Switch agent",
                            com.intellij.openapi.ui.Messages.getWarningIcon()
                        )
                        if (choice != com.intellij.openapi.ui.Messages.YES) return@addActionListener
                    }
                    service.setActiveProfile(profile.id)
                    updateAgentButtonLabel()
                    acpService.switchAgent(profile)
                }
            }
            menu.add(item)
        }
        menu.addSeparator()
        val openSettings = JMenuItem("⚙ Manage agents…")
        openSettings.addActionListener {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, AgentSettingsConfigurable::class.java)
        }
        menu.add(openSettings)
        menu.show(agentButton, 0, agentButton.height)
    }

    @Suppress("unused")
    private fun createMenuButton(label: String): JButton {
        return JButton("$label ▾").apply {
            margin = JBUI.insets(2, 6)
            isFocusPainted = false
            font = font.deriveFont(Font.PLAIN, 11f)
        }
    }

    /**
     * FlowLayout qui calcule correctement sa preferredSize quand les composants sont
     * wrappés sur plusieurs lignes. Le FlowLayout standard suppose une seule ligne pour
     * le calcul de preferred → le parent ne sait pas qu'il faut plus de hauteur.
     * Ici on simule le wrap pour donner la vraie hauteur préférée.
     */
    private class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {
        override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)
        override fun minimumLayoutSize(target: Container): Dimension {
            val m = layoutSize(target, false)
            m.width -= hgap + 1
            return m
        }
        private fun layoutSize(target: Container, preferred: Boolean): Dimension {
            synchronized(target.treeLock) {
                var targetWidth = target.size.width
                if (targetWidth == 0) {
                    val parent = target.parent
                    targetWidth = if (parent != null && parent.size.width > 0) parent.size.width else Int.MAX_VALUE
                }
                val insets = target.insets
                val maxWidth = (targetWidth - (insets.left + insets.right + hgap * 2)).coerceAtLeast(1)
                val dim = Dimension(0, 0)
                var rowWidth = 0
                var rowHeight = 0
                for (i in 0 until target.componentCount) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue
                    val d = if (preferred) m.preferredSize else m.minimumSize
                    if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                        addRow(dim, rowWidth, rowHeight)
                        rowWidth = 0
                        rowHeight = 0
                    }
                    if (rowWidth != 0) rowWidth += hgap
                    rowWidth += d.width
                    rowHeight = maxOf(rowHeight, d.height)
                }
                addRow(dim, rowWidth, rowHeight)
                dim.width += insets.left + insets.right + hgap * 2
                dim.height += insets.top + insets.bottom + vgap * 2
                return dim
            }
        }
        private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
            dim.width = maxOf(dim.width, rowWidth)
            if (dim.height > 0) dim.height += vgap
            dim.height += rowHeight
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
