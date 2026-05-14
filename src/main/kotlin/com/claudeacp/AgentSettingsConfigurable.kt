package com.claudeacp

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel

/**
 * Page Settings : Tools → AgentNav ACP.
 * Permet à l'utilisateur d'override les chemins des binaires `claude` et `npx`.
 */
class AgentSettingsConfigurable : BoundConfigurable("AgentNav ACP") {

    private val settings = AgentSettings.getInstance()
    private val claudeField = TextFieldWithBrowseButton()
    private val npxField = TextFieldWithBrowseButton()
    private val claudeDetectedLabel = JBLabel("")
    private val npxDetectedLabel = JBLabel("")

    init {
        claudeField.addBrowseFolderListener(
            "Select claude binary",
            "Path to the Claude Code CLI executable",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        )
        npxField.addBrowseFolderListener(
            "Select npx binary",
            "Path to the npx executable",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        )
    }

    override fun createPanel(): DialogPanel = panel {
        group("Agent binaries") {
            row {
                text(
                    "Leave fields empty to enable auto-discovery. The plugin will look for binaries via " +
                        "<code>CLAUDE_CLI_PATH</code> / <code>NPX_PATH</code> environment variables, " +
                        "common paths (nvm, homebrew, npm-global), then <code>which</code> as last resort."
                )
            }
            row("Claude Code CLI:") {
                cell(claudeField).align(AlignX.FILL)
            }
            row("") {
                cell(claudeDetectedLabel)
            }
            row("npx:") {
                cell(npxField).align(AlignX.FILL)
            }
            row("") {
                cell(npxDetectedLabel)
            }
            row {
                button("Auto-detect now") {
                    runAutoDetect()
                }
            }
        }
    }

    override fun reset() {
        claudeField.text = settings.claudeCliPath
        npxField.text = settings.npxPath
        refreshDetectedLabels()
    }

    override fun isModified(): Boolean {
        return claudeField.text != settings.claudeCliPath ||
            npxField.text != settings.npxPath
    }

    override fun apply() {
        settings.claudeCliPath = claudeField.text.trim()
        settings.npxPath = npxField.text.trim()
        refreshDetectedLabels()
    }

    private fun runAutoDetect() {
        // Temporairement vider les settings pour que le resolver ignore la valeur cachée
        val prevClaude = settings.claudeCliPath
        val prevNpx = settings.npxPath
        settings.claudeCliPath = ""
        settings.npxPath = ""
        try {
            AgentBinaryResolver.resolveClaudeCli()?.let { claudeField.text = it }
            AgentBinaryResolver.resolveNpx()?.let { npxField.text = it }
        } finally {
            settings.claudeCliPath = prevClaude
            settings.npxPath = prevNpx
        }
        refreshDetectedLabels()
    }

    private fun refreshDetectedLabels() {
        val detectedClaude = AgentBinaryResolver.resolveClaudeCli()
        claudeDetectedLabel.text = if (detectedClaude != null) {
            "<html>✅ Detected: <code>$detectedClaude</code></html>"
        } else {
            "<html>⚠️ Not detected — install Claude Code or set a path above</html>"
        }
        val detectedNpx = AgentBinaryResolver.resolveNpx()
        npxDetectedLabel.text = if (detectedNpx != null) {
            "<html>✅ Detected: <code>$detectedNpx</code></html>"
        } else {
            "<html>⚠️ Not detected — install Node.js or set a path above</html>"
        }
    }
}
