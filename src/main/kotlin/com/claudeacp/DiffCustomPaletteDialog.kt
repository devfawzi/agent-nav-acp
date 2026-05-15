package com.claudeacp

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColorPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

/**
 * Dialog pour éditer les 6 couleurs de la palette diff custom : background + ligne pour
 * Added / Deleted / Modified. À chaque changement, on apply en live sur le scheme IntelliJ
 * pour que l'user voit l'effet immédiatement sur un diff déjà ouvert. Les valeurs sont
 * persistées dans `DiffPaletteService.state`.
 */
class DiffCustomPaletteDialog(
    private val service: DiffPaletteService = DiffPaletteService.getInstance()
) : DialogWrapper(null, false, IdeModalityType.IDE) {

    private val initial = service.customColors()

    private val addedBgPanel = ColorPanel().apply { selectedColor = initial.addedBg }
    private val addedLinePanel = ColorPanel().apply { selectedColor = initial.addedLine }
    private val deletedBgPanel = ColorPanel().apply { selectedColor = initial.deletedBg }
    private val deletedLinePanel = ColorPanel().apply { selectedColor = initial.deletedLine }
    private val modifiedBgPanel = ColorPanel().apply { selectedColor = initial.modifiedBg }
    private val modifiedLinePanel = ColorPanel().apply { selectedColor = initial.modifiedLine }

    init {
        title = "Custom diff palette"
        setOKButtonText("Apply")
        // Live preview : à chaque changement, on apply tout de suite. L'user voit le rendu
        // sans avoir à fermer-rouvrir.
        listOf(addedBgPanel, addedLinePanel, deletedBgPanel, deletedLinePanel,
            modifiedBgPanel, modifiedLinePanel).forEach { panel ->
            panel.addActionListener { applyCurrent() }
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val grid = JPanel(GridBagLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(10)
        }
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
        }
        fun addRow(row: Int, label: String, bg: ColorPanel, line: ColorPanel) {
            c.gridx = 0; c.gridy = row; c.weightx = 0.0
            grid.add(JLabel(label), c)
            c.gridx = 1; c.weightx = 1.0
            grid.add(JLabel("Background:"), c)
            c.gridx = 2
            grid.add(bg, c)
            c.gridx = 3
            grid.add(JLabel("Line marker:"), c)
            c.gridx = 4
            grid.add(line, c)
        }
        // Header row
        c.gridx = 0; c.gridy = 0
        grid.add(JLabel("<html><i>Pick colors — changes apply live on open diffs.</i></html>"), c.apply { gridwidth = 5 })
        c.gridwidth = 1
        addRow(1, "🟢 Added",    addedBgPanel,    addedLinePanel)
        addRow(2, "🔴 Deleted",  deletedBgPanel,  deletedLinePanel)
        addRow(3, "🟡 Modified", modifiedBgPanel, modifiedLinePanel)

        // Reset button row
        c.gridx = 0; c.gridy = 4; c.gridwidth = 5
        grid.add(JButton("Reset to Vivid preset").apply {
            margin = JBUI.insets(2, 8)
            addActionListener {
                addedBgPanel.selectedColor = Color(0x1f4d2b)
                addedLinePanel.selectedColor = Color(0x2e7d32)
                deletedBgPanel.selectedColor = Color(0x5a1e1e)
                deletedLinePanel.selectedColor = Color(0xc62828)
                modifiedBgPanel.selectedColor = Color(0x44391e)
                modifiedLinePanel.selectedColor = Color(0xb78600)
                applyCurrent()
            }
        }, c)

        return grid
    }

    private fun applyCurrent() {
        val colors = DiffPaletteService.PaletteColors(
            addedBg = addedBgPanel.selectedColor ?: initial.addedBg,
            addedLine = addedLinePanel.selectedColor ?: initial.addedLine,
            deletedBg = deletedBgPanel.selectedColor ?: initial.deletedBg,
            deletedLine = deletedLinePanel.selectedColor ?: initial.deletedLine,
            modifiedBg = modifiedBgPanel.selectedColor ?: initial.modifiedBg,
            modifiedLine = modifiedLinePanel.selectedColor ?: initial.modifiedLine
        )
        service.setCustomColors(colors)
    }

    override fun doOKAction() {
        applyCurrent()
        super.doOKAction()
    }
}
