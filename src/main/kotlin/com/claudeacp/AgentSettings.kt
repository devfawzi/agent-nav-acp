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
        var mcpConfigPath: String = ""
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

    fun getClaudeCliPathOrNull(): String? = claudeCliPath.takeIf { it.isNotBlank() }
    fun getNpxPathOrNull(): String? = npxPath.takeIf { it.isNotBlank() }
    fun getOpencodePathOrNull(): String? = opencodePath.takeIf { it.isNotBlank() }
    fun getMcpConfigPathOrNull(): String? = mcpConfigPath.takeIf { it.isNotBlank() && java.io.File(it).isFile }

    companion object {
        fun getInstance(): AgentSettings = service()
    }
}
