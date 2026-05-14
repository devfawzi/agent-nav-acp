package com.claudeacp

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
class AgentSettingsConfigurable : BoundConfigurable("AgentNav ACP") {

    private val binarySettings = AgentSettings.getInstance()
    private val profilesService = AgentProfilesService.getInstance()

    private val claudeField = TextFieldWithBrowseButton()
    private val npxField = TextFieldWithBrowseButton()
    private val opencodeField = TextFieldWithBrowseButton()
    private val claudeDetectedLabel = JBLabel("")
    private val npxDetectedLabel = JBLabel("")
    private val opencodeDetectedLabel = JBLabel("")

    private val profilesListModel = DefaultListModel<AgentProfile>()
    private val profilesList = JBList(profilesListModel)

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
            row("npx:") { cell(npxField).align(AlignX.FILL) }
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
        refreshDetectedLabels()
        refreshProfilesList()
    }

    override fun isModified(): Boolean {
        return claudeField.text != binarySettings.claudeCliPath ||
            npxField.text != binarySettings.npxPath ||
            opencodeField.text != binarySettings.opencodePath
    }

    override fun apply() {
        binarySettings.claudeCliPath = claudeField.text.trim()
        binarySettings.npxPath = npxField.text.trim()
        binarySettings.opencodePath = opencodeField.text.trim()
        refreshDetectedLabels()
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
        } else "<html>⚠️ Not detected — install Node.js or set a path above</html>"
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
