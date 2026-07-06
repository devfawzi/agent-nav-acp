package com.agentnav.services

import com.agentnav.core.*
import com.agentnav.claude.*
import com.agentnav.acp.*
import com.agentnav.ui.*
import com.agentnav.services.*
import com.agentnav.settings.*

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Récupère les diagnostics (errors / warnings) du fichier actuellement ouvert dans l'éditeur
 * IntelliJ — pour les injecter automatiquement dans le contexte du prompt envoyé à claude.
 * Style "comme Cursor qui voit tes erreurs sans que tu copies/colles".
 *
 * Le user peut désactiver via Settings (AgentSettings.injectDiagnostics).
 */
object EditorDiagnosticsGrabber {

    data class Diagnostic(val severity: String, val line: Int, val column: Int, val message: String)

    /**
     * Retourne un bloc markdown à injecter en fin de prompt avec les erreurs/warnings du
     * buffer courant. Retourne null si rien à signaler (pas d'erreur, pas d'éditeur ouvert,
     * ou la feature est désactivée).
     *
     * @param maxItems cap le nombre de diagnostics pour ne pas exploser le prompt
     * @param errorsOnly si true, ne renvoie que les ERRORS (skip warnings/info)
     */
    fun buildDiagnosticsContext(
        project: Project,
        maxItems: Int = 20,
        errorsOnly: Boolean = true
    ): String? {
        // `DaemonCodeAnalyzerImpl.getHighlights` ET le calcul de line/column via `Document`
        // exigent un ReadAction. On wrap toute la lecture dans `runReadAction` pour ne pas
        // crasher quand on est appelés depuis l'EDT lors de send().
        return com.intellij.openapi.application.ReadAction.compute<String?, RuntimeException> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ?: return@compute null
            val doc = editor.document
            val vf = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(doc)
                ?: return@compute null
            val highlights = try {
                DaemonCodeAnalyzerImpl.getHighlights(doc, null, project)
            } catch (_: Throwable) {
                return@compute null
            }
            if (highlights.isEmpty()) return@compute null
            val relevant = highlights
                .filter { hi ->
                    val s = hi.severity
                    if (errorsOnly) s == HighlightSeverity.ERROR
                    else s == HighlightSeverity.ERROR || s == HighlightSeverity.WARNING
                }
                .take(maxItems)
            if (relevant.isEmpty()) return@compute null

            val items = relevant.map { hi ->
                val offset = hi.startOffset.coerceAtLeast(0).coerceAtMost(doc.textLength)
                val line = doc.getLineNumber(offset) + 1
                val col = offset - doc.getLineStartOffset(line - 1) + 1
                val severity = when (hi.severity) {
                    HighlightSeverity.ERROR -> "ERROR"
                    HighlightSeverity.WARNING -> "WARN"
                    else -> "INFO"
                }
                val msg = (hi.description ?: hi.toolTip ?: "").take(160)
                Diagnostic(severity, line, col, msg)
            }
            val basePath = project.basePath
            val displayPath = if (basePath != null && vf.path.startsWith(basePath))
                vf.path.substring(basePath.length).trimStart('/') else File(vf.path).name

            buildString {
                append("\n[Editor diagnostics for ${displayPath}]\n")
                items.forEach { d ->
                    append("- ${d.severity} ${d.line}:${d.column} — ${d.message}\n")
                }
            }
        }
    }
}
