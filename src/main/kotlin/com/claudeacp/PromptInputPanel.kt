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

    private val modeButton = createMenuButton("Mode")
    private val modelButton = createMenuButton("Model")
    private val effortButton = createMenuButton("Effort")

    private val attachButton = JButton(AllIcons.General.Add).apply {
        toolTipText = "Attach files or images"
        margin = JBUI.insets(4)
        isFocusPainted = false
    }

    private val agentButton = JButton("Agent ▾").apply {
        margin = JBUI.insets(2, 6)
        isFocusPainted = false
        font = font.deriveFont(Font.PLAIN, 11f)
        toolTipText = "Switch ACP agent"
    }

    private val sendButton = JButton("➤").apply {
        toolTipText = "Send (Enter — Shift+Enter for new line)"
        margin = JBUI.insets(4, 10)
        font = font.deriveFont(Font.BOLD)
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

    private val fileMentionPopup = FileMentionPopup(project, textArea) { entry ->
        replaceMentionToken(entry)
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
        setupClipboardPaste()
        setupSmartPasteAction()  // intercepte Ctrl/Cmd+V au niveau IntelliJ Action System
        setupDragAndDrop(root)

        val scrollText = JScrollPane(textArea).apply {
            border = null
            preferredSize = Dimension(0, 70)
        }

        val centerStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getTextFieldBackground()
            add(attachmentsPanel)
            add(scrollText)
        }

        // Footer
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = UIUtil.getPanelBackground()
            add(attachButton)
            add(agentButton)
            add(modeButton)
            add(modelButton)
            add(effortButton)
        }
        updateAgentButtonLabel()
        agentButton.addActionListener { showAgentMenu() }
        val sendPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 2)).apply {
            background = UIUtil.getPanelBackground()
            add(sendButton)
        }
        val footer = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(toolbar, BorderLayout.WEST)
            add(sendPanel, BorderLayout.EAST)
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
                // Le popup mention intercepte d'abord (Up/Down/Enter/Tab/Esc)
                if (fileMentionPopup.handleKey(e)) return

                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    send()
                }
            }

            override fun keyReleased(e: KeyEvent) {
                // Si on a tapé @ ou modifié le token courant : refresh popup
                if (e.keyCode == KeyEvent.VK_ESCAPE) return
                updateMentionPopup()
            }
        })
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
                    // 5) Texte plain : insertion standard
                    if (tr.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        val s = tr.getTransferData(DataFlavor.stringFlavor) as? String
                        if (s != null) {
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
            // 5) Texte standard : insertion classique
            if (tr.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val s = tr.getTransferData(DataFlavor.stringFlavor) as? String
                if (s != null) {
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
        if (isExecuting && textArea.text.trim().isEmpty()) {
            onCancel?.invoke()
            return
        }
        send()
    }

    private fun send() {
        val txt = textArea.text.trim()
        if (txt.isEmpty() && attachments.isEmpty()) {
            if (isExecuting) onCancel?.invoke()
            return
        }
        if (isExecuting) onCancel?.invoke()
        onSend?.invoke(txt, attachments.toList())
        textArea.text = ""
        attachments.clear()
        refreshAttachmentsPanel()
    }

    // ── Model / Mode / Effort menus (inchangé) ───────────────────────────────

    private fun updateButtons(config: ClaudeACPService.SessionConfig) {
        val currentMode = config.modes.firstOrNull { it.id == config.currentModeId }
        modeButton.text = "Mode: ${currentMode?.name ?: "default"} ▾"
        modeButton.isEnabled = config.modes.isNotEmpty()

        val currentModel = config.models.firstOrNull { it.id == config.currentModelId }
        modelButton.text = "Model: ${currentModel?.name ?: "Default"} ▾"
        modelButton.isEnabled = config.models.isNotEmpty()

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
                item.addActionListener { acpService.setModel(opt.id, targetSessionId = getMySessionId()) }
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
                item.addActionListener { acpService.setMode(opt.id, targetSessionId = getMySessionId()) }
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
                item.addActionListener { acpService.setConfigOption(effort.id, opt.id, targetSessionId = getMySessionId()) }
                menu.add(item)
            }
            menu.show(effortButton, 0, effortButton.height)
        }
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
