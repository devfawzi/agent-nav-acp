package com.agentnav.settings

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

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
         * Caches des slash commands / skills / MCP servers du dernier `system:init`, persistés
         * entre runs. Le system:init n'arrive qu'APRÈS le 1er prompt d'une session : sans ces
         * caches, le popup `/` et le picker /mcp seraient vides au démarrage d'un chat
         * (contrairement au TUI claude qui affiche tout immédiatement).
         */
        var slashCommandsCacheCsv: String = "",
        var skillsCacheCsv: String = "",
        /** Format : "name:status,name:status". */
        var mcpServersCacheCsv: String = "",
        /**
         * Si true, à chaque prompt envoyé, on ajoute en fin de message la liste des
         * errors/warnings du buffer éditeur courant (LSP diagnostics). Style Cursor :
         * claude voit directement "fix these errors" sans copier/coller.
         */
        var injectDiagnostics: Boolean = true,
        /** Inclure aussi les WARNINGS (sinon ERRORS only). */
        var injectDiagnosticsIncludeWarnings: Boolean = false,
        /**
         * Plafond hebdomadaire en $. 0 = pas de limite. Soft warning à 80%, hard stop à 100%
         * (avec confirmation pour outrepasser). Calculé sur les events `total_cost_usd` cumulés.
         */
        var weeklyBudgetUsd: Double = 0.0,
        /** Coût cumulé sur la semaine en cours (rolling 7 days). */
        var currentWeekCostUsd: Double = 0.0,
        /** Timestamp epoch ms du début de la semaine en cours (lundi 00:00 local). */
        var currentWeekStartMs: Long = 0L,
        /**
         * Mode "Trust this session" : si true, claude lance avec
         * `--dangerously-skip-permissions` → aucune card Allow/Deny, tout passe direct.
         * Si false, claude lance avec `--permission-prompt-tool stdio` et nos cards
         * sont affichées pour chaque Bash/MCP/etc. dangereux.
         *
         * Default false : par défaut on demande à l'user via cards. Cocher pour zéro friction
         * (machine perso, contexte trusté).
         */
        var trustSession: Boolean = false
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

    var weeklyBudgetUsd: Double
        get() = state.weeklyBudgetUsd
        set(value) { state.weeklyBudgetUsd = value }

    var trustSession: Boolean
        get() = state.trustSession
        set(value) { state.trustSession = value }

    /**
     * Ajoute un coût au compteur de la semaine en cours. Si on bascule de semaine, reset
     * le compteur. Retourne le nouveau total cumulé.
     */
    fun addToCurrentWeek(deltaUsd: Double): Double {
        val now = System.currentTimeMillis()
        val weekStart = startOfCurrentWeekMs()
        if (state.currentWeekStartMs != weekStart) {
            state.currentWeekStartMs = weekStart
            state.currentWeekCostUsd = 0.0
        }
        state.currentWeekCostUsd += deltaUsd
        return state.currentWeekCostUsd
    }

    fun currentWeekCostUsd(): Double {
        val weekStart = startOfCurrentWeekMs()
        if (state.currentWeekStartMs != weekStart) {
            // Si la semaine a changé, on reset (lecture seule mais on aligne le state).
            state.currentWeekStartMs = weekStart
            state.currentWeekCostUsd = 0.0
        }
        return state.currentWeekCostUsd
    }

    fun resetWeekCounter() {
        state.currentWeekStartMs = startOfCurrentWeekMs()
        state.currentWeekCostUsd = 0.0
    }

    /** Lundi 00:00 local de la semaine courante en epoch ms. */
    private fun startOfCurrentWeekMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        // Aligne sur lundi (DAY_OF_WEEK : SUNDAY=1, MONDAY=2)
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val mondayOffset = if (dow == java.util.Calendar.SUNDAY) -6 else java.util.Calendar.MONDAY - dow
        cal.add(java.util.Calendar.DAY_OF_MONTH, mondayOffset)
        return cal.timeInMillis
    }

    fun getClaudeCliPathOrNull(): String? = claudeCliPath.takeIf { it.isNotBlank() }
    fun getNpxPathOrNull(): String? = npxPath.takeIf { it.isNotBlank() }
    fun getOpencodePathOrNull(): String? = opencodePath.takeIf { it.isNotBlank() }
    fun getMcpConfigPathOrNull(): String? = mcpConfigPath.takeIf { it.isNotBlank() && java.io.File(it).isFile }

    /** Cache des tools MCP par server (lus depuis le dernier `system:init` reçu). */
    fun getMcpToolsCache(): Map<String, List<String>> =
        state.mcpToolsCacheCsv.mapValues { it.value.split(",").filter { t -> t.isNotBlank() } }

    /** Met à jour le cache. Appelé par ClaudeCliBackend au `system:init`. */
    fun updateMcpToolsCache(toolsByServer: Map<String, List<String>>) {
        state.mcpToolsCacheCsv = toolsByServer
            .mapValues { it.value.joinToString(",") }
            .toMutableMap()
    }

    fun getSlashCommandsCache(): List<String> =
        state.slashCommandsCacheCsv.split(",").filter { it.isNotBlank() }

    fun updateSlashCommandsCache(cmds: List<String>) {
        if (cmds.isNotEmpty()) state.slashCommandsCacheCsv = cmds.joinToString(",")
    }

    fun getSkillsCache(): List<String> =
        state.skillsCacheCsv.split(",").filter { it.isNotBlank() }

    fun updateSkillsCache(skills: List<String>) {
        if (skills.isNotEmpty()) state.skillsCacheCsv = skills.joinToString(",")
    }

    fun getMcpServersCache(): Map<String, String> =
        state.mcpServersCacheCsv.split(",")
            .filter { it.contains(":") }
            .associate { it.substringBeforeLast(":") to it.substringAfterLast(":") }

    fun updateMcpServersCache(servers: Map<String, String>) {
        if (servers.isNotEmpty()) {
            state.mcpServersCacheCsv = servers.entries.joinToString(",") { "${it.key}:${it.value}" }
        }
    }

    companion object {
        fun getInstance(): AgentSettings = service()
    }
}
