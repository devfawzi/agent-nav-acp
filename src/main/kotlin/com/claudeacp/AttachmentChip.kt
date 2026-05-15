package com.claudeacp

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.AbstractBorder
import java.awt.*

/**
 * Petite "chip" affichée au-dessus du textarea pour représenter un attachment (fichier ou image).
 * Contient un bouton × pour la retirer.
 */
class AttachmentChip(
    private val attachment: PromptAttachment,
    private val onRemove: () -> Unit
) : JPanel(BorderLayout()) {

    init {
        background = UIUtil.getTextFieldBackground()
        border = AttachmentBorder()
        maximumSize = Dimension(260, 28)

        val icon = JLabel(iconFor(attachment)).apply {
            border = JBUI.Borders.empty(0, 4, 0, 4)
        }
        val label = JLabel(attachment.displayName).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            border = JBUI.Borders.empty(2, 2, 2, 6)
            toolTipText = when (attachment) {
                is PromptAttachment.FileLink -> attachment.absolutePath
                is PromptAttachment.Image -> attachment.displayName
                is PromptAttachment.CodeRef -> "<html>${attachment.absolutePath}:${attachment.lineRange}<br><br>" +
                    "<pre style='font-family:monospaced;font-size:10px;'>" +
                    attachment.content.take(600).replace("<", "&lt;").replace(">", "&gt;") +
                    (if (attachment.content.length > 600) "\n…" else "") +
                    "</pre></html>"
            }
        }
        val close = JLabel("×").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(0, 4, 0, 6)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { onRemove() }
            })
        }

        add(icon, BorderLayout.WEST)
        add(label, BorderLayout.CENTER)
        add(close, BorderLayout.EAST)
    }

    private fun iconFor(att: PromptAttachment): Icon {
        att.icon?.let { return it }
        return when (att) {
            is PromptAttachment.Image -> {
                // Thumbnail à partir du base64 si possible
                try {
                    val bytes = Base64.getDecoder().decode(att.base64Data)
                    val img = ImageIO.read(ByteArrayInputStream(bytes))
                    if (img != null) {
                        val scaled = img.getScaledInstance(20, 20, Image.SCALE_SMOOTH)
                        return ImageIcon(scaled)
                    }
                } catch (_: Exception) {}
                AllIcons.FileTypes.Image
            }
            is PromptAttachment.FileLink -> AllIcons.FileTypes.Any_type
            is PromptAttachment.CodeRef -> AllIcons.Actions.ShowAsTree
        }
    }
}

private class AttachmentBorder : AbstractBorder() {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBColor.border()
        g2.drawRoundRect(x, y, width - 1, height - 1, 10, 10)
    }
    override fun getBorderInsets(c: Component): Insets = Insets(2, 2, 2, 2)
}
