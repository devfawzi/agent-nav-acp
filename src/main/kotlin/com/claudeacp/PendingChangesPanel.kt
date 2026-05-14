package com.claudeacp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*

/**
 * Barre des pending changes collée au-dessus de l'input :
 *  - Header cliquable (titre + flèche) qui plie/déplie la liste
 *  - Quand dépliée : liste compacte des fichiers + actions Review All / Accept All
 *  - Cliquable par fichier (ouvre le diff dans l'onglet éditeur)
 */
class PendingChangesPanel(private val project: Project) {

    private val service = project.getService(PendingChangesService::class.java)
    private val diffManager = project.getService(DiffViewerManager::class.java)

    private val titleLabel = JLabel("📝 0 pending changes").apply {
        font = font.deriveFont(Font.PLAIN, 12f)
        foreground = JBColor.foreground()
    }

    private val arrow = JLabel("▸").apply {
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.emptyRight(6)
    }

    private val reviewAllButton = JButton("Review All").apply {
        toolTipText = "Open all pending diffs in editor tab"
        margin = JBUI.insets(2, 6)
        addActionListener { diffManager.showAllPendingDiffs() }
    }

    private val acceptAllButton = JButton("Accept All").apply {
        toolTipText = "Accept all pending changes"
        margin = JBUI.insets(2, 6)
        addActionListener {
            service.getAll().forEach { service.accept(it.path) }
        }
    }

    private val rejectAllButton = JButton("Reject All").apply {
        toolTipText = "Reject all pending changes (restore before content or delete new files)"
        margin = JBUI.insets(2, 6)
        addActionListener {
            service.getAll().forEach { service.reject(it.path) }
        }
    }

    private val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
    }

    private val listScroll = JBScrollPane(listPanel).apply {
        preferredSize = Dimension(0, 0)
        border = null
        verticalScrollBar.unitIncrement = 12
    }

    private var expanded = false

    fun getContent(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty()
            )
        }

        val header = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 8)
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = UIUtil.getPanelBackground()
            add(arrow)
            add(titleLabel)
        }

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = UIUtil.getPanelBackground()
            add(reviewAllButton)
            add(acceptAllButton)
            add(rejectAllButton)
        }

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)

        // Clic sur le header → toggle
        val clickHandler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                toggle()
            }
            override fun mouseEntered(e: MouseEvent) {
                header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
        }
        header.addMouseListener(clickHandler)
        left.addMouseListener(clickHandler)
        titleLabel.addMouseListener(clickHandler)
        arrow.addMouseListener(clickHandler)

        root.add(header, BorderLayout.NORTH)
        root.add(listScroll, BorderLayout.CENTER)

        service.addListener { refresh() }
        refresh()
        applyExpanded()

        return root
    }

    private fun toggle() {
        expanded = !expanded
        applyExpanded()
    }

    private fun applyExpanded() {
        val all = service.getAll()
        val visible = expanded && all.isNotEmpty()
        // Hauteur adaptative : ~42px par item, plafonné à 5 items visibles (au-delà scroll)
        val itemHeight = 42
        val maxItems = 5
        val height = if (visible) (all.size.coerceAtMost(maxItems) * itemHeight) + 6 else 0
        listScroll.preferredSize = Dimension(0, height)
        arrow.text = if (visible) "▾" else "▸"
        listScroll.revalidate()
        listScroll.parent?.revalidate()
        listScroll.parent?.repaint()
    }

    private fun refresh() {
        ApplicationManager.getApplication().invokeLater {
            val all = service.getAll()
            val hasItems = all.isNotEmpty()
            titleLabel.text = if (!hasItems) "📝 No pending changes"
            else "📝 ${all.size} pending change${if (all.size > 1) "s" else ""}"
            // Cache complètement les boutons globaux quand il n'y a rien à reviewer
            reviewAllButton.isVisible = hasItems
            acceptAllButton.isVisible = hasItems
            rejectAllButton.isVisible = hasItems
            // Pas d'auto-expand : c'est l'utilisateur qui décide de déplier

            listPanel.removeAll()
            all.forEach { change ->
                listPanel.add(createRow(change))
                listPanel.add(Box.createVerticalStrut(2))
            }
            listPanel.revalidate()
            listPanel.repaint()
            applyExpanded()
        }
    }

    private fun createRow(change: PendingChangesService.PendingChange): JPanel {
        val row = JPanel(BorderLayout()).apply {
            background = JBColor(java.awt.Color(0xeeeeee), java.awt.Color(0x2a2d31))
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(6, 8)
            )
            // On laisse la hauteur être dictée par le contenu (boutons), maximumSize ne contraint
            // que la largeur. Hauteur libre = boutons jamais coupés.
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val basePath = project.basePath
        val displayName = if (basePath != null && change.path.startsWith(basePath)) {
            change.path.substring(basePath.length).trimStart('/')
        } else File(change.path).name

        val label = JLabel("📄 $displayName").apply {
            toolTipText = change.path
            font = font.deriveFont(Font.PLAIN, 12f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    diffManager.showDiffForFile(change.path)
                }
            })
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            background = JBColor(java.awt.Color(0xeeeeee), java.awt.Color(0x2a2d31))
            add(JButton("✓").apply {
                toolTipText = "Accept"
                margin = JBUI.insets(1, 4)
                addActionListener { service.accept(change.path) }
            })
            add(JButton("↩").apply {
                toolTipText = "Reject (restore before)"
                margin = JBUI.insets(1, 4)
                addActionListener { service.reject(change.path) }
            })
        }

        row.add(label, BorderLayout.CENTER)
        row.add(actions, BorderLayout.EAST)
        return row
    }
}
