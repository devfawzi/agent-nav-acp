package com.claudeacp

import javax.swing.Icon

/**
 * Pièce jointe à un prompt : fichier (ResourceLink ACP) ou image (ImageContent ACP).
 */
sealed class PromptAttachment {
    abstract val displayName: String
    abstract val icon: Icon?

    /** Référence à un fichier ou dossier du projet (envoyé en `resource_link` ACP). */
    data class FileLink(
        val absolutePath: String,
        val isDirectory: Boolean,
        override val displayName: String,
        val mimeType: String? = null,
        override val icon: Icon? = null
    ) : PromptAttachment()

    /** Image inline (envoyée en `image` ACP avec base64). */
    data class Image(
        override val displayName: String,
        val mimeType: String,
        val base64Data: String,
        override val icon: Icon? = null
    ) : PromptAttachment()

    /**
     * Référence à un fragment de code depuis un éditeur IntelliJ. Style Cursor :
     * quand l'user copie du code et colle dans le chat, on crée un chip cliquable
     * `file.kt:23-45` au lieu d'un blob de texte brut. Le contenu est envoyé à claude
     * formaté en bloc markdown avec un header `<<<file.kt:23-45>>>` pour qu'il sache
     * d'où vient le code.
     */
    data class CodeRef(
        val absolutePath: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
        val language: String? = null,
        override val displayName: String,
        override val icon: Icon? = null
    ) : PromptAttachment() {
        val lineRange: String get() = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
    }
}
