package com.claudeacp

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Settings persistants pour les chemins des binaires `claude` et `npx`.
 *
 * Permet à l'utilisateur d'override l'auto-discovery via la Settings page IntelliJ.
 * Priorité dans `AgentBinaryResolver` : env var → settings persistants → auto-discovery.
 */
@Service(Service.Level.APP)
@State(name = "ClaudeACPSettings", storages = [Storage("ClaudeACPSettings.xml")])
class AgentSettings : PersistentStateComponent<AgentSettings.State> {

    data class State(
        var claudeCliPath: String = "",
        var npxPath: String = "",
        var opencodePath: String = "",
        /** Path to a JSON file passed to `claude --mcp-config <path>`. Empty = use claude's global config. */
        var mcpConfigPath: String = "",
        /**
         * Cache des derniers tools MCP observés par server, persisté entre runs.
         * Permet à la Settings page d'afficher les tools sans avoir à relancer un claude.
         * Mis à jour à chaque `system:init` du transport CLI.
         * Format : "ServerName" -> "tool1,tool2,tool3"
         */
        var mcpToolsCacheCsv: MutableMap<String, String> = mutableMapOf(),
        /**
         * Si true, à chaque prompt envoyé, on ajoute en fin de message la liste des
         * errors/warnings du buffer éditeur courant (LSP diagnostics). Style Cursor :
         * claude voit directement "fix these errors" sans copier/coller.
         */
        var injectDiagnostics: Boolean = true,
        /** Inclure aussi les WARNINGS (sinon ERRORS only). */
        var injectDiagnosticsIncludeWarnings: Boolean = false
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var claudeCliPath: String
        get() = state.claudeCliPath
        set(value) {
            state.claudeCliPath = value
        }

    var npxPath: String
        get() = state.npxPath
        set(value) {
            state.npxPath = value
        }

    var opencodePath: String
        get() = state.opencodePath
        set(value) {
            state.opencodePath = value
        }

    var mcpConfigPath: String
        get() = state.mcpConfigPath
        set(value) {
            state.mcpConfigPath = value
        }

    var injectDiagnostics: Boolean
        get() = state.injectDiagnostics
        set(value) { state.injectDiagnostics = value }

    var injectDiagnosticsIncludeWarnings: Boolean
        get() = state.injectDiagnosticsIncludeWarnings
        set(value) { state.injectDiagnosticsIncludeWarnings = value }

    fun getClaudeCliPathOrNull(): String? = claudeCliPath.takeIf { it.isNotBlank() }
    fun getNpxPathOrNull(): String? = npxPath.takeIf { it.isNotBlank() }
    fun getOpencodePathOrNull(): String? = opencodePath.takeIf { it.isNotBlank() }
    fun getMcpConfigPathOrNull(): String? = mcpConfigPath.takeIf { it.isNotBlank() && java.io.File(it).isFile }

    /** Cache des tools MCP par server (lus depuis le dernier `system:init` reçu). */
    fun getMcpToolsCache(): Map<String, List<String>> =
        state.mcpToolsCacheCsv.mapValues { it.value.split(",").filter { t -> t.isNotBlank() } }

    /** Met à jour le cache. Appelé par ClaudeACPService au `system:init`. */
    fun updateMcpToolsCache(toolsByServer: Map<String, List<String>>) {
        state.mcpToolsCacheCsv = toolsByServer
            .mapValues { it.value.joinToString(",") }
            .toMutableMap()
    }

    companion object {
        fun getInstance(): AgentSettings = service()
    }
}
