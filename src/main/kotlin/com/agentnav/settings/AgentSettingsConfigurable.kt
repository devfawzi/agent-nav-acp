package com.agentnav.settings

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*

/**
 * Settings page (Tools → AgentNav ACP) :
 *  - Section "Agents" : liste des profils (builtin + custom) avec Add / Edit / Delete custom
 *  - Section "Binaries" : paths overrides pour claude / npx
 */
class AgentSettingsConfigurable : BoundConfigurable("AgentNav") {

    private val binarySettings = AgentSettings.getInstance()
    private val profilesService = AgentProfilesService.getInstance()

    private val claudeField = TextFieldWithBrowseButton()
    private val npxField = TextFieldWithBrowseButton()
    private val opencodeField = TextFieldWithBrowseButton()
    private val mcpConfigField = TextFieldWithBrowseButton()
    private val injectDiagnosticsCheck = JCheckBox("Auto-inject editor errors/warnings into prompts")
    private val includeWarningsCheck = JCheckBox("Include warnings (not just errors)")
    private val trustSessionCheck = JCheckBox(
        "Trust this session — auto-approve all tools (no Allow/Deny prompts)")
    private val weeklyBudgetField = JTextField(8)
    private val additionalDirsField = JTextField(30)
    private val weeklyBudgetStatusLabel = JBLabel("")
    private val claudeDetectedLabel = JBLabel("")
    private val npxDetectedLabel = JBLabel("")
    private val opencodeDetectedLabel = JBLabel("")
    private val mcpConfigStatusLabel = JBLabel("")

    private val profilesListModel = DefaultListModel<AgentProfile>()
    private val profilesList = JBList(profilesListModel)

    /** Inventaire des MCP : liste verticale "ServerName · type · N tools" + expand des tools. */
    private val mcpInventoryPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = com.intellij.util.ui.UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4)
    }

    init {
        claudeField.addBrowseFolderListener(
            "Select claude binary", null, null,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        )
        npxField.addBrowseFolderListener(
            "Select npx binary", null, null,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        )
        opencodeField.addBrowseFolderListener(
            "Select opencode binary", null, null,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        )
        mcpConfigField.addBrowseFolderListener(
            "Select MCP config JSON file", null, null,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json"),
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        )
        mcpConfigField.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = refreshMcpStatus()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = refreshMcpStatus()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = refreshMcpStatus()
        })

        profilesList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val p = value as? AgentProfile
                if (p != null) {
                    val suffix = if (p.isBuiltin) "  (builtin)" else ""
                    text = "<html><b>${p.displayName}</b>$suffix<br><span style='color:gray;font-size:10px;'>${p.fullCommandLine()}</span></html>"
                }
                border = JBUI.Borders.empty(4, 6)
                return this
            }
        }
    }

    override fun createPanel(): DialogPanel = panel {
        group("Agents") {
            row {
                text(
                    "ACP-compatible agents available. Builtin profiles are read-only. " +
                        "You can add custom agents that implement the ACP protocol " +
                        "(<a href='https://agentclientprotocol.com/'>see compatible agents</a>)."
                )
            }
            row {
                cell(JBScrollPane(profilesList).apply {
                    preferredSize = Dimension(0, 180)
                }).align(AlignX.FILL)
            }
            row {
                button("Add custom…") { showAddCustomDialog() }
                button("Edit") { editSelected() }
                button("Delete") { deleteSelected() }
                button("Test connection") { testSelected() }
            }
        }
        group("MCP servers (Claude Code only)") {
            row {
                text(
                    "Optional path to a JSON file passed to <code>claude --mcp-config &lt;file&gt;</code>. " +
                        "Format: <code>{\"mcpServers\": {\"name\": {\"command\": \"...\", \"args\": [], \"env\": {}}}}</code>. " +
                        "HTTP servers: add <code>\"type\": \"http\"</code>. " +
                        "Leave empty to use claude's global config (managed via <code>claude mcp add</code>)."
                )
            }
            row("MCP config file:") { cell(mcpConfigField).align(AlignX.FILL) }
            row("") { cell(mcpConfigStatusLabel) }
            row {
                text(
                    "Additional directories passed to <code>claude --add-dir</code> " +
                        "(comma-separated absolute paths). Extends claude's filesystem sandbox " +
                        "beyond the project — enables <code>@/abs/path</code> mentions. " +
                        "⚠ Claude can then also write there."
                )
            }
            row("Additional directories:") { cell(additionalDirsField).align(AlignX.FILL) }
            row {
                cell(JBScrollPane(mcpInventoryPanel).apply {
                    preferredSize = Dimension(0, 240)
                    border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1)
                }).align(AlignX.FILL)
            }
            row {
                button("Refresh inventory") { refreshMcpInventory() }
                comment("Servers come from your <code>--mcp-config</code> file. Tools are cached from the last Claude session (sent your 1st prompt yet).")
            }
        }
        group("Permission mode (Claude Code only)") {
            row {
                text(
                    "<b>Trust mode</b> (default): Claude lance avec <code>--dangerously-skip-permissions</code>. " +
                        "Tout est exécuté direct, aucun blocage, aucune card Allow/Deny. " +
                        "Recommandé pour usage perso.<br>" +
                        "<b>Safe mode</b> (uncheck): nous demande confirmation via card Allow/Deny pour " +
                        "chaque commande Bash / outil MCP sensible. Plus sécurisé mais peut bloquer le flow."
                )
            }
            row { cell(trustSessionCheck) }
            row {
                comment("⚠ Switching this mode respawns the Claude process — current chats restart on --resume.")
            }
        }
        group("Editor context injection") {
            row {
                text(
                    "When sending a prompt, automatically append the list of errors/warnings " +
                        "from the currently focused editor (LSP/inspections) — Claude sees them " +
                        "without you copy-pasting. Style: Cursor."
                )
            }
            row { cell(injectDiagnosticsCheck) }
            row { cell(includeWarningsCheck) }
        }
        group("Weekly budget cap") {
            row {
                text(
                    "Set a soft weekly budget (USD). Soft warning at 80%, confirmation prompt at 100%. " +
                        "Set to 0 to disable. Cumulative tracking via Claude's <code>total_cost_usd</code>. " +
                        "Resets every Monday 00:00."
                )
            }
            row("Weekly budget (USD):") {
                cell(weeklyBudgetField)
                button("Reset week counter") { binarySettings.resetWeekCounter(); refreshBudgetStatus() }
            }
            row("") { cell(weeklyBudgetStatusLabel) }
        }
        group("Binaries auto-discovery") {
            row {
                text(
                    "Leave empty for auto-discovery via " +
                        "<code>CLAUDE_CLI_PATH</code> / <code>NPX_PATH</code> env vars, common paths, or <code>which</code>."
                )
            }
            row("Claude Code CLI:") { cell(claudeField).align(AlignX.FILL) }
            row("") { cell(claudeDetectedLabel) }
            row("OpenCode CLI:") { cell(opencodeField).align(AlignX.FILL) }
            row("") { cell(opencodeDetectedLabel) }
            row("npx (OpenCode fallback only):") { cell(npxField).align(AlignX.FILL) }
            row("") { cell(npxDetectedLabel) }
            row {
                button("Auto-detect now") { runAutoDetect() }
            }
        }
    }

    override fun reset() {
        claudeField.text = binarySettings.claudeCliPath
        npxField.text = binarySettings.npxPath
        opencodeField.text = binarySettings.opencodePath
        mcpConfigField.text = binarySettings.mcpConfigPath
        additionalDirsField.text = binarySettings.additionalDirsCsv
        injectDiagnosticsCheck.isSelected = binarySettings.injectDiagnostics
        includeWarningsCheck.isSelected = binarySettings.injectDiagnosticsIncludeWarnings
        trustSessionCheck.isSelected = binarySettings.trustSession
        weeklyBudgetField.text = if (binarySettings.weeklyBudgetUsd > 0) binarySettings.weeklyBudgetUsd.toString() else ""
        refreshDetectedLabels()
        refreshMcpStatus()
        refreshMcpInventory()
        refreshBudgetStatus()
        refreshProfilesList()
    }

    private fun refreshBudgetStatus() {
        val budget = binarySettings.weeklyBudgetUsd
        val current = binarySettings.currentWeekCostUsd()
        weeklyBudgetStatusLabel.text = if (budget <= 0.0) {
            "<html><i>No cap. Current week cost: \$%.4f</i></html>".format(current)
        } else {
            val pct = (current / budget * 100).coerceAtLeast(0.0)
            val color = when {
                pct >= 100 -> "#c62828"
                pct >= 80 -> "#f57c00"
                else -> "gray"
            }
            "<html><span style='color:$color'>\$%.2f / \$%.2f used (%.0f%%)</span></html>"
                .format(current, budget, pct)
        }
    }

    /**
     * Liste les MCP servers du fichier --mcp-config (parsing JSON minimal) et combine avec
     * les tools cachés depuis le dernier `system:init` reçu. Affiche dans un panel scrollable
     * avec un row par server : nom + type + N tools + un toggle pour voir la liste détaillée.
     */
    private fun refreshMcpInventory() {
        mcpInventoryPanel.removeAll()
        val configPath = mcpConfigField.text.trim().ifEmpty { binarySettings.mcpConfigPath }
        val servers = parseMcpConfigServers(configPath)
        val toolsCache = binarySettings.getMcpToolsCache()

        if (servers.isEmpty()) {
            mcpInventoryPanel.add(JBLabel(
                "<html><span style='color:gray;font-style:italic;'>" +
                    "No <code>mcpServers</code> found in the configured file. Set the path above and " +
                    "make sure it has a <code>{\"mcpServers\": {...}}</code> root." +
                    "</span></html>"
            ).apply { border = JBUI.Borders.empty(10) })
            mcpInventoryPanel.revalidate()
            mcpInventoryPanel.repaint()
            return
        }

        servers.forEach { srv ->
            // claude normalise les noms : "claude.ai Gmail" → "claude_ai_Gmail" pour les tools
            val normalized = srv.name.replace(Regex("[^A-Za-z0-9]"), "_")
            val tools = toolsCache[srv.name] ?: toolsCache[normalized] ?: emptyList()
            mcpInventoryPanel.add(buildMcpServerRow(srv, tools))
            mcpInventoryPanel.add(Box.createVerticalStrut(4))
        }
        mcpInventoryPanel.revalidate()
        mcpInventoryPanel.repaint()
    }

    private data class McpServerEntry(val name: String, val type: String, val target: String)

    private fun parseMcpConfigServers(path: String): List<McpServerEntry> {
        if (path.isBlank() || !java.io.File(path).isFile) return emptyList()
        return try {
            val content = java.io.File(path).readText(Charsets.UTF_8)
            val root = com.google.gson.JsonParser.parseString(content).asJsonObject
            val servers = root.getAsJsonObject("mcpServers") ?: return emptyList()
            servers.entrySet().mapNotNull { (name, valEl) ->
                if (!valEl.isJsonObject) return@mapNotNull null
                val obj = valEl.asJsonObject
                val type = obj.get("type")?.asString
                    ?: if (obj.has("url")) "http" else "stdio"
                val target = when {
                    obj.has("url") -> obj.get("url").asString
                    obj.has("command") -> {
                        val cmd = obj.get("command").asString
                        val args = obj.getAsJsonArray("args")?.joinToString(" ") { it.asString } ?: ""
                        "$cmd $args".trim()
                    }
                    else -> "(unknown)"
                }
                McpServerEntry(name, type, target)
            }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            listOf(McpServerEntry("(parse error)", "?", e.message?.take(80) ?: "?"))
        }
    }

    private fun buildMcpServerRow(srv: McpServerEntry, tools: List<String>): JComponent {
        val card = JPanel(BorderLayout()).apply {
            background = com.intellij.util.ui.UIUtil.getTextFieldBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1),
                JBUI.Borders.empty(6, 10)
            )
            maximumSize = Dimension(Int.MAX_VALUE, if (tools.isEmpty()) 50 else 120)
        }
        val typeIcon = when (srv.type) {
            "http", "sse" -> "🌐"
            "stdio" -> "📥"
            else -> "🔌"
        }
        val toolCount = if (tools.isEmpty()) "(no tools cached yet)" else "${tools.size} tool(s)"
        val header = JBLabel(
            "<html><b>$typeIcon ${srv.name}</b>  " +
                "<span style='color:gray;font-size:10px;'>${srv.type} · $toolCount</span><br>" +
                "<span style='color:gray;font-size:10px;'>${srv.target}</span></html>"
        )
        card.add(header, BorderLayout.NORTH)

        if (tools.isNotEmpty()) {
            val toolsHtml = tools.sorted().joinToString("<br>") {
                "<code>$it</code>"
            }
            val pane = JTextPane().apply {
                contentType = "text/html"
                isEditable = false
                isFocusable = true
                background = com.intellij.util.ui.UIUtil.getTextFieldBackground()
                text = "<html><body style='font-family:monospaced;font-size:11px;color:#5b89d9;padding-top:4px;'>" +
                    toolsHtml + "</body></html>"
                border = JBUI.Borders.empty(4, 16, 0, 0)
            }
            card.add(pane, BorderLayout.CENTER)
        }
        return card
    }

    override fun isModified(): Boolean {
        return claudeField.text != binarySettings.claudeCliPath ||
            npxField.text != binarySettings.npxPath ||
            opencodeField.text != binarySettings.opencodePath ||
            mcpConfigField.text != binarySettings.mcpConfigPath ||
            additionalDirsField.text != binarySettings.additionalDirsCsv ||
            injectDiagnosticsCheck.isSelected != binarySettings.injectDiagnostics ||
            includeWarningsCheck.isSelected != binarySettings.injectDiagnosticsIncludeWarnings ||
            trustSessionCheck.isSelected != binarySettings.trustSession ||
            parseBudget(weeklyBudgetField.text) != binarySettings.weeklyBudgetUsd
    }

    override fun apply() {
        binarySettings.claudeCliPath = claudeField.text.trim()
        binarySettings.npxPath = npxField.text.trim()
        binarySettings.opencodePath = opencodeField.text.trim()
        binarySettings.mcpConfigPath = mcpConfigField.text.trim()
        binarySettings.additionalDirsCsv = additionalDirsField.text.trim()
        binarySettings.injectDiagnostics = injectDiagnosticsCheck.isSelected
        binarySettings.injectDiagnosticsIncludeWarnings = includeWarningsCheck.isSelected
        binarySettings.trustSession = trustSessionCheck.isSelected
        binarySettings.weeklyBudgetUsd = parseBudget(weeklyBudgetField.text)
        refreshDetectedLabels()
        refreshMcpStatus()
        refreshBudgetStatus()
    }

    private fun parseBudget(text: String): Double =
        text.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    private fun refreshMcpStatus() {
        val path = mcpConfigField.text.trim()
        mcpConfigStatusLabel.text = computeMcpStatus(path)
    }

    private fun computeMcpStatus(path: String): String {
        if (path.isEmpty()) return "<html><i>Empty → using claude's global MCP config.</i></html>"
        val file = java.io.File(path)
        if (!file.isFile) return "<html>⚠️ File not found: <code>$path</code></html>"
        return try {
            val json = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
            val servers = json.getAsJsonObject("mcpServers")?.keySet()?.toList().orEmpty()
            "<html>✅ ${servers.size} MCP server(s): <code>${servers.joinToString(", ")}</code></html>"
        } catch (e: Exception) {
            "<html>⚠️ Invalid JSON: ${e.message}</html>"
        }
    }

    private fun refreshProfilesList() {
        profilesListModel.clear()
        profilesService.getAllProfiles().forEach { profilesListModel.addElement(it) }
        if (profilesListModel.size > 0) profilesList.selectedIndex = 0
    }

    private fun showAddCustomDialog() {
        val dialog = CustomProfileDialog()
        if (dialog.showAndGet()) {
            profilesService.addCustom(dialog.profileName, dialog.command, dialog.args)
            refreshProfilesList()
        }
    }

    private fun editSelected() {
        val selected = profilesList.selectedValue ?: return
        if (selected.isBuiltin) {
            Messages.showInfoMessage("Builtin profiles cannot be edited.", "Read-only")
            return
        }
        val dialog = CustomProfileDialog(selected)
        if (dialog.showAndGet()) {
            profilesService.updateCustom(selected.id, dialog.profileName, dialog.command, dialog.args)
            refreshProfilesList()
        }
    }

    private fun deleteSelected() {
        val selected = profilesList.selectedValue ?: return
        if (selected.isBuiltin) {
            Messages.showInfoMessage("Builtin profiles cannot be deleted.", "Read-only")
            return
        }
        val ok = Messages.showYesNoDialog(
            "Delete agent '${selected.displayName}'?", "Confirm", Messages.getQuestionIcon()
        )
        if (ok == Messages.YES) {
            profilesService.removeCustom(selected.id)
            refreshProfilesList()
        }
    }

    private fun testSelected() {
        val selected = profilesList.selectedValue ?: return
        val result = AgentConnectionTester.testProfile(selected)
        if (result.success) {
            Messages.showInfoMessage(
                "✅ Connection OK\n\n${result.detail}",
                "Test connection — ${selected.displayName}"
            )
        } else {
            Messages.showErrorDialog(
                "❌ Failed\n\n${result.detail}",
                "Test connection — ${selected.displayName}"
            )
        }
    }

    private fun runAutoDetect() {
        val prevClaude = binarySettings.claudeCliPath
        val prevNpx = binarySettings.npxPath
        val prevOpencode = binarySettings.opencodePath
        binarySettings.claudeCliPath = ""
        binarySettings.npxPath = ""
        binarySettings.opencodePath = ""
        try {
            AgentBinaryResolver.resolveClaudeCli()?.let { claudeField.text = it }
            AgentBinaryResolver.resolveNpx()?.let { npxField.text = it }
            AgentBinaryResolver.resolveOpencode()?.let { opencodeField.text = it }
        } finally {
            binarySettings.claudeCliPath = prevClaude
            binarySettings.npxPath = prevNpx
            binarySettings.opencodePath = prevOpencode
        }
        refreshDetectedLabels()
    }

    private fun refreshDetectedLabels() {
        val detectedClaude = AgentBinaryResolver.resolveClaudeCli()
        claudeDetectedLabel.text = if (detectedClaude != null) {
            "<html>✅ Detected: <code>$detectedClaude</code></html>"
        } else "<html>⚠️ Not detected — install Claude Code or set a path above</html>"
        val detectedOpencode = AgentBinaryResolver.resolveOpencode()
        opencodeDetectedLabel.text = if (detectedOpencode != null) {
            "<html>✅ Detected: <code>$detectedOpencode</code></html>"
        } else "<html>⚠️ Not detected — install OpenCode or use npx fallback</html>"
        val detectedNpx = AgentBinaryResolver.resolveNpx()
        npxDetectedLabel.text = if (detectedNpx != null) {
            "<html>✅ Detected: <code>$detectedNpx</code></html>"
        } else "<html>⚠️ Not detected — only needed if you use OpenCode via npx</html>"
    }
}

/**
 * Modal d'ajout / édition d'un agent custom.
 */
private class CustomProfileDialog(
    existing: AgentProfile? = null
) : DialogWrapper(true) {

    private val nameField = JTextField(existing?.displayName ?: "", 30)
    private val commandField = JTextField(existing?.command ?: "", 30)
    private val argsField = JTextField(existing?.args?.joinToString(",") ?: "", 30)

    val profileName: String get() = nameField.text.trim()
    val command: String get() = commandField.text.trim()
    val args: List<String> get() = argsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    init {
        title = if (existing == null) "Add custom agent" else "Edit agent: ${existing.displayName}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridLayout(0, 1, 4, 4))
        panel.preferredSize = Dimension(520, 220)
        panel.add(JLabel("<html><b>Display name</b></html>"))
        panel.add(nameField)
        panel.add(JLabel("<html><b>Command</b> (binary or first word, e.g. <code>npx</code>, <code>opencode</code>, <code>/path/to/agent</code>)</html>"))
        panel.add(commandField)
        panel.add(JLabel("<html><b>Args</b> (comma-separated, e.g. <code>-y,my-agent-acp</code>)</html>"))
        panel.add(argsField)
        panel.add(JLabel("<html><br><i>Tip: see <a href='https://agentclientprotocol.com/'>agentclientprotocol.com</a> for the list of ACP-compatible agents.</i></html>"))
        return panel
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        if (profileName.isBlank()) return com.intellij.openapi.ui.ValidationInfo("Name required", nameField)
        if (command.isBlank()) return com.intellij.openapi.ui.ValidationInfo("Command required", commandField)
        return null
    }
}
