package com.agentnav.core

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

/**
 * Données canoniques échangées entre un AgentBackend (CLI claude ou ACP OpenCode) et l'UI.
 *
 * Principe : ces types ne portent PAS le sessionId — chaque backend est dédié à une session
 * unique, le sessionId est implicite. Si une couche projet-level (history, pending changes)
 * a besoin de l'id, le panel le passe en paramètre séparé.
 */

enum class AgentState { STOPPED, STARTING, INITIALIZING, CREATING_SESSION, READY, ERROR }

/** Usage cumulé d'une session : tokens et coût $. */
data class UsageStats(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
    val turnCount: Int = 0
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens
}

/** Option d'un menu select (model, permission mode, etc.). */
data class SelectOption(val id: String, val name: String, val description: String? = null)

/** Option configurable (ex: thinking effort) avec ses valeurs possibles. */
data class ConfigOption(
    val id: String,
    val name: String,
    val type: String,
    val options: List<SelectOption> = emptyList(),
    val currentValue: String? = null
)

/** MCP server connu (parsé depuis system:init claude ou available_commands ACP). */
data class McpServerInfo(val name: String, val status: String)

/** Configuration courante d'une session : modèles dispo, mode actif, slash commands, etc. */
data class SessionConfig(
    val models: List<SelectOption> = emptyList(),
    val modes: List<SelectOption> = emptyList(),
    val configOptions: List<ConfigOption> = emptyList(),
    val currentModelId: String? = null,
    val currentModeId: String? = null,
    val currentConfigValues: Map<String, String> = emptyMap(),
    val slashCommands: List<String> = emptyList(),
    val mcpServers: List<McpServerInfo> = emptyList(),
    val mcpTools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val agents: List<String> = emptyList()
)

/**
 * Tool call émis par l'agent (Bash, Edit, Read, Task, etc.).
 * sessionId : conservé pour transition — préfère utiliser le backend qui a émis.
 */
data class ToolCallInfo(
    val toolCallId: String?,
    val title: String,
    val kind: String?,
    val status: String?,
    val path: String?,
    val command: String?,
    val sessionId: String?,
    val writeContent: String? = null,
    val editOldString: String? = null,
    val editNewString: String? = null,
    val permissionMode: String? = null,
    val detail: String? = null,
    val planContent: String? = null,
    val userQuestionsJson: String? = null
)

/**
 * Demande de permission émise par l'agent quand un tool sensible (Bash arbitraire, MCP, etc.)
 * doit être confirmé. respondAllow / respondDeny / respondAllowAlways sont des callbacks
 * one-shot — appeler plusieurs fois n'a aucun effet (idempotent côté backend).
 *
 * `permissionSuggestionsJson` : JSON brut des suggestions de règles à appliquer pour ne plus
 * redemander (claude 2.1+). Si non-null, l'UI peut proposer un bouton "Allow always".
 */
data class PermissionRequest(
    val requestId: String,
    val toolName: String,
    val toolInput: String?,
    val sessionId: String?,
    val respondAllow: () -> Unit,
    val respondDeny: (reason: String?) -> Unit,
    val respondAllowAlways: (() -> Unit)? = null,
    val permissionSuggestionsJson: String? = null
)
