package com.claudeacp

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import javax.swing.Icon
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.icons.AllIcons
import java.io.File

/**
 * Helper pour récupérer la sélection courante d'un éditeur IntelliJ et la convertir en
 * `PromptAttachment.CodeRef` style Cursor : path, lignes start/end, contenu, langage.
 *
 * Deux entry points :
 *  - `grabCurrentSelection()` : retourne la sélection active dans l'éditeur focusé (peut être null)
 *  - `tryMatchClipboard(pastedText)` : si le texte collé correspond exactement à une sélection
 *    active d'un éditeur du projet, retourne la CodeRef. Sinon null.
 */
object EditorSelectionGrabber {

    /** Récupère la sélection courante de l'éditeur actif. null si rien sélectionné. */
    fun grabCurrentSelection(project: Project): PromptAttachment.CodeRef? {
        return com.intellij.openapi.application.ReadAction.compute<PromptAttachment.CodeRef?, RuntimeException> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
            val sel = editor.selectionModel
            val selText = sel.selectedText ?: return@compute null
            if (selText.isBlank()) return@compute null
            val doc = editor.document
            val startLine = doc.getLineNumber(sel.selectionStart) + 1
            val endLine = doc.getLineNumber(sel.selectionEnd) + 1
            val vf = com.intellij.openapi.fileEditor.FileDocumentManager
                .getInstance().getFile(doc) ?: return@compute null
            val name = vf.name
            val ft = FileTypeManager.getInstance().getFileTypeByFileName(name)
            val icon: Icon = ft.icon ?: AllIcons.FileTypes.Any_type
            val lang = inferLanguage(name)
            val displayName = relativize(project, vf.path)
            PromptAttachment.CodeRef(
                absolutePath = vf.path,
                startLine = startLine,
                endLine = endLine,
                content = selText,
                language = lang,
                displayName = "$displayName:${if (startLine == endLine) "$startLine" else "$startLine-$endLine"}",
                icon = icon
            )
        }
    }

    /**
     * Si `pastedText` correspond exactement à la sélection courante de N'IMPORTE QUEL éditeur
     * ouvert dans le projet, on retourne la CodeRef. Sinon null (= paste texte classique).
     * Compare modulo trim de fin de ligne pour gérer les diffs de \r\n.
     */
    fun tryMatchClipboard(project: Project, pastedText: String): PromptAttachment.CodeRef? {
        return com.intellij.openapi.application.ReadAction.compute<PromptAttachment.CodeRef?, RuntimeException> {
            val normalized = pastedText.normalizeForCompare()
            val mgr = FileEditorManager.getInstance(project)
            val editors = mgr.allEditors
                .filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>()
                .map { it.editor }
            for (editor in editors) {
                val sel = editor.selectionModel
                val selText = sel.selectedText ?: continue
                if (selText.isBlank()) continue
                if (selText.normalizeForCompare() != normalized) continue
                val doc = editor.document
                val startLine = doc.getLineNumber(sel.selectionStart) + 1
                val endLine = doc.getLineNumber(sel.selectionEnd) + 1
                val vf = com.intellij.openapi.fileEditor.FileDocumentManager
                    .getInstance().getFile(doc) ?: continue
                val name = vf.name
                val ft = FileTypeManager.getInstance().getFileTypeByFileName(name)
                val icon: Icon = ft.icon ?: AllIcons.FileTypes.Any_type
                return@compute PromptAttachment.CodeRef(
                    absolutePath = vf.path,
                    startLine = startLine,
                    endLine = endLine,
                    content = selText,
                    language = inferLanguage(name),
                    displayName = "${relativize(project, vf.path)}:${if (startLine == endLine) "$startLine" else "$startLine-$endLine"}",
                    icon = icon
                )
            }
            null
        }
    }

    private fun String.normalizeForCompare(): String =
        this.replace("\r\n", "\n").trimEnd()

    private fun inferLanguage(filename: String): String? {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "tsx"
            "jsx" -> "jsx"
            "go" -> "go"
            "rs" -> "rust"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "c", "h" -> "c"
            "cpp", "hpp", "cxx" -> "cpp"
            "cs" -> "csharp"
            "scala" -> "scala"
            "sh", "bash" -> "bash"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "toml" -> "toml"
            "xml" -> "xml"
            "html", "htm" -> "html"
            "css" -> "css"
            "scss", "sass" -> "scss"
            "sql" -> "sql"
            "md" -> "markdown"
            else -> ext.ifBlank { null }
        }
    }

    private fun relativize(project: Project, absPath: String): String {
        val base = project.basePath ?: return File(absPath).name
        return if (absPath.startsWith(base)) absPath.substring(base.length).trimStart('/')
        else File(absPath).name
    }
}
