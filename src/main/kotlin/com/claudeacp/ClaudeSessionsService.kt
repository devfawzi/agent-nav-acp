package com.claudeacp

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
        val firstUserMessage: String,
        val messageCount: Int,
        val lastModified: Instant,
        val sizeBytes: Long,
        val cwd: String?
    ) {
        fun formattedDate(): String {
            val formatter = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
            return formatter.format(lastModified)
        }
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
            var messages = 0
            var cwd: String? = null
            // Lecture ligne par ligne ; on s'arrête dès qu'on a trouvé le 1er user message
            // (le reste est juste pour le count, mais on peut lire toutes les lignes — les
            // jsonl restent < 1MB en général).
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || !line.startsWith("{")) continue
                    try {
                        val obj = JsonParser.parseString(line).asJsonObject
                        val type = obj.get("type")?.asString
                        if (type == "user" || type == "assistant") messages++
                        if (firstUser == null && type == "user") {
                            val msg = obj.getAsJsonObject("message")
                            val content = msg?.get("content")
                            firstUser = when {
                                content == null -> null
                                content.isJsonPrimitive -> content.asString
                                content.isJsonArray -> content.asJsonArray
                                    .firstOrNull { it.isJsonObject &&
                                        it.asJsonObject.get("type")?.asString == "text" }
                                    ?.asJsonObject?.get("text")?.asString
                                else -> null
                            }
                        }
                        if (cwd == null) {
                            cwd = obj.get("cwd")?.asString
                        }
                    } catch (_: Exception) { /* skip malformed line */ }
                }
            }
            // Sessions sans aucun message user/assistant : probablement vides (queue events
            // seulement) → on les ignore pour ne pas polluer la liste.
            if (messages == 0) return null
            SessionInfo(
                sessionId = file.nameWithoutExtension,
                firstUserMessage = (firstUser ?: "(no user message)").take(200),
                messageCount = messages,
                lastModified = Instant.ofEpochMilli(file.lastModified()),
                sizeBytes = file.length(),
                cwd = cwd
            )
        } catch (e: Exception) {
            log.warn("Failed to parse session file ${file.name}", e)
            null
        }
    }
}
