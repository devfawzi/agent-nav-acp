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
}
