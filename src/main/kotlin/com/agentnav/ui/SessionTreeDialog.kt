package com.agentnav.ui

import com.agentnav.claude.SessionTreeBuilder
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.io.File
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.WindowConstants
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Arbre de conversation de la session courante (IMPROVEMENTS #6). Les 🌿 marquent les
 * points de divergence (rewind TUI puis continuation). Le bouton Fork ouvre un nouveau
 * tab `--resume <sid> --fork-session` : continuer dans une branche isolée sans toucher
 * la session d'origine.
 */
class SessionTreeDialog(
    private val sessionId: String,
    private val sessionFile: File,
    private val onFork: () -> Unit
) {

    fun show() {
        val tree = SessionTreeBuilder.build(sessionFile)

        val rootNode = DefaultMutableTreeNode("Session ${sessionId.take(8)}… — " +
            "${tree.nodeCount} messages, ${tree.branchPoints} branch point(s)")
        tree.roots.forEach { addNode(rootNode, it) }

        val jTree = JTree(rootNode).apply {
            isRootVisible = true
            for (i in 0 until rowCount) expandRow(i)
        }

        val dialog = JDialog(null as Frame?, "Conversation tree", true).apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            size = Dimension(720, 520)
            setLocationRelativeTo(null)
        }

        val forkBtn = JButton("🌿 Fork session (continue in a new tab)").apply {
            toolTipText = "claude --resume $sessionId --fork-session — new session id, " +
                "full history, the original session stays untouched."
            addActionListener {
                dialog.dispose()
                onFork()
            }
        }
        val hint = JLabel("<html><span style='color:gray;font-size:10px'>" +
            "Branches appear when a conversation was rewound and continued (TUI rewind). " +
            "Point-in-time rewind is not exposed by claude stream-json yet.</span></html>")

        val footer = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            background = UIUtil.getPanelBackground()
            add(forkBtn)
            add(hint)
        }

        dialog.contentPane.apply {
            layout = BorderLayout()
            add(JBScrollPane(jTree).apply { border = JBUI.Borders.empty(4) }, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
        dialog.isVisible = true
    }

    private fun addNode(parent: DefaultMutableTreeNode, node: SessionTreeBuilder.Node) {
        val icon = if (node.role == "user") "👤" else "🤖"
        val branch = if (node.isBranchPoint) " 🌿" else ""
        val label = node.text.ifEmpty { "(${node.role} — tools/thinking)" }
        // Compacte les chaînons vides linéaires pour ne pas noyer l'arbre.
        if (node.text.isEmpty() && node.children.size == 1) {
            addNode(parent, node.children[0])
            return
        }
        val treeNode = DefaultMutableTreeNode("$icon $label$branch")
        parent.add(treeNode)
        node.children.forEach { addNode(treeNode, it) }
    }
}
