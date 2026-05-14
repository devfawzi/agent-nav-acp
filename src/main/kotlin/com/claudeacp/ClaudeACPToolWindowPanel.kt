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

    private val chatPanel = ChatPanel(project)
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
        // Refresh la config locale pour ce sid
        ApplicationManager.getApplication().invokeLater {
            inputPanel.refreshConfig(acpService.getSessionConfig(sid))
        }
    }

    fun getSessionId(): String? = mySessionId

    private val statusLabel = JLabel("⏸ Stopped").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 8)
    }

    private val historyButton = JButton("📋 History").apply {
        toolTipText = "Show prompt history"
        margin = JBUI.insets(2, 8)
        font = font.deriveFont(Font.PLAIN, 11f)
        addActionListener { showHistoryPopup() }
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

        root.add(header, BorderLayout.NORTH)
        root.add(chatPanel.getContent(), BorderLayout.CENTER)
        root.add(bottom, BorderLayout.SOUTH)

        inputPanel.onSend { txt, atts ->
            if (acpService.state != ClaudeACPService.State.READY) {
                chatPanel.appendError("Agent not ready (state=${acpService.state})")
                return@onSend
            }
            if (mySessionId == null) {
                // Sécurité : ne devrait plus arriver car Chat 1 claim auto via state listener
                // et Chat 2+ via la factory. Si on tombe ici, on évite tout fallback global.
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
                    }
                }
                chatPanel.appendInfo("Attachments: $names")
            }
            acpService.sendPrompt(txt, targetSessionId = mySessionId, attachments = atts)
        }

        inputPanel.onCancel {
            // Cancel uniquement le prompt de NOTRE session, pas celui en cours global.
            acpService.cancelPrompt(targetSessionId = mySessionId)
        }

        return root
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
            }
        }

        // Les infos/erreurs/stderr globaux ne sont affichés que dans le 1er chat
        if (isFirstChat) {
            acpService.addInfoListener { msg ->
                ApplicationManager.getApplication().invokeLater { chatPanel.appendInfo(msg) }
            }
            acpService.addErrorListener { msg ->
                ApplicationManager.getApplication().invokeLater { chatPanel.appendError(msg) }
            }
            acpService.addStderrListener { msg ->
                ApplicationManager.getApplication().invokeLater { chatPanel.appendStderr(msg) }
            }
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
                ApplicationManager.getApplication().invokeLater { chatPanel.appendToolCall(info) }
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

        // Executing par sid : le bouton Stop/Send ne change que pour le chat en cours.
        acpService.addExecutingListener { executing, sid ->
            if (sid == mySessionId) {
                ApplicationManager.getApplication().invokeLater {
                    inputPanel.setExecutingState(executing)
                }
            }
        }

        // Card de modification de fichier dans le chat de la session concernée
        pendingService.addAddedListener { change ->
            if (matchesMySession(change.triggeredBySessionId)) {
                ApplicationManager.getApplication().invokeLater { chatPanel.appendFileChange(change) }
            }
        }
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
