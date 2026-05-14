package com.claudeacp

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Snapshot d'un fichier à un instant T
 */
data class FileSnapshot(
    val filepath: String,
    val content: String,
    val timestamp: Long
)

/**
 * Snapshot complet d'un prompt avec tous les fichiers modifiés
 */
data class PromptSnapshot(
    val promptId: Int,
    val promptText: String,
    val sessionId: String?,
    val timestamp: Long,
    val filesBefore: MutableMap<String, FileSnapshot> = mutableMapOf(),
    val filesAfter: MutableMap<String, FileSnapshot> = mutableMapOf()
)

/**
 * Service qui gère l'historique de tous les prompts et leurs changements
 * 
 * Permet de :
 * - Voir les changements d'un prompt spécifique
 * - Comparer 2 prompts entre eux
 * - Naviguer dans l'historique
 */
@Service(Service.Level.PROJECT)
class PromptHistoryService(private val project: Project) {

    private val log = thisLogger()
    private val prompts = mutableListOf<PromptSnapshot>()
    private var currentPrompt: PromptSnapshot? = null
    
    // Listeners notifiés quand un prompt est ajouté/modifié
    private val listeners = mutableListOf<() -> Unit>()

    /**
     * Démarre l'enregistrement d'un nouveau prompt
     */
    fun startPrompt(promptText: String, sessionId: String? = null) {
        val promptId = prompts.size + 1
        currentPrompt = PromptSnapshot(
            promptId = promptId,
            promptText = promptText,
            sessionId = sessionId,
            timestamp = System.currentTimeMillis()
        )
        prompts.add(currentPrompt!!)
        log.info("Started prompt #$promptId (sid=$sessionId): ${promptText.take(50)}")
        notifyListeners()
    }

    fun getPromptsForSession(sessionId: String?): List<PromptSnapshot> {
        if (sessionId == null) return getAllPrompts()
        return prompts.filter { it.sessionId == sessionId }
    }

    /**
     * Capture le contenu d'un fichier AVANT modification
     */
    fun captureFileBefore(filepath: String, content: String) {
        currentPrompt?.let { prompt ->
            // Ne capturer que si pas déjà capturé pour ce prompt
            if (!prompt.filesBefore.containsKey(filepath)) {
                prompt.filesBefore[filepath] = FileSnapshot(
                    filepath = filepath,
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
                log.info("Captured BEFORE for prompt #${prompt.promptId}: $filepath")
            }
        }
    }

    /**
     * Capture le contenu d'un fichier APRÈS modification
     */
    fun captureFileAfter(filepath: String, content: String) {
        currentPrompt?.let { prompt ->
            prompt.filesAfter[filepath] = FileSnapshot(
                filepath = filepath,
                content = content,
                timestamp = System.currentTimeMillis()
            )
            log.info("Captured AFTER for prompt #${prompt.promptId}: $filepath")
            notifyListeners()
        }
    }

    /**
     * Termine le prompt courant
     */
    fun endPrompt() {
        currentPrompt = null
    }

    /**
     * Remap le sessionId du prompt courant (utilisé en mode CLI quand le placeholder
     * "pending-..." est remplacé par le vrai sid une fois `system:init` reçu).
     */
    fun remapCurrentSessionId(newSessionId: String) {
        currentPrompt?.let { old ->
            val replaced = old.copy(sessionId = newSessionId)
            // PromptSnapshot est data class avec mutableMaps : on remplace dans la liste
            val idx = prompts.indexOf(old)
            if (idx >= 0) {
                // Préserver les références aux maps (copy() les recrée vides sans le constructor)
                // → on créé manuellement avec les anciens maps
                val withMaps = PromptSnapshot(
                    promptId = old.promptId,
                    promptText = old.promptText,
                    sessionId = newSessionId,
                    timestamp = old.timestamp,
                    filesBefore = old.filesBefore,
                    filesAfter = old.filesAfter
                )
                prompts[idx] = withMaps
                currentPrompt = withMaps
                log.info("Remapped prompt #${old.promptId} sid → $newSessionId")
            }
        }
    }

    /**
     * Indique si un prompt est en cours (utilisé par le VFS listener pour filtrer
     * les changements de fichiers qui appartiennent à un prompt actif).
     */
    fun hasActivePrompt(): Boolean = currentPrompt != null

    /**
     * Récupère le snapshot du prompt courant (peut être null).
     */
    fun currentPromptId(): Int? = currentPrompt?.promptId

    /**
     * Récupère tous les prompts
     */
    fun getAllPrompts(): List<PromptSnapshot> = prompts.toList()

    /**
     * Récupère un prompt par son ID
     */
    fun getPrompt(id: Int): PromptSnapshot? = prompts.find { it.promptId == id }

    /**
     * Récupère le contenu d'un fichier pour un prompt donné
     * Cherche d'abord dans "after", sinon dans "before"
     */
    fun getFileContentAtPrompt(promptId: Int, filepath: String): String? {
        val prompt = getPrompt(promptId) ?: return null
        return prompt.filesAfter[filepath]?.content 
            ?: prompt.filesBefore[filepath]?.content
    }

    /**
     * Calcule le diff entre deux prompts pour un fichier
     * 
     * @return Pair(contenuAvant, contenuAprès) ou null si pas trouvable
     */
    fun getDiffBetweenPrompts(
        fromPromptId: Int,
        toPromptId: Int,
        filepath: String
    ): Pair<String, String>? {
        // Le contenu "avant" = état après le prompt source
        val before = getFileContentAtPrompt(fromPromptId, filepath) ?: return null
        // Le contenu "après" = état après le prompt cible
        val after = getFileContentAtPrompt(toPromptId, filepath) ?: return null
        return before to after
    }

    /**
     * Récupère tous les fichiers modifiés dans l'ensemble de l'historique
     */
    fun getAllModifiedFiles(): Set<String> {
        return prompts.flatMap { it.filesAfter.keys }.toSet()
    }

    /**
     * Ajoute un listener pour les changements
     */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    /**
     * Reset l'historique (pour debug)
     */
    fun clearHistory() {
        prompts.clear()
        currentPrompt = null
        notifyListeners()
    }
}
