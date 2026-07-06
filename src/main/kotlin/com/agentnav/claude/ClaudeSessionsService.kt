package com.agentnav.claude

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Service qui lit les sessions Claude Code stockées sur disque pour permettre la reprise
 * de conversation via `claude --resume <sid>`.
 *
 * Format de stockage Claude Code : `~/.claude/projects/<encoded-cwd>/<sid>.jsonl` où le
 * cwd absolu est encodé en remplaçant chaque `/` par `-` (ex : `/home/fawzi/Dev/foo` →
 * `-home-fawzi-Dev-foo`). Chaque .jsonl contient une ligne par event (user, assistant,
 * tool_use, etc.) au même format que le flux stream-json runtime.
 */
@Service(Service.Level.PROJECT)
class ClaudeSessionsService(private val project: Project) {

    private val log = thisLogger()

    data class SessionInfo(
        val sessionId: String,
        /** Premier prompt user "humain" (après skip des wrappers system). */
        val firstUserMessage: String,
        /** Dernier prompt user humain — utile pour savoir où on en était. */
        val lastUserMessage: String?,
        /** Date du premier user message humain. */
        val firstUserAt: Instant?,
        /** Date du dernier user message humain. */
        val lastUserAt: Instant?,
        /** Si claude a généré un type:"summary" dans le .jsonl, on l'utilise comme titre. */
        val summary: String?,
        val messageCount: Int,
        val lastModified: Instant,
        val sizeBytes: Long,
        val cwd: String?,
        /**
         * Source de la session : `sdk-cli` = lancée via le plugin (stream-json),
         * `cli` = claude CLI TUI direct, `sdk-ts` = SDK TypeScript, etc.
         * Permet de filtrer "uniquement mes chats plugin" dans le picker.
         */
        val entrypoint: String?
    ) {
        fun formattedDate(): String = formatInstant(lastModified)
        fun formattedFirstUserAt(): String? = firstUserAt?.let { formatInstant(it) }
        fun formattedLastUserAt(): String? = lastUserAt?.let { formatInstant(it) }

        companion object {
            private val FORMATTER = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
            fun formatInstant(i: Instant): String = FORMATTER.format(i)
        }
    }

    /**
     * Préfixes des wrappers système injectés par Claude Code AVANT le vrai prompt user.
     * On les skippe pour afficher le contenu humain dans le picker, sinon le card devient
     * `<local-command-caveat>Caveat: ...` au lieu de "fix the bug in MyService.kt".
     */
    private val SYSTEM_WRAPPER_PREFIXES = listOf(
        "<local-command-caveat>",
        "<local-command-stdout>",
        "<local-command-stderr>",
        "<system-reminder>",
        "<command-name>",
        "<command-message>",
        "<command-args>",
        "<task-notification>",
        "<bash-stdout>",
        "<bash-stderr>",
        "<user-prompt-submit-hook>"
    )

    /** Renvoie true si le contenu est entièrement constitué de wrappers / system reminders. */
    private fun isSystemWrapperOnly(text: String): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return true
        return SYSTEM_WRAPPER_PREFIXES.any { trimmed.startsWith(it) }
    }

    /** Répertoire où Claude Code stocke les sessions du projet courant. */
    fun getProjectSessionsDir(): File? {
        val basePath = project.basePath ?: return null
        val encoded = basePath.replace("/", "-")
        val home = System.getProperty("user.home") ?: return null
        val dir = File("$home/.claude/projects/$encoded")
        return if (dir.isDirectory) dir else null
    }

    /** Liste les sessions du projet courant, triées par date de modif desc. */
    fun listSessions(limit: Int = 50): List<SessionInfo> {
        val dir = getProjectSessionsDir() ?: return emptyList()
        return scanSessionsDir(dir, limit)
    }

    /**
     * Liste TOUTES les sessions Claude Code (tous projets confondus), triées par date desc.
     * Utile pour reprendre une conv lancée depuis un autre projet/cwd, ou si l'encoding de
     * cwd ne match pas exactement (ex: lien symbolique).
     */
    fun listAllSessions(limit: Int = 200): List<SessionInfo> {
        val home = System.getProperty("user.home") ?: return emptyList()
        val root = File("$home/.claude/projects")
        if (!root.isDirectory) return emptyList()
        val projectDirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return projectDirs.asSequence()
            .flatMap { dir ->
                val files = dir.listFiles() ?: return@flatMap emptySequence()
                files.asSequence()
                    .filter { it.isFile && it.name.endsWith(".jsonl") }
                    .filter { isLikelySessionId(it.nameWithoutExtension) }
            }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .mapNotNull { parseSession(it) }
            .toList()
    }

    private fun scanSessionsDir(dir: File, limit: Int): List<SessionInfo> {
        val files: Array<File> = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .filter { isLikelySessionId(it.nameWithoutExtension) }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .mapNotNull { parseSession(it) }
            .toList()
    }

    /** UUIDv4 32 chars + 4 tirets. On filtre pour éviter les fichiers queue ou autres. */
    private fun isLikelySessionId(name: String): Boolean {
        if (name.length != 36) return false
        return name.matches(Regex("[0-9a-fA-F-]{36}"))
    }

    private fun parseSession(file: File): SessionInfo? {
        return try {
            var firstUser: String? = null
            var firstUserAt: Instant? = null
            var lastUser: String? = null
            var lastUserAt: Instant? = null
            var summary: String? = null
            var messages = 0
            var cwd: String? = null
            var entrypoint: String? = null
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach line@{ line ->
                    if (line.isBlank() || !line.startsWith("{")) return@line
                    try {
                        val obj = JsonParser.parseString(line).asJsonObject
                        val type = obj.get("type")?.asString
                        // Claude génère parfois un type:"summary" — c'est le meilleur titre
                        // possible pour une session, on le prend en priorité.
                        if (type == "summary" && summary == null) {
                            summary = obj.get("summary")?.asString
                                ?: obj.get("text")?.asString
                        }
                        if (type == "user" || type == "assistant") messages++
                        if (type == "user") {
                            val text = extractUserText(obj) ?: return@line
                            // Skip les wrappers system pour trouver le vrai prompt humain.
                            if (isSystemWrapperOnly(text)) return@line
                            val ts = obj.get("timestamp")?.asString?.let {
                                runCatching { Instant.parse(it) }.getOrNull()
                            }
                            if (firstUser == null) {
                                firstUser = text
                                firstUserAt = ts
                            }
                            lastUser = text
                            lastUserAt = ts
                        }
                        if (cwd == null) {
                            cwd = obj.get("cwd")?.asString
                        }
                        if (entrypoint == null) {
                            entrypoint = obj.get("entrypoint")?.asString
                        }
                    } catch (_: Exception) { /* skip malformed line */ }
                }
            }
            if (messages == 0) return null
            SessionInfo(
                sessionId = file.nameWithoutExtension,
                firstUserMessage = (firstUser ?: "(no user message)").take(200),
                lastUserMessage = lastUser?.take(200)?.takeIf { it != firstUser?.take(200) },
                firstUserAt = firstUserAt,
                lastUserAt = lastUserAt,
                summary = summary?.take(200),
                messageCount = messages,
                lastModified = Instant.ofEpochMilli(file.lastModified()),
                sizeBytes = file.length(),
                cwd = cwd,
                entrypoint = entrypoint
            )
        } catch (e: Exception) {
            log.warn("Failed to parse session file ${file.name}", e)
            null
        }
    }

    /** Supprime le .jsonl correspondant à une session. Retourne true si supprimé. */
    fun deleteSession(sessionId: String): Boolean {
        val home = System.getProperty("user.home") ?: return false
        val root = File("$home/.claude/projects")
        if (!root.isDirectory) return false
        var found = false
        root.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val candidate = File(dir, "$sessionId.jsonl")
            if (candidate.isFile) {
                if (candidate.delete()) {
                    log.info("Deleted session ${candidate.absolutePath}")
                    found = true
                }
            }
            // Supprime aussi le dossier `<sid>/` si présent (tool-results storage)
            val sidDir = File(dir, sessionId)
            if (sidDir.isDirectory) {
                sidDir.deleteRecursively()
            }
        }
        return found
    }

    /** Bulk delete. Retourne le nombre supprimé. */
    fun deleteSessions(sessionIds: Collection<String>): Int =
        sessionIds.count { deleteSession(it) }

    /** Extrait le texte d'un user message du .jsonl (content peut être string ou array). */
    private fun extractUserText(obj: com.google.gson.JsonObject): String? {
        val msg = obj.getAsJsonObject("message") ?: return null
        val content = msg.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
                .joinToString("\n") { it.asJsonObject.get("text")?.asString ?: "" }
                .ifBlank { null }
            else -> null
        }
    }
}
