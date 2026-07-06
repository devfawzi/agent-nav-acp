package com.claudeacp

import com.claudeacp.core.AgentState
import com.claudeacp.core.ChatSession
import com.claudeacp.core.PermissionRequest
import com.claudeacp.core.SessionConfig
import com.claudeacp.core.ToolCallInfo
import com.claudeacp.core.UsageStats
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

/**
 * Panel UI d'un chat. Possède SON propre ChatSession (= son backend isolé). Toutes les
 * communications agent → UI passent par les callbacks du backend ; aucun listener global,
 * aucun filtre par sessionId. Isolation totale entre chats par construction.
 */
class ClaudeACPToolWindowPanel(
    private val project: Project,
    initialChatSession: ChatSession,
    private val isFirstChat: Boolean = true
) {

    /** Mutable car l'user peut changer d'agent avant le 1er prompt → swap propre du backend. */
    @Volatile
    private var chatSession: ChatSession = initialChatSession
    private val backend get() = chatSession.backend
    private val acpService = project.getService(ClaudeACPService::class.java)
    private val historyService = project.getService(PromptHistoryService::class.java)
    private val diffManager = project.getService(DiffViewerManager::class.java)

    /** Close propre du backend, appelé par la factory à la fermeture du content. */
    fun closeSession() {
        chatSession.close()
    }

    /** Swap d'agent profile : kill backend courant, en spawne un nouveau avec le profile demandé.
     *  Autorisé uniquement tant qu'aucun prompt n'a été envoyé (hasAutoRenamed == false). */
    fun swapAgentProfile(newProfile: AgentProfile) {
        if (hasAutoRenamed) {
            chatPanel.appendError("Cannot switch agent after a prompt was sent. Open a new chat instead.")
            return
        }
        PluginLogService.getInstance(project).info("panel",
            "🔄 Swap agent profile: ${chatSession.profile.id} → ${newProfile.id}")
        chatSession.close()
        chatSession = ChatSession(project, newProfile)
        wireBackend()
        // Reset UI state pour le nouveau backend
        statusLabel.text = "🚀 Starting…"
        statusLabel.foreground = JBColor.foreground()
        sessionLabel.isVisible = false
        chatSession.start()
        chatSession.sessionId?.let { updateSessionLabel(it) }
    }

    private val chatPanel = ChatPanel(project) { toolUseId, replyText, switchModeTo ->
        // ExitPlanMode Approve / AskUserQuestion Submit / Skip / Reject : on envoie le
        // tool_result attendu par claude, et si demandé on switch le permission mode
        // (typiquement plan → acceptEdits après approbation). L'ordre compte : switch
        // d'abord pour que les Write/Edit suivants soient autorisés.
        if (switchModeTo != null) {
            chatSession.setMode(switchModeTo)
        }
        chatSession.replyToolResult(toolUseId, replyText)
    }
    private val pendingPanel = PendingChangesPanel(project)
    private val pendingService = project.getService(PendingChangesService::class.java)

    /** sessionId de notre chat, fourni par le backend. Stable dès la construction de ChatSession. */
    private val mySessionId: String? get() = backend.sessionId

    private val inputPanel = PromptInputPanel(
        project = project,
        getMySessionId = { mySessionId },
        setModelCallback = { chatSession.setModel(it) },
        setModeCallback = { chatSession.setMode(it) },
        setEffortCallback = { chatSession.setEffort(it) },
        onAgentSwitchRequested = { newProfile -> swapAgentProfile(newProfile) }
    )

    /** Callback set par la factory pour renommer le content (le tab) du tool window. */
    var renameContentCallback: ((String) -> Unit)? = null

    /** Indique si on a déjà auto-renommé via le 1er prompt (pour ne pas écraser un rename manuel). */
    @Volatile
    private var hasAutoRenamed = false

    /** Affiche la card permission + active le bandeau "Waiting for approval". */
    private fun showPermissionCard(req: PermissionRequest) {
        chatPanel.appendPermissionRequest(req)
        val preview = formatPermissionPreview(req.toolName, req.toolInput)
        setActivityText("🔐 Waiting for your approval — $preview · click Allow/Deny in the chat ⬇")
    }

    private fun updateSessionLabel(sid: String) {
        val short = sid.take(8)
        sessionLabel.text = "🔗 $short…"
        sessionLabel.toolTipText = "<html>Session: <code>$sid</code><br>" +
            "Click to copy <code>claude --resume $sid</code></html>"
        sessionLabel.isVisible = true
    }

    fun getSessionId(): String? = mySessionId

    /** Compat ascendante : la factory utilisait setSessionId pour les chats 2+. Désormais
     *  le sid est connu dès la construction du ChatSession, on logue juste pour debug. */
    @Deprecated("ChatSession owns the sessionId. Kept as no-op for legacy callers.")
    fun setSessionId(sid: String) {
        PluginLogService.getInstance(project).debug("panel",
            "Legacy setSessionId($sid) called — ignored (chatSession.sessionId=${chatSession.sessionId})")
    }

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

        // Branche les callbacks du backend AVANT de le démarrer pour ne rien rater.
        wireBackend()

        ApplicationManager.getApplication().invokeLater {
            checkPrerequisitesAndConnect()
        }

        return rootCard
    }

    private fun checkPrerequisitesAndConnect() {
        val prereqs = Prerequisites.check()
        if (!prereqs.allOk) {
            onboardingPanel.update(prereqs)
            showCard("onboarding")
            statusLabel.text = "❌ Setup required"
            statusLabel.foreground = JBColor.RED
            return
        }

        showCard("chat")
        if (backend.state == AgentState.STOPPED || backend.state == AgentState.ERROR) {
            chatPanel.appendInfo("Starting Claude…")
            chatSession.start()
        }
        // Le sid est connu dès la construction du ChatSession (preAssignedSid CLI),
        // on peut donc afficher le label tout de suite.
        mySessionId?.let { updateSessionLabel(it) }
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
            if (backend.state != AgentState.READY) {
                chatPanel.appendError("Agent not ready (state=${backend.state})")
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
            val settings = AgentSettings.getInstance()
            val enrichedText = if (settings.injectDiagnostics) {
                val diags = EditorDiagnosticsGrabber.buildDiagnosticsContext(
                    project,
                    errorsOnly = !settings.injectDiagnosticsIncludeWarnings
                )
                if (diags != null) txt + diags else txt
            } else txt
            if (!checkBudgetBeforeSend(settings)) return@onSend
            chatSession.sendPrompt(enrichedText, atts)
        }

        inputPanel.onCancel {
            chatSession.cancel()
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
                        onPick = { chatSession.setModel(opt.id) }
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
                        onPick = { chatSession.setMode(opt.id) }
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
                        onPick = { chatSession.setEffort(opt.id) }
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

    /**
     * Branche TOUS les callbacks du backend directement sur l'UI. Aucun listener global,
     * aucun filtre par sid. C'est garanti par construction qu'on ne reçoit QUE nos events.
     */
    private fun wireBackend() {
        val ui = ApplicationManager.getApplication()

        backend.onStateChange = { newState ->
            ui.invokeLater {
                statusLabel.text = when (newState) {
                    AgentState.STOPPED -> "⏸ Stopped"
                    AgentState.STARTING -> "🚀 Starting..."
                    AgentState.INITIALIZING -> "🤝 Initializing..."
                    AgentState.CREATING_SESSION -> "📝 Creating session..."
                    AgentState.READY -> "✅ Ready"
                    AgentState.ERROR -> "❌ Error"
                }
                statusLabel.foreground = when (newState) {
                    AgentState.READY -> JBColor.GREEN
                    AgentState.ERROR -> JBColor.RED
                    else -> JBColor.foreground()
                }
                inputPanel.setReady(newState == AgentState.READY)
                if (newState == AgentState.READY) {
                    inputPanel.refreshConfig(backend.config)
                }
            }
        }

        backend.onSessionReady = { sid ->
            ui.invokeLater {
                updateSessionLabel(sid)
                inputPanel.refreshConfig(backend.config)
            }
        }

        backend.onTextChunk = { text ->
            ui.invokeLater {
                chatPanel.appendAssistantChunk(text)
                setActivityText("✍ Writing reply…")
            }
        }

        backend.onThoughtChunk = { text ->
            ui.invokeLater {
                chatPanel.appendThinkingChunk(text)
                setActivityText("🤔 Thinking…")
            }
        }

        backend.onToolCall = { info ->
            ui.invokeLater {
                chatPanel.appendToolCall(info)
                updateActivityBar(info)
                watchStuckTool(info)
            }
        }

        backend.onPermission = { req ->
            ui.invokeLater {
                showPermissionCard(req)
            }
        }

        backend.onExecuting = { exec ->
            ui.invokeLater {
                inputPanel.setExecutingState(exec)
                if (exec) setActivityText("🤔 Thinking…") else hideActivityBar()
            }
        }

        backend.onConfigChange = { config ->
            ui.invokeLater {
                inputPanel.refreshConfig(config)
            }
        }

        backend.onUsage = { stats ->
            ui.invokeLater { renderUsage(stats) }
        }

        backend.onInfo = { msg ->
            ui.invokeLater { chatPanel.appendInfo(msg) }
        }

        backend.onError = { msg ->
            ui.invokeLater { chatPanel.appendError(msg) }
        }

        backend.onStderr = { msg ->
            ui.invokeLater { chatPanel.appendStderr(msg) }
        }

        backend.onToolResultError = { msg ->
            ui.invokeLater { chatPanel.appendError(msg) }
        }

        // PendingChange (diff inline + side panel) — vient de PendingChangesService project-level
        // mais filtré par sid du change (les backends taggent leur sid à l'addOrUpdate).
        pendingService.addAddedListener { change ->
            if (change.triggeredBySessionId == mySessionId) {
                ui.invokeLater { chatPanel.appendFileChange(change) }
            }
        }
    }

    private fun setActivityText(text: String) {
        activityBar.text = text
        activityBar.isVisible = true
    }

    private fun hideActivityBar() {
        activityBar.isVisible = false
        // Quand l'exécution s'arrête, on clear aussi les watchers stuck.
        stuckTools.clear()
    }

    /** Tools actuellement in_progress + leur timestamp de démarrage. Pour le watchdog. */
    private val stuckTools = java.util.concurrent.ConcurrentHashMap<String, StuckEntry>()
    private data class StuckEntry(val name: String, val detail: String?, val startMs: Long, var warned: Boolean = false)

    /**
     * Watchdog : si un tool reste in_progress > 30s, on push un message d'aide dans le chat
     * qui explique ce qui se passe et les causes probables (permission, network, infinite
     * loop). Évite que l'user reste perdu devant un "Running" silencieux.
     */
    private fun watchStuckTool(info: ToolCallInfo) {
        val id = info.toolCallId ?: return
        when (info.status) {
            "in_progress" -> {
                stuckTools[id] = StuckEntry(info.title, info.path ?: info.command ?: info.detail, System.currentTimeMillis())
                // Schedule un check à 30s
                javax.swing.Timer(30_000) { _ ->
                    val entry = stuckTools[id] ?: return@Timer
                    if (entry.warned) return@Timer
                    entry.warned = true
                    val elapsed = (System.currentTimeMillis() - entry.startMs) / 1000
                    val detail = entry.detail?.let { " · $it" } ?: ""
                    val causes = stuckCauseHints(entry.name)
                    chatPanel.appendInfo(
                        "⏱ Tool '${entry.name}'$detail has been running for ${elapsed}s.\n" +
                            "Common causes: $causes"
                    )
                }.apply { isRepeats = false }.start()
            }
            "completed", "error", "failed" -> stuckTools.remove(id)
            else -> {}
        }
    }

    /** Hints contextualisés selon le tool pour aider l'user à débloquer. */
    private fun stuckCauseHints(toolName: String): String = when (toolName) {
        "Bash" -> "(1) permission request not shown — check for a yellow Allow/Deny card above; " +
            "(2) command is an interactive prompt (vim, less, etc.) — Bash blocked; " +
            "(3) network call timeout. Action: click ⏹ Stop and reformulate."
        "WebFetch", "WebSearch" -> "Network timeout or upstream blocking. Action: ⏹ Stop and retry."
        "Read" -> "File is huge or locked by another process. Action: ⏹ Stop and ask Claude to read a smaller range."
        "Edit", "Write", "MultiEdit" -> "File write denied (permissions) or a hook is hanging. Check the Logs panel."
        "Task" -> "Sub-agent is itself stuck. Action: ⏹ Stop, then ask the parent agent to retry without delegation."
        else -> "Unknown blocker. Try ⏹ Stop, or open the Logs panel (🖥 in title bar) for details."
    }

    /** Petit preview du tool input pour le bandeau permission. */
    private fun formatPermissionPreview(toolName: String, toolInput: String?): String {
        if (toolInput.isNullOrBlank()) return toolName
        return try {
            val obj = com.google.gson.JsonParser.parseString(toolInput).asJsonObject
            val main = when (toolName) {
                "Bash" -> obj.get("command")?.asString
                "Read", "Edit", "Write", "MultiEdit" ->
                    obj.get("file_path")?.asString ?: obj.get("path")?.asString
                else -> obj.entrySet().firstOrNull()?.let { "${it.key}=${it.value}" }
            }
            "$toolName: ${main?.take(60) ?: ""}"
        } catch (_: Exception) { toolName }
    }

    private fun updateActivityBar(info: ToolCallInfo) {
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

    /**
     * Retourne true si on peut envoyer le prompt, false si l'user annule à cause du budget.
     * Logique :
     *  - budget == 0 → pas de cap, toujours OK
     *  - cumul < 80% → silent OK
     *  - 80% ≤ cumul < 100% → warning info dans le chat (continue quand même)
     *  - cumul ≥ 100% → dialog Yes/No "Continue past budget?"
     */
    private fun checkBudgetBeforeSend(settings: AgentSettings): Boolean {
        val budget = settings.weeklyBudgetUsd
        if (budget <= 0.0) return true
        val cost = settings.currentWeekCostUsd()
        val ratio = cost / budget
        when {
            ratio >= 1.0 -> {
                val choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                    project,
                    "Weekly budget reached: $%.2f / $%.2f (%.0f%%).\n\nContinue this turn anyway?"
                        .format(cost, budget, ratio * 100),
                    "Budget cap reached",
                    "Continue", "Stop",
                    com.intellij.openapi.ui.Messages.getWarningIcon()
                )
                if (choice != com.intellij.openapi.ui.Messages.YES) {
                    chatPanel.appendInfo("Prompt cancelled (weekly budget reached).")
                    return false
                }
            }
            ratio >= 0.8 -> {
                chatPanel.appendInfo("⚠ Budget warning: $%.2f / $%.2f (%.0f%%)".format(cost, budget, ratio * 100))
            }
        }
        return true
    }

    private fun renderUsage(stats: UsageStats) {
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

    /** Renommé manuellement par l'utilisateur (action "Rename Chat"). */
    fun renameChat(newName: String) {
        hasAutoRenamed = true // empêche l'auto-rename ultérieur
        renameContentCallback?.invoke(newName)
    }

    /** Permet à AddSelectionToChatAction (ou autre extension) d'ajouter un attachment. */
    fun addAttachmentExternal(att: PromptAttachment) {
        inputPanel.addAttachmentExternal(att)
    }

    /** True si ce panel correspond à l'onglet actuellement sélectionné dans la tool window. */
    private fun isCurrentlySelectedInToolWindow(): Boolean {
        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("AgentNav ACP") ?: return false
        val selected = tw.contentManager.selectedContent ?: return false
        val selectedPanel = selected.getUserData(ClaudeACPToolWindowFactory.PANEL_KEY)
        return selectedPanel === this
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
