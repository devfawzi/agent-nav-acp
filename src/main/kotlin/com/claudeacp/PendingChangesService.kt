package com.claudeacp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracke les fichiers modifiés par Claude en attente de review.
 *
 * Chaque entrée stocke le BEFORE (état avant que Claude touche le fichier dans ce cycle de review)
 * et le AFTER courant (état actuel sur disque, potentiellement modifié par l'utilisateur qui revert
 * des hunks via le diff viewer).
 *
 * - Accept : retire de la liste, garde le disque tel quel (l'état accepté devient le baseline)
 * - Reject : écrit BEFORE sur le disque (revert complet), retire de la liste
 *
 * Le BEFORE n'est jamais écrasé tant qu'un Accept ou Reject explicite n'a pas eu lieu — même si
 * Claude refait des modifs au prompt suivant.
 */
@Service(Service.Level.PROJECT)
class PendingChangesService(private val project: Project) {

    data class PendingChange(
        val path: String,
        val before: String,
        @Volatile var lastSnapshotAfter: String,
        val virtualFile: VirtualFile?,
        val triggeredBySessionId: String? = null,
        val addedAt: Long = System.currentTimeMillis()
    )

    private val log = thisLogger()
    private val changes = LinkedHashMap<String, PendingChange>()
    private val listeners = mutableListOf<() -> Unit>()
    private val addedListeners = mutableListOf<(PendingChange) -> Unit>()

    /**
     * Set des paths actuellement en cours de reject. Quand on écrit le `before` sur le disque
     * pour faire un revert, le VFS détecte le changement et appellerait `addOrUpdate` avec
     * before/after inversés. On marque ici pour que le VFS listener skip cet event-là.
     */
    private val rejectInProgress = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun addOrUpdate(path: String, before: String, after: String, vf: VirtualFile?, triggeredBy: String? = null) {
        val existing = changes[path]
        val isNew = existing == null
        if (isNew) {
            val change = PendingChange(path, before, after, vf, triggeredBy)
            changes[path] = change
            log.info("Pending added: $path (before=${before.length}c, after=${after.length}c, sid=$triggeredBy)")
            notifyAdded(change)
        } else {
            existing!!.lastSnapshotAfter = after
            log.info("Pending updated: $path (new after=${after.length}c)")
        }
        notifyListeners()
    }

    fun addAddedListener(listener: (PendingChange) -> Unit) {
        addedListeners.add(listener)
    }

    private fun notifyAdded(change: PendingChange) {
        val snapshot = addedListeners.toList()
        ApplicationManager.getApplication().invokeLater {
            snapshot.forEach { it(change) }
        }
    }

    @Synchronized
    fun accept(path: String) {
        if (changes.remove(path) != null) {
            log.info("Accepted: $path")
            notifyListeners()
        }
    }

    /**
     * Accept partiel : écrit un contenu intermédiaire sur disque (ex: sous-ensemble de hunks
     * acceptés sur les N proposés par claude), puis retire le change de la liste. Utilisé par
     * le hunk-by-hunk picker. On marque rejectInProgress pour que le VFS listener ne crée pas
     * un nouveau pending change à partir de cette écriture.
     */
    @Synchronized
    fun applyPartial(path: String, content: String) {
        val change = changes.remove(path) ?: return
        rejectInProgress.add(path)
        log.info("Apply partial: $path (${content.length}c)")
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                try {
                    val vf = change.virtualFile?.takeIf { it.isValid }
                        ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                    if (vf != null && vf.exists()) {
                        vf.setBinaryContent(content.toByteArray())
                    } else {
                        File(path).writeText(content)
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                    }
                } catch (e: Exception) {
                    log.error("Failed to apply partial for $path", e)
                    rejectInProgress.remove(path)
                }
            }
        }
        notifyListeners()
    }

    @Synchronized
    fun reject(path: String) {
        val change = changes.remove(path) ?: return
        rejectInProgress.add(path)
        val wasNewFile = change.before.isEmpty()
        log.info("Rejecting $path (wasNewFile=$wasNewFile)")
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                try {
                    val vf = change.virtualFile?.takeIf { it.isValid }
                        ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)

                    if (wasNewFile) {
                        // C'était un Write (nouveau fichier) → on supprime
                        if (vf != null && vf.exists()) {
                            vf.delete(this)
                        } else {
                            File(path).delete()
                        }
                    } else {
                        // C'était un Edit → on restore le BEFORE
                        if (vf != null && vf.exists()) {
                            vf.setBinaryContent(change.before.toByteArray())
                        } else {
                            File(path).writeText(change.before)
                            LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                        }
                    }
                } catch (e: Exception) {
                    log.error("Failed to reject $path", e)
                    rejectInProgress.remove(path)
                }
            }
        }
        notifyListeners()
    }

    /**
     * Le VFS listener doit appeler ceci avant de traiter un change.
     * Retourne true si ce path est en cours de reject (et clear le flag).
     */
    fun consumeRejectFlag(path: String): Boolean = rejectInProgress.remove(path)

    @Synchronized
    fun getAll(): List<PendingChange> = changes.values.toList()

    @Synchronized
    fun isEmpty(): Boolean = changes.isEmpty()

    @Synchronized
    fun get(path: String): PendingChange? = changes[path]

    @Synchronized
    fun clear() {
        if (changes.isNotEmpty()) {
            changes.clear()
            notifyListeners()
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        val snapshot = listeners.toList()
        ApplicationManager.getApplication().invokeLater {
            snapshot.forEach { it() }
        }
    }
}
