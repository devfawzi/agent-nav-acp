package com.claudeacp

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service singleton qui gère l'onglet d'éditeur du diff des pending changes.
 *
 * Un seul `ChainDiffVirtualFile` est maintenu et mis à jour automatiquement à chaque
 * changement du `PendingChangesService` (ajout, accept, reject). Le user n'a qu'un
 * seul onglet diff qui s'adapte au state courant.
 *
 * - Côté gauche du diff : BEFORE (immutable)
 * - Côté droit : VirtualFile éditable (boutons `>>` natifs pour revert hunk par hunk)
 */
@Service(Service.Level.PROJECT)
class DiffViewerManager(private val project: Project) {

    private val log = thisLogger()

    @Volatile
    private var currentDiffFile: ChainDiffVirtualFile? = null

    private val refreshScheduled = AtomicBoolean(false)

    init {
        // Auto-refresh dès qu'un change est ajouté/retiré du service
        val pendingService = project.getService(PendingChangesService::class.java)
        pendingService.addListener {
            scheduleRefresh()
        }
    }

    /**
     * Demande de refresh de l'onglet diff. Debouncé : si un refresh est déjà programmé,
     * on attend le prochain tick EDT. Évite de spammer l'IDE pendant que Claude écrit
     * plusieurs fichiers en rafale.
     */
    fun scheduleRefresh(focusedPath: String? = null) {
        log.info("scheduleRefresh called (focused=$focusedPath, alreadyScheduled=${refreshScheduled.get()})")
        if (refreshScheduled.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater {
                refreshScheduled.set(false)
                try {
                    doRefresh(focusedPath)
                } catch (e: Exception) {
                    log.error("Diff refresh failed", e)
                }
            }
        }
    }

    private fun doRefresh(focusedPath: String?) {
        log.info("doRefresh START focused=$focusedPath currentDiffFile=$currentDiffFile")
        val service = project.getService(PendingChangesService::class.java)
        val pending = service.getAll()
        log.info("doRefresh pending.size=${pending.size}")

        if (pending.isEmpty()) {
            log.info("Refresh: no pending → close current diff tab")
            closeCurrentDiff()
            return
        }

        val requests = pending.map { buildDiffRequest(it) }
        val initialIdx = focusedPath?.let { p ->
            pending.indexOfFirst { it.path == p }.takeIf { it >= 0 }
        } ?: 0

        val chain = SimpleDiffRequestChain(requests, initialIdx)
        val title = if (pending.size == 1) {
            "Claude: ${File(pending[0].path).name}"
        } else {
            "Claude Pending Changes (${pending.size})"
        }

        closeCurrentDiff()
        log.info("doRefresh creating ChainDiffVirtualFile title='$title'")
        val newDiffFile = ChainDiffVirtualFile(chain, title)
        try {
            log.info("doRefresh calling showDiffFile...")
            DiffEditorTabFilesManager.getInstance(project).showDiffFile(newDiffFile, true)
            currentDiffFile = newDiffFile
            log.info("doRefresh SUCCESS: opened diff tab with ${pending.size} file(s), focus idx=$initialIdx")
        } catch (e: Exception) {
            log.error("doRefresh showDiffFile FAILED", e)
            // Fallback popup
            try {
                log.info("doRefresh trying fallback DiffManager.showDiff...")
                DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
                log.info("doRefresh fallback SUCCESS")
            } catch (fe: Exception) {
                log.error("doRefresh fallback ALSO FAILED", fe)
            }
        }
    }

    private fun closeCurrentDiff() {
        val old = currentDiffFile ?: return
        currentDiffFile = null
        try {
            val editorManager = FileEditorManager.getInstance(project)
            if (editorManager.isFileOpen(old)) {
                editorManager.closeFile(old)
            }
        } catch (e: Exception) {
            log.warn("Failed to close old diff tab", e)
        }
    }

    /** Force l'ouverture/refresh du diff multi-fichiers (= bouton Review All). */
    fun showAllPendingDiffs() {
        scheduleRefresh()
    }

    /** Ouvre le diff sur un fichier spécifique (= clic sur fichier dans la liste). */
    fun showDiffForFile(path: String) {
        scheduleRefresh(focusedPath = path)
    }

    internal fun buildDiffRequest(change: PendingChangesService.PendingChange): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        val fileName = File(change.path).name
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)

        // Left : BEFORE en read-only
        val leftContent: DiffContent = factory.create(change.before, fileType)

        // Right : VirtualFile pour permettre l'édition + boutons hunk-by-hunk natifs
        val virtualFile = change.virtualFile?.takeIf { it.isValid && it.exists() }
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(change.path)

        val rightContent: DiffContent = if (virtualFile != null && virtualFile.exists()) {
            factory.create(project, virtualFile)
        } else {
            factory.create(change.lastSnapshotAfter, fileType)
        }

        val req = SimpleDiffRequest(
            "Claude: $fileName",
            leftContent,
            rightContent,
            "Before Claude",
            "After Claude"
        )
        // Toolbar actions : Accept/Reject pour le fichier entier. Le hunk-by-hunk est déjà
        // géré nativement par IntelliJ via les flèches ⮜/⮞ dans la gutter du diff (copier
        // ou revert un hunk individuellement).
        // Toolbar minimal : Accept All / Reject All du fichier + picker palette.
        // Navigation hunk-by-hunk via les actions natives IntelliJ déjà présentes dans
        // le toolbar du diff (flèches gutter en side-by-side).
        req.putUserData(
            com.intellij.diff.util.DiffUserDataKeysEx.CONTEXT_ACTIONS,
            listOf(
                AcceptFileDiffAction(project, change.path),
                RejectFileDiffAction(project, change.path),
                com.intellij.openapi.actionSystem.Separator.getInstance(),
                DiffPalettePickerAction()
            )
        )
        return req
    }

    // ── Compat avec PromptHistory : ouvre des diffs read-only en popup ────────

    fun showDiff(filepath: String, oldContent: String, newContent: String, title: String = "Claude Changes") {
        ApplicationManager.getApplication().invokeLater {
            try {
                val factory = DiffContentFactory.getInstance()
                val fileName = File(filepath).name
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
                val request = SimpleDiffRequest(
                    title,
                    factory.create(project, oldContent, fileType),
                    factory.create(project, newContent, fileType),
                    "Before",
                    "After"
                )
                DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT)
            } catch (e: Exception) {
                log.error("Failed to show diff for $filepath", e)
            }
        }
    }

    fun showPromptDiff(promptId: Int) {
        val historyService = project.getService(PromptHistoryService::class.java)
        val prompt = historyService.getPrompt(promptId) ?: return
        if (prompt.filesAfter.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            val factory = DiffContentFactory.getInstance()
            val requests = prompt.filesAfter.map { (filepath, afterSnapshot) ->
                val beforeContent = prompt.filesBefore[filepath]?.content ?: ""
                val fileName = File(filepath).name
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
                SimpleDiffRequest(
                    "Prompt #$promptId: $fileName",
                    factory.create(project, beforeContent, fileType),
                    factory.create(project, afterSnapshot.content, fileType),
                    "Before Prompt #$promptId",
                    "After Prompt #$promptId"
                )
            }
            if (requests.size == 1) {
                DiffManager.getInstance().showDiff(project, requests.first(), DiffDialogHints.DEFAULT)
            } else {
                DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
            }
        }
    }

    fun comparePrompts(fromPromptId: Int, toPromptId: Int) {
        val historyService = project.getService(PromptHistoryService::class.java)
        val toPrompt = historyService.getPrompt(toPromptId) ?: return
        if (toPrompt.filesAfter.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            val factory = DiffContentFactory.getInstance()
            val requests = toPrompt.filesAfter.keys.mapNotNull { filepath ->
                val before = historyService.getFileContentAtPrompt(fromPromptId, filepath) ?: return@mapNotNull null
                val after = historyService.getFileContentAtPrompt(toPromptId, filepath) ?: return@mapNotNull null
                val fileName = File(filepath).name
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
                SimpleDiffRequest(
                    "Prompt #$fromPromptId → #$toPromptId: $fileName",
                    factory.create(project, before, fileType),
                    factory.create(project, after, fileType),
                    "After #$fromPromptId",
                    "After #$toPromptId"
                )
            }
            if (requests.size == 1) {
                DiffManager.getInstance().showDiff(project, requests.first(), DiffDialogHints.DEFAULT)
            } else if (requests.isNotEmpty()) {
                DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
            }
        }
    }
}
