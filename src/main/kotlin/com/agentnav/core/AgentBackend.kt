package com.agentnav.core

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*


/**
 * Contrat unique pour un backend agent (claude CLI stream-json ou OpenCode ACP).
 *
 * Principe d'isolation : 1 backend = 1 session. Pas de multiplexage interne, pas de
 * broadcast à N consommateurs. Le panel UI possède SON backend et branche ses callbacks
 * directement dessus — aucun listener global, aucun filtre par sessionId.
 *
 * Cycle de vie typique :
 *   1. Construction (avec profile + resumeSid optionnel)
 *   2. Le panel branche les callbacks (onTextChunk, onPermission, etc.)
 *   3. start() : spawn le process / s'inscrit sur le hub ACP partagé
 *   4. onSessionReady fire avec le sessionId définitif (pré-assigné côté CLI, attribué
 *      par le serveur ACP)
 *   5. sendPrompt / cancel / setMode / setModel / etc. au cours de la conv
 *   6. stop() : kill propre
 *
 * Tous les callbacks sont invoqués sur un thread arbitraire — le panel doit dispatcher
 * sur l'EDT (ApplicationManager.invokeLater) avant de toucher Swing.
 */
interface AgentBackend {

    // ─── Identité ────────────────────────────────────────────────────────────────
    /** sessionId actuel. Null tant que onSessionReady n'a pas fired. */
    val sessionId: String?

    /** État courant (synchrone). Les transitions sont aussi notifiées via onStateChange. */
    val state: AgentState

    /** Snapshot de la config (modèles, mode, slash commands, MCP). */
    val config: SessionConfig

    /** Snapshot de l'usage cumulé. */
    val usage: UsageStats

    // ─── Cycle de vie ────────────────────────────────────────────────────────────
    /** Lance le backend (spawn process / register sur hub). Idempotent. */
    fun start()

    /** Arrête proprement (kill process, désinscrit du hub). */
    fun stop()

    // ─── Actions ─────────────────────────────────────────────────────────────────
    /** Envoie un prompt user avec ses pièces jointes. */
    fun sendPrompt(text: String, attachments: List<PromptAttachment> = emptyList())

    /** Interrompt le turn en cours sans tuer le process. */
    fun cancel()

    /** Réponse à un tool interactif (ExitPlanMode approve, AskUserQuestion submit). */
    fun replyToolResult(toolUseId: String, content: String)

    /** Change le permission mode (plan / acceptEdits / bypassPermissions). */
    fun setMode(modeId: String)

    /** Change le modèle. */
    fun setModel(modelId: String)

    /** Change le niveau de thinking/effort. */
    fun setEffort(level: String)

    // ─── Callbacks (le panel les set AVANT start()) ──────────────────────────────
    /** Transition d'état (STARTING → INITIALIZING → READY → …). */
    var onStateChange: ((AgentState) -> Unit)?

    /** sessionId définitif connu (fire UNE fois en CLI, pareil en ACP après session/new). */
    var onSessionReady: ((String) -> Unit)?

    /** Chunk de texte assistant (à concaténer dans la bulle courante). */
    var onTextChunk: ((String) -> Unit)?

    /** Chunk de "thinking" (panneau séparé / italique). */
    var onThoughtChunk: ((String) -> Unit)?

    /** Tool call démarré ou updaté (status running/completed/failed). */
    var onToolCall: ((ToolCallInfo) -> Unit)?

    /** Demande de permission à présenter à l'user (card Allow/Deny). */
    var onPermission: ((PermissionRequest) -> Unit)?

    /** Une session est officiellement en exécution / fini d'exécuter. */
    var onExecuting: ((Boolean) -> Unit)?

    /** Mise à jour de la config (modèle changé, mode changé, MCP ré-énumérés). */
    var onConfigChange: ((SessionConfig) -> Unit)?

    /** Mise à jour de l'usage (tokens/coût) en fin de turn. */
    var onUsage: ((UsageStats) -> Unit)?

    /** Message d'info (statut connexion, info dev). */
    var onInfo: ((String) -> Unit)?

    /** Erreur générique (à afficher dans le chat). */
    var onError: ((String) -> Unit)?

    /** Stderr du process (warn / debug brut). */
    var onStderr: ((String) -> Unit)?

    /** Tool a renvoyé un is_error=true (ex: Bash bloqué par permissions, file not found). */
    var onToolResultError: ((String) -> Unit)?

    /** Memory paths annoncés par claude (system:init.memory_paths). Optionnel. */
    var onMemoryPaths: ((Map<String, String>) -> Unit)?
}
