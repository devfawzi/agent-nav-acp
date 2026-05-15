package com.claudeacp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

class ClaudeACPToolWindowPanel(
    private val project: Project,
    private val isFirstChat: Boolean = true
) {

    private val acpService = project.getService(ClaudeACPService::class.java)
    private val historyService = project.getService(PromptHistoryService::class.java)
    private val diffManager = project.getService(DiffViewerManager::class.java)

    private val chatPanel = ChatPanel(project) { toolUseId, replyText, switchModeTo ->
        // ExitPlanMode Approve / AskUserQuestion Submit / Skip / Reject : on envoie le
        // tool_result attendu par claude, et si demandé on switch le permission mode
        // (typiquement plan → acceptEdits après approbation). L'ordre compte : switch
        // d'abord pour que les Write/Edit suivants soient autorisés.
        val sid = mySessionId
        if (switchModeTo != null) {
            acpService.setMode(switchModeTo, sid)
        }
        acpService.replyToolResult(toolUseId, replyText, sid)
    }
    private val pendingPanel = PendingChangesPanel(project)
    private val pendingService = project.getService(PendingChangesService::class.java)

    /**
     * sessionId que ce panel "possède". Chaque content de la tool window a son propre sid pour
     * router correctement les chunks de Claude vers le bon onglet de chat.
     */
    @Volatile
    private var mySessionId: String? = null

    private val inputPanel = PromptInputPanel(project) { mySessionId }

    /** Callback set par la factory pour renommer le content (le tab) du tool window. */
    var renameContentCallback: ((String) -> Unit)? = null

    /** Indique si on a déjà auto-renommé via le 1er prompt (pour ne pas écraser un rename manuel). */
    @Volatile
    private var hasAutoRenamed = false

    fun setSessionId(sid: String) {
        mySessionId = sid
        // Refresh la config locale + label session id pour faciliter `claude --resume <sid>`
        ApplicationManager.getApplication().invokeLater {
            inputPanel.refreshConfig(acpService.getSessionConfig(sid))
            updateSessionLabel(sid)
        }
    }

    private fun updateSessionLabel(sid: String) {
        val short = sid.take(8)
        sessionLabel.text = "🔗 $short…"
        sessionLabel.toolTipText = "<html>Session: <code>$sid</code><br>" +
            "Click to copy <code>claude --resume $sid</code></html>"
        sessionLabel.isVisible = true
    }

    fun getSessionId(): String? = mySessionId

    private val statusLabel = JLabel("⏸ Stopped").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 8)
    }

    private val sessionLabel = JLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val sid = mySessionId ?: return
                val cmd = "claude --resume $sid"
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(java.awt.datatransfer.StringSelection(cmd), null)
                val prev = text
                text = "✓ copied"
                javax.swing.Timer(1200) { text = prev; (it.source as javax.swing.Timer).stop() }.start()
            }
        })
    }

    private val historyButton = JButton(com.intellij.icons.AllIcons.Vcs.History).apply {
        toolTipText = "Prompt history for this chat"
        margin = JBUI.emptyInsets()
        preferredSize = java.awt.Dimension(28, 28)
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
        addActionListener { showHistoryPopup() }
    }

    private val exportButton = JButton(com.intellij.icons.AllIcons.Actions.Download).apply {
        toolTipText = "Export this conversation as Markdown"
        margin = JBUI.emptyInsets()
        preferredSize = java.awt.Dimension(28, 28)
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
        addActionListener { exportChatToMarkdown() }
    }

    /** Indicateur discret de tokens/coût cumulé, mis à jour à chaque event `result`. */
    private val usageLabel = JLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 6)
        isVisible = false
    }

    /**
     * Bandeau live au-dessus du chat qui montre ce que Claude est en train de faire en ce
     * moment : "🔎 Read MyFile.kt", "⚙ Bash: npm test", "🤔 Thinking…". Disparait quand le
     * turn se termine (event result).
     */
    private val activityBar = JLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor(java.awt.Color(0x4a6fa5), java.awt.Color(0x7a9fd1))
        border = JBUI.Borders.empty(4, 10)
        isOpaque = true
        background = JBColor(java.awt.Color(0xeaf3ff), java.awt.Color(0x1f2d3d))
        isVisible = false
    }

    private val rootCard = JPanel(CardLayout())
    private lateinit var chatRoot: JComponent
    private lateinit var onboardingPanel: OnboardingPanel
    private lateinit var onboardingRoot: JComponent

    fun getContent(): JComponent {
        chatRoot = buildChatRoot()
        onboardingPanel = OnboardingPanel(onRetry = { checkPrerequisitesAndConnect() })
        onboardingRoot = onboardingPanel.getContent()

        rootCard.add(chatRoot, "chat")
        rootCard.add(onboardingRoot, "onboarding")

        wireListeners()

        ApplicationManager.getApplication().invokeLater {
            checkPrerequisitesAndConnect()
        }

        return rootCard
    }

    private fun checkPrerequisitesAndConnect() {
        val prereqs = acpService.checkPrerequisites()
        if (!prereqs.allOk) {
            onboardingPanel.update(prereqs)
            showCard("onboarding")
            statusLabel.text = "❌ Setup required"
            statusLabel.foreground = JBColor.RED
            return
        }

        showCard("chat")
        if (acpService.state !in setOf(
                ClaudeACPService.State.READY,
                ClaudeACPService.State.STARTING,
                ClaudeACPService.State.INITIALIZING,
                ClaudeACPService.State.CREATING_SESSION
            )
        ) {
            chatPanel.appendInfo("Connecting to AgentNav ACP...")
            acpService.startAgent()
        }
    }

    private fun showCard(name: String) {
        (rootCard.layout as CardLayout).show(rootCard, name)
    }

    private fun buildChatRoot(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
        }

        // Header compact : juste status + bouton history (les tabs des chats sont gérés
        // nativement par le ContentManager d'IntelliJ via la title bar de la tool window)
        val header = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(0, 4)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                background = UIUtil.getPanelBackground()
                add(sessionLabel)
                add(usageLabel)
                add(exportButton)
                add(historyButton)
                add(statusLabel)
            }, BorderLayout.EAST)
        }

        // Bottom : pending bar + input
        val bottom = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(pendingPanel.getContent(), BorderLayout.NORTH)
            add(inputPanel.getContent(), BorderLayout.CENTER)
            border = JBUI.Borders.empty(4)
        }

        // chatStack = chat scrollpane + activity bar (au-dessus, visible seulement pendant exec)
        val chatStack = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(activityBar, BorderLayout.NORTH)
            add(chatPanel.getContent(), BorderLayout.CENTER)
        }
        root.add(header, BorderLayout.NORTH)
        root.add(chatStack, BorderLayout.CENTER)
        root.add(bottom, BorderLayout.SOUTH)

        inputPanel.onSend { txt, atts ->
            if (acpService.state != ClaudeACPService.State.READY) {
                chatPanel.appendError("Agent not ready (state=${acpService.state})")
                return@onSend
            }
            // En CLI mode, mySessionId est null tant que claude n'a pas émis son 1er
            // system:init (peut arriver seulement après notre 1er user message). On
            // autorise donc le 1er send même sans sid : le service trouvera le
            // pendingCliProc et on claim le sid quand il arrive.
            val isCliMode = acpService.activeProfile.transport == Transport.CLI_STREAM_JSON
            if (mySessionId == null && !isCliMode) {
                chatPanel.appendError("No session attached to this chat — try opening a new chat.")
                return@onSend
            }
            if (!hasAutoRenamed) {
                hasAutoRenamed = true
                val title = txt.take(40).let { if (txt.length > 40) "$it…" else it }
                renameContentCallback?.invoke(title)
            }
            chatPanel.appendUserMessage(txt)
            // Une fois la conversation démarrée, on verrouille le choix d'agent pour ce chat.
            inputPanel.lockAgent()
            // Affiche aussi un petit récap des pièces jointes en dessous (chips)
            if (atts.isNotEmpty()) {
                val names = atts.joinToString(", ") {
                    when (it) {
                        is PromptAttachment.FileLink -> "📎 ${it.displayName}"
                        is PromptAttachment.Image -> "🖼 ${it.displayName}"
                        is PromptAttachment.CodeRef -> "📌 ${it.displayName}"
                    }
                }
                chatPanel.appendInfo("Attachments: $names")
            }
            // Injection auto des diagnostics LSP du buffer courant (si activée).
            val settings = AgentSettings.getInstance()
            val enrichedText = if (settings.injectDiagnostics) {
                val diags = EditorDiagnosticsGrabber.buildDiagnosticsContext(
                    project,
                    errorsOnly = !settings.injectDiagnosticsIncludeWarnings
                )
                if (diags != null) txt + diags else txt
            } else txt
            acpService.sendPrompt(enrichedText, targetSessionId = mySessionId, attachments = atts)
        }

        inputPanel.onCancel {
            // Cancel uniquement le prompt de NOTRE session, pas celui en cours global.
            acpService.cancelPrompt(targetSessionId = mySessionId)
        }

        inputPanel.onSlashCommand { cmd, args ->
            handleSlashCommand(cmd, args)
        }

        return root
    }

    /**
     * Dispatch des slash commands plugin (/mode /model /effort /skill /mcp) — interceptés
     * AVANT l'envoi à claude. Affiche une SlashPickerCard interactive dans le chat avec les
     * options, et applique le choix de l'user via les setters du service.
     */
    private fun handleSlashCommand(cmd: String, args: String) {
        val config = inputPanel.getCurrentConfig()
        val sid = mySessionId
        when (cmd) {
            "model" -> {
                val options = config.models.map { opt ->
                    SlashPickerOption(
                        id = opt.id,
                        label = opt.name,
                        description = opt.description,
                        onPick = { acpService.setModel(opt.id, targetSessionId = sid) }
                    )
                }
                chatPanel.appendSlashPicker(
                    title = "Pick a model",
                    options = options,
                    currentValueId = config.currentModelId,
                    footer = if (options.isEmpty()) "Model list arrives after the first prompt." else null
                )
            }
            "mode" -> {
                val options = config.modes.map { opt ->
                    SlashPickerOption(
                        id = opt.id,
                        label = opt.name,
                        description = opt.description,
                        onPick = { acpService.setMode(opt.id, targetSessionId = sid) }
                    )
                }
                chatPanel.appendSlashPicker(
                    title = "Pick a permission mode",
                    options = options,
                    currentValueId = config.currentModeId
                )
            }
            "effort" -> {
                val effortOpt = config.configOptions.firstOrNull {
                    it.id.lowercase().contains("thought") || it.id.lowercase().contains("effort")
                        || it.name.lowercase().contains("effort") || it.name.lowercase().contains("thinking")
                }
                if (effortOpt == null) {
                    chatPanel.appendSlashPicker(
                        title = "Effort",
                        options = emptyList(),
                        footer = "Not available on this agent."
                    )
                    return
                }
                val options = effortOpt.options.map { opt ->
                    SlashPickerOption(
                        id = opt.id,
                        label = opt.name,
                        description = opt.description,
                        onPick = { acpService.setConfigOption(effortOpt.id, opt.id, targetSessionId = sid) }
                    )
                }
                chatPanel.appendSlashPicker(
                    title = "Pick an effort level",
                    options = options,
                    currentValueId = effortOpt.currentValue
                )
            }
            "skill", "skills" -> {
                val skillSet = config.skills.toSet()
                val sorted = config.slashCommands.sorted()
                // Filtre optionnel par args (ex: /skills review → ne montre que ceux qui matchent)
                val filtered = if (args.isNotEmpty()) sorted.filter { it.contains(args, ignoreCase = true) } else sorted
                val options = filtered.map { name ->
                    val isUserSkill = name in skillSet
                    SlashPickerOption(
                        id = name,
                        label = "/$name",
                        description = if (isUserSkill) "user skill" else "built-in command",
                        icon = if (isUserSkill) "🧩" else "⚙",
                        onPick = {
                            // Insert la slash command dans le textarea (l'user complète son intent + Send)
                            inputPanel.insertText("/$name ")
                        }
                    )
                }
                chatPanel.appendSlashPicker(
                    title = if (args.isEmpty()) "Skills & commands" else "Skills matching \"$args\"",
                    options = options,
                    footer = "Click to insert the slash command in the prompt."
                )
            }
            "mcp" -> {
                val items = mutableListOf<SlashPickerOption>()
                config.mcpServers.forEach { srv ->
                    val icon = when (srv.status) {
                        "connected" -> "🟢"
                        "needs-auth" -> "🔑"
                        "failed", "error" -> "❌"
                        else -> "⚪"
                    }
                    items += SlashPickerOption(
                        id = "server:${srv.name}",
                        label = "${srv.name}  (${srv.status})",
                        description = when (srv.status) {
                            "needs-auth" -> "Click to insert an auth prompt."
                            "connected" -> "Click to mention this server in the prompt."
                            else -> "Click to mention this server."
                        },
                        icon = icon,
                        onPick = {
                            val text = when (srv.status) {
                                "needs-auth" -> "Authenticate the ${srv.name} MCP server. "
                                else -> "Use the ${srv.name} MCP server to "
                            }
                            inputPanel.insertText(text)
                        }
                    )
                }
                config.mcpTools.sorted().forEach { tool ->
                    val parts = tool.removePrefix("mcp__").split("__", limit = 2)
                    val label = if (parts.size == 2) "${parts[0]} · ${parts[1]}" else tool
                    items += SlashPickerOption(
                        id = "tool:$tool",
                        label = label,
                        // Click direct = invocation immédiate du tool sans intent additionnel
                        description = "$tool — click to invoke directly",
                        icon = "🔧",
                        onPick = {
                            // On envoie tout de suite un prompt qui force claude à appeler
                            // ce tool MCP. claude le détecte dans son arsenal de tools[].
                            inputPanel.sendDirectly("Use the $tool tool with appropriate arguments. " +
                                "If arguments are needed, infer them from the current project context.")
                        }
                    )
                }
                val footer = when {
                    items.isEmpty() && sid == null ->
                        "No MCP info yet — send your first prompt so Claude loads system:init, then retry /mcp."
                    items.isEmpty() ->
                        "No MCP servers detected. Set --mcp-config in Settings, or run `claude mcp add` in a terminal."
                    else -> "${config.mcpServers.size} server(s) · ${config.mcpTools.size} tool(s)"
                }
                chatPanel.appendSlashPicker(
                    title = "MCP servers & tools",
                    options = items,
                    footer = footer
                )
            }
            else -> {
                chatPanel.appendInfo("Unknown command: /$cmd")
            }
        }
    }

    private fun wireListeners() {
        // Au switch d'agent : reset notre sessionId.
        // Chat 1 → re-claim auto via le state listener ci-dessous.
        // Chat 2+ → on schedule un newSession dès que le service redevient READY.
        acpService.addAgentSwitchedListener {
            mySessionId = null
            inputPanel.refreshConfig(ClaudeACPService.SessionConfig())
            if (!isFirstChat) {
                schedulePostReadyNewSession()
            }
        }

        // Pour Chat 1 : claim auto le sessionId initial du service dès qu'il devient READY.
        // Ça évite que Chat 1 récupère par hasard le sessionId d'un Chat 2 créé entretemps.
        if (isFirstChat) {
            val claimListener = object : (ClaudeACPService.State) -> Unit {
                override fun invoke(s: ClaudeACPService.State) {
                    if (s == ClaudeACPService.State.READY && mySessionId == null) {
                        val sid = acpService.sessionId
                        if (sid != null) {
                            setSessionId(sid)
                        }
                    }
                }
            }
            acpService.addStateListener(claimListener)
            // Catch-up si déjà READY au moment où on s'abonne
            if (acpService.state == ClaudeACPService.State.READY && mySessionId == null) {
                acpService.sessionId?.let { setSessionId(it) }
            }
            // En CLI mode : le sid arrive après le 1er user message (via system:init),
            // donc on écoute aussi sessionCreatedListener pour claim à ce moment-là.
            val firstSidClaim = object : (String) -> Unit {
                override fun invoke(sid: String) {
                    if (mySessionId == null) {
                        setSessionId(sid)
                        acpService.removeSessionCreatedListener(this)
                    }
                }
            }
            acpService.addSessionCreatedListener(firstSidClaim)
        }

        acpService.addStateListener { newState ->
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = when (newState) {
                    ClaudeACPService.State.STOPPED -> "⏸ Stopped"
                    ClaudeACPService.State.STARTING -> "🚀 Starting..."
                    ClaudeACPService.State.INITIALIZING -> "🤝 Initializing..."
                    ClaudeACPService.State.CREATING_SESSION -> "📝 Creating session..."
                    ClaudeACPService.State.READY -> "✅ Ready"
                    ClaudeACPService.State.ERROR -> "❌ Error"
                }
                statusLabel.foreground = when (newState) {
                    ClaudeACPService.State.READY -> JBColor.GREEN
                    ClaudeACPService.State.ERROR -> JBColor.RED
                    else -> JBColor.foreground()
                }
                inputPanel.setReady(newState == ClaudeACPService.State.READY)
                // Pré-remplit Model/Mode/Effort/Skills/MCP dès READY pour que l'user puisse
                // configurer AVANT d'envoyer le 1er prompt (claude n'émet system:init qu'après
                // le 1er user message, sinon les dropdowns resteraient grisés au démarrage).
                if (newState == ClaudeACPService.State.READY) {
                    inputPanel.refreshConfig(acpService.getSessionConfig(mySessionId))
                }
            }
        }

        // Les infos info() reste sur le 1er chat (status connexion etc.) pour pas spammer.
        if (isFirstChat) {
            acpService.addInfoListener { msg ->
                ApplicationManager.getApplication().invokeLater { chatPanel.appendInfo(msg) }
            }
        }
        // Erreurs et stderr : affichés sur TOUS les chats — sinon un chat resumé qui plante
        // au boot (ex: --resume sans bon cwd) reste silencieux et l'user voit juste un cryptique
        // "No Claude CLI process available" quand il prompte.
        acpService.addErrorListener { msg ->
            ApplicationManager.getApplication().invokeLater { chatPanel.appendError(msg) }
        }
        acpService.addStderrListener { msg ->
            ApplicationManager.getApplication().invokeLater { chatPanel.appendStderr(msg) }
        }
        // Filtres par sessionId STRICT : si mySessionId est null, on n'affiche RIEN.
        acpService.addMessageChunkListener { text, sid ->
            if (matchesMySession(sid)) {
                ApplicationManager.getApplication().invokeLater { chatPanel.appendAssistantChunk(text) }
            }
        }
        acpService.addThoughtChunkListener { text, sid ->
            if (matchesMySession(sid)) {
                ApplicationManager.getApplication().invokeLater { chatPanel.appendThinkingChunk(text) }
            }
        }
        acpService.addToolCallListener { info ->
            if (matchesMySession(info.sessionId)) {
                ApplicationManager.getApplication().invokeLater {
                    chatPanel.appendToolCall(info)
                    updateActivityBar(info)
                }
            }
        }
        acpService.addThoughtChunkListener { _, sid ->
            if (matchesMySession(sid)) {
                ApplicationManager.getApplication().invokeLater {
                    setActivityText("🤔 Thinking…")
                }
            }
        }
        acpService.addMessageChunkListener { _, sid ->
            // Quand claude commence à émettre du texte assistant, on cache le bandeau d'activité
            // tool — il a "fini" de réfléchir/agir et il "parle".
            if (matchesMySession(sid)) {
                ApplicationManager.getApplication().invokeLater {
                    setActivityText("✍ Writing reply…")
                }
            }
        }

        // Quand un tool est bloqué (Bash hors cwd, etc.), claude renvoie un tool_result is_error.
        // On affiche le message dans le chat pour que l'user comprenne pourquoi sa commande
        // n'a pas tourné (au lieu de voir juste un Tools (1) silencieux).
        acpService.addToolResultErrorListener { msg, sid ->
            if (matchesMySession(sid)) {
                ApplicationManager.getApplication().invokeLater { chatPanel.appendError(msg) }
            }
        }

        // Config par sid : refresh des dropdowns Model/Mode/Effort uniquement pour notre session.
        acpService.addSessionConfigListener { sid, config ->
            if (sid != null && sid == mySessionId) {
                ApplicationManager.getApplication().invokeLater {
                    inputPanel.refreshConfig(config)
                }
            }
        }

        // Executing : on accepte l'event si sid matche notre chat, OU si on n'a pas encore claim
        // de sid (cas resume/Chat 2+ avant le 1er system:init). Le user a explicitement demandé
        // « stop quoi qu'il arrive » → on privilégie l'affichage du ⏹ même si le routing par sid
        // n'est pas encore résolu. cancelCliPrompt fait son propre fallback pour trouver le proc.
        acpService.addExecutingListener { executing, sid ->
            val matches = sid == mySessionId || mySessionId == null
            if (matches) {
                ApplicationManager.getApplication().invokeLater {
                    inputPanel.setExecutingState(executing)
                    if (executing) {
                        setActivityText("🤔 Thinking…")
                    } else {
                        hideActivityBar()
                    }
                }
            }
        }

        // Card de modification de fichier dans le chat de la session concernée
        pendingService.addAddedListener { change ->
            if (matchesMySession(change.triggeredBySessionId)) {
                ApplicationManager.getApplication().invokeLater { chatPanel.appendFileChange(change) }
            }
        }

        // Permission request (--permission-prompt-tool stdio) → carte Allow/Deny inline
        acpService.addPermissionRequestListener { req ->
            if (matchesMySession(req.sessionId)) {
                ApplicationManager.getApplication().invokeLater {
                    chatPanel.appendPermissionRequest(req)
                }
            }
        }

        // Session rebound : quand le sid de notre chat change (ex: respawn claude --resume
        // après changement de model), on met à jour notre mySessionId pour continuer à
        // recevoir les events.
        acpService.addSessionReboundListener { oldSid, newSid ->
            if (mySessionId == oldSid) {
                mySessionId = newSid
                ApplicationManager.getApplication().invokeLater {
                    inputPanel.refreshConfig(acpService.getSessionConfig(newSid))
                    renderUsage(acpService.getSessionUsage(newSid))
                }
            }
        }

        // Indicateur tokens/coût discret en haut à droite, mis à jour à chaque turn.
        acpService.addUsageListener { sid, stats ->
            if (sid == mySessionId) {
                ApplicationManager.getApplication().invokeLater { renderUsage(stats) }
            }
        }
    }

    private fun setActivityText(text: String) {
        activityBar.text = text
        activityBar.isVisible = true
    }

    private fun hideActivityBar() {
        activityBar.isVisible = false
    }

    private fun updateActivityBar(info: ClaudeACPService.ToolCallInfo) {
        // Si le tool est completed, on revient à "Thinking" en attendant la suite ou la fin.
        if (info.status == "completed" || info.status == "error") {
            setActivityText("🤔 Thinking…")
            return
        }
        // Mapping tool → label compact pour le bandeau live.
        val toolName = info.title
        val icon = when (toolName) {
            "Read" -> "📖"
            "Edit", "MultiEdit", "Write" -> "✏"
            "Bash" -> "⚙"
            "Grep" -> "🔎"
            "Glob" -> "🗂"
            "WebFetch", "WebSearch" -> "🌐"
            "Task" -> "🤖"
            "TodoWrite" -> "📋"
            "Skill" -> "🧩"
            else -> if (toolName.startsWith("mcp__")) "🔌" else "🔧"
        }
        val detail = info.path?.let {
            val base = project.basePath
            if (base != null && it.startsWith(base)) it.substring(base.length).trimStart('/') else it
        } ?: info.command?.take(60) ?: info.detail?.take(60) ?: ""
        val label = if (detail.isNotEmpty()) "$icon $toolName · $detail" else "$icon $toolName"
        setActivityText(label)
    }

    /**
     * Export la conversation courante en markdown via le parser interne. Si la session a un
     * sid réel, on parse le .jsonl. Sinon (session non encore claim), on dump les messages
     * affichés dans le ChatPanel (fallback minimal).
     */
    private fun exportChatToMarkdown() {
        val sid = mySessionId
        val markdown = if (sid != null) {
            val sessionsService = project.getService(ClaudeSessionsService::class.java)
            val file = sessionsService.getProjectSessionsDir()?.resolve("$sid.jsonl")
            if (file != null && file.isFile) {
                renderJsonlAsMarkdown(file)
            } else {
                "_(session file not found on disk yet — wait for the first turn to complete then retry)_\n"
            }
        } else {
            "_(no session yet — send a prompt first)_\n"
        }

        val defaultName = "agentnav-chat-${java.time.LocalDate.now()}.md"
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "Export conversation"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Markdown (*.md)", "md")
            selectedFile = java.io.File(System.getProperty("user.home"), defaultName)
        }
        val result = chooser.showSaveDialog(null)
        if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
            var target = chooser.selectedFile
            if (!target.name.endsWith(".md", ignoreCase = true)) {
                target = java.io.File(target.parentFile, target.name + ".md")
            }
            try {
                target.writeText(markdown, Charsets.UTF_8)
                chatPanel.appendInfo("✓ Exported to ${target.absolutePath}")
            } catch (e: Exception) {
                chatPanel.appendError("Export failed: ${e.message}")
            }
        }
    }

    /** Parse un .jsonl Claude Code en markdown. Logique alignée avec le skill extract-context. */
    private fun renderJsonlAsMarkdown(file: java.io.File): String {
        val wrapperPrefixes = listOf(
            "<local-command-caveat>", "<local-command-stdout>", "<local-command-stderr>",
            "<system-reminder>", "<command-name>", "<command-message>", "<command-args>",
            "<task-notification>", "<bash-stdout>", "<bash-stderr>", "<user-prompt-submit-hook>"
        )
        fun isWrapper(t: String): Boolean {
            val s = t.trimStart()
            return s.isEmpty() || wrapperPrefixes.any { s.startsWith(it) }
        }
        data class Turn(val role: String, val text: String, val tools: List<String>, val ts: String?)
        val turns = mutableListOf<Turn>()
        var summary: String? = null
        var cwd: String? = null
        file.useLines { lines ->
            lines.forEach line@{ line ->
                if (!line.startsWith("{")) return@line
                try {
                    val obj = com.google.gson.JsonParser.parseString(line).asJsonObject
                    if (cwd == null) cwd = obj.get("cwd")?.asString
                    val type = obj.get("type")?.asString
                    if (type == "summary" && summary == null)
                        summary = obj.get("summary")?.asString ?: obj.get("text")?.asString
                    if (type != "user" && type != "assistant") return@line
                    val msg = obj.getAsJsonObject("message") ?: return@line
                    val content = msg.get("content")
                    val textParts = mutableListOf<String>()
                    val tools = mutableListOf<String>()
                    when {
                        content == null -> {}
                        content.isJsonPrimitive -> textParts.add(content.asString)
                        content.isJsonArray -> content.asJsonArray.forEach { blk ->
                            if (!blk.isJsonObject) return@forEach
                            val o = blk.asJsonObject
                            when (o.get("type")?.asString) {
                                "text" -> o.get("text")?.asString?.let { textParts.add(it) }
                                "tool_use" -> {
                                    val name = o.get("name")?.asString ?: "tool"
                                    val input = o.getAsJsonObject("input")
                                    val detail = when (name) {
                                        "Read", "Edit", "Write", "MultiEdit" ->
                                            input?.get("file_path")?.asString ?: input?.get("path")?.asString ?: ""
                                        "Bash" -> input?.get("command")?.asString?.take(80) ?: ""
                                        "Grep", "Glob" -> input?.get("pattern")?.asString ?: ""
                                        else -> ""
                                    }
                                    tools.add(if (detail.isNotEmpty()) "$name($detail)" else name)
                                }
                            }
                        }
                    }
                    val text = textParts.joinToString("\n")
                    if (type == "user" && isWrapper(text)) return@line
                    if (text.isBlank() && tools.isEmpty()) return@line
                    turns.add(Turn(
                        role = type,
                        text = text.take(5000) + (if (text.length > 5000) "…(truncated)" else ""),
                        tools = tools,
                        ts = obj.get("timestamp")?.asString
                    ))
                } catch (_: Exception) {}
            }
        }
        val sb = StringBuilder()
        sb.appendLine("# Conversation (session `${file.nameWithoutExtension.take(8)}…`)")
        sb.appendLine()
        if (summary != null) sb.appendLine("**Summary:** $summary\n")
        sb.appendLine("- **cwd:** `$cwd`")
        sb.appendLine("- **turns:** ${turns.size}")
        sb.appendLine("- **exported:** ${java.time.Instant.now()}")
        sb.appendLine()
        sb.appendLine("---\n")
        var turnIdx = 0
        for (t in turns) {
            if (t.role == "user") {
                turnIdx++
                val ts = (t.ts ?: "").take(19).replace("T", " ")
                sb.appendLine("## Turn $turnIdx — $ts")
                sb.appendLine()
                sb.appendLine("**user:** ${t.text}")
                sb.appendLine()
            } else {
                if (t.text.isNotBlank()) {
                    sb.appendLine("**assistant:** ${t.text}")
                    sb.appendLine()
                }
                t.tools.forEach { sb.appendLine("- `→ $it`") }
                if (t.tools.isNotEmpty()) sb.appendLine()
            }
        }
        return sb.toString()
    }

    private fun renderUsage(stats: ClaudeACPService.UsageStats) {
        if (stats.turnCount == 0) {
            usageLabel.isVisible = false
            return
        }
        val tokens = stats.totalTokens
        val tokStr = when {
            tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.1fk", tokens / 1_000.0)
            else -> tokens.toString()
        }
        val costStr = if (stats.totalCostUsd >= 0.01) String.format("$%.2f", stats.totalCostUsd) else "—"
        usageLabel.text = "$tokStr tok · $costStr"
        usageLabel.toolTipText = "<html>" +
            "Input: ${stats.inputTokens} · Output: ${stats.outputTokens}<br>" +
            "Cache read: ${stats.cacheReadTokens} · Cache write: ${stats.cacheCreationTokens}<br>" +
            "Turns: ${stats.turnCount} · Cost: \$${String.format("%.4f", stats.totalCostUsd)}" +
            "</html>"
        usageLabel.isVisible = true
    }

    /** Pour Chat 2+ après un switch d'agent : recrée une session quand le service est de nouveau READY. */
    private fun schedulePostReadyNewSession() {
        val listener = object : (ClaudeACPService.State) -> Unit {
            override fun invoke(s: ClaudeACPService.State) {
                if (s == ClaudeACPService.State.READY) {
                    acpService.removeStateListener(this)
                    acpService.newSession { newSid -> setSessionId(newSid) }
                }
            }
        }
        acpService.addStateListener(listener)
    }

    /** Renommé manuellement par l'utilisateur (action "Rename Chat"). */
    fun renameChat(newName: String) {
        hasAutoRenamed = true // empêche l'auto-rename ultérieur
        renameContentCallback?.invoke(newName)
    }

    /** Permet à AddSelectionToChatAction (ou autre extension) d'ajouter un attachment. */
    fun addAttachmentExternal(att: PromptAttachment) {
        inputPanel.addAttachmentExternal(att)
    }

    /** Strict : ce panel n'affiche QUE les events de son propre sessionId. */
    private fun matchesMySession(chunkSid: String?): Boolean {
        val my = mySessionId ?: return false
        return chunkSid == my
    }

    private fun showHistoryPopup() {
        val prompts = historyService.getPromptsForSession(mySessionId)
        val frame = JDialog(null as Frame?, "Prompts History", false).apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            size = Dimension(640, 480)
            setLocationRelativeTo(null)
        }

        val list = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8)
        }

        if (prompts.isEmpty()) {
            list.add(JLabel("No prompts yet.").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(10)
            })
        } else {
            prompts.forEach { p ->
                val nbFiles = p.filesAfter.size
                val row = JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.compound(
                        JBUI.Borders.customLine(JBColor.border(), 1),
                        JBUI.Borders.empty(10)
                    )
                    background = UIUtil.getTextFieldBackground()
                    maximumSize = Dimension(Int.MAX_VALUE, 60)
                    preferredSize = Dimension(0, 60)
                }
                row.add(JLabel("#${p.promptId} (${nbFiles} file${if (nbFiles > 1) "s" else ""}): ${p.promptText.take(80)}"), BorderLayout.CENTER)

                val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
                actions.background = UIUtil.getTextFieldBackground()
                actions.add(JButton("View").apply {
                    isEnabled = nbFiles > 0
                    margin = JBUI.insets(2, 6)
                    addActionListener { diffManager.showPromptDiff(p.promptId) }
                })
                if (p.promptId > 1) {
                    actions.add(JButton("vs #${p.promptId - 1}").apply {
                        isEnabled = nbFiles > 0
                        margin = JBUI.insets(2, 6)
                        addActionListener { diffManager.comparePrompts(p.promptId - 1, p.promptId) }
                    })
                }
                row.add(actions, BorderLayout.EAST)
                list.add(row)
                list.add(Box.createVerticalStrut(4))
            }
        }

        frame.contentPane = com.intellij.ui.components.JBScrollPane(list)
        frame.isVisible = true
    }
}
