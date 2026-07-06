package com.claudeacp

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * Popup autocomplete déclenché quand l'user tape `/` au début du textarea. Liste les slash
 * commands disponibles (plugin: /mode, /model, /effort, /skill, /mcp + claude built-in
 * comme /init, /review, /security-review, etc.).
 *
 * Filtré en live selon ce que l'user tape derrière le `/`. Enter ou click sur un item
 * insère la command + un espace dans le textarea (pour que l'user complete ses args, ou
 * envoie direct via Enter).
 */
class SlashCommandPopup(
    private val owner: JTextArea,
    private val onPick: (cmd: String) -> Unit
) {

    /**
     * Entry du popup. Trois modes :
     *  - `submenuProvider != null` : item parent qui ouvre une nouvelle page (ex /mcp → liste servers)
     *  - `onActivate != null` : item terminal qui déclenche une action et ferme le popup (ex pick model)
     *  - Sinon : fallback historique, déclenche `onPick(name)` (insère dans textarea / commande claude)
     */
    data class Entry(
        val name: String,
        val description: String,
        val isPlugin: Boolean,
        val icon: String? = null,
        val checked: Boolean = false,
        val submenuProvider: (() -> List<Entry>)? = null,
        val onActivate: (() -> Unit)? = null
    )

    private val listModel = DefaultListModel<Entry>()
    private val titleLabel = JLabel("").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(4, 8, 2, 8)
        isOpaque = true
        background = UIUtil.getListBackground()
    }
    private val list = JList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = EntryRenderer()
        background = UIUtil.getListBackground()
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val scroll = JScrollPane(list).apply {
        border = null
    }
    private val container = JPanel(BorderLayout()).apply {
        background = UIUtil.getListBackground()
        preferredSize = Dimension(360, 260)
        add(titleLabel, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    @Volatile
    private var popup: JBPopup? = null

    @Volatile
    var anchorIndex: Int = 0  // index du `/` dans le textarea

    private var rootEntries: List<Entry> = emptyList()

    /** Stack de navigation pour Esc-back : chaque niveau = (title, entries) */
    private val navStack = ArrayDeque<Pair<String, List<Entry>>>()
    private var currentTitle: String = ""
    private var currentEntries: List<Entry> = emptyList()

    /** Set la liste des commandes (plugin builtins + claude slash_commands). */
    fun setEntries(entries: List<Entry>) {
        rootEntries = entries
    }

    /** Affiche/refresh le popup à partir du token texte après `/`. */
    fun show(filter: String) {
        if (rootEntries.isEmpty()) return
        // Reset à la racine quand on appelle show() depuis l'extérieur (= user a tapé `/`).
        navStack.clear()
        currentTitle = ""
        currentEntries = rootEntries
        renderCurrent(filter)
        ensurePopupVisible()
    }

    /** Replace les entries affichées par un sous-menu. */
    private fun openSubmenu(title: String, entries: List<Entry>) {
        navStack.addLast(currentTitle to currentEntries)
        currentTitle = title
        currentEntries = entries
        renderCurrent("")
    }

    /** Revient au menu parent si possible. Retourne true si pop effectué. */
    private fun popSubmenu(): Boolean {
        if (navStack.isEmpty()) return false
        val (title, entries) = navStack.removeLast()
        currentTitle = title
        currentEntries = entries
        renderCurrent("")
        return true
    }

    /** Filtre + remplit la liste avec preservation de la sélection si possible. */
    private fun renderCurrent(filter: String) {
        val needle = filter.lowercase()
        val matching = if (needle.isEmpty()) currentEntries
        else currentEntries.filter { it.name.lowercase().startsWith(needle) }
            .ifEmpty { currentEntries.filter { it.name.lowercase().contains(needle) } }
        if (matching.isEmpty()) {
            // Si root + pas de match : on cache. Si submenu : on garde mais affiche placeholder.
            if (navStack.isEmpty()) {
                hide()
                return
            }
            listModel.clear()
            return
        }
        val previouslySelected = list.selectedValue
        val sameList = listModel.size == matching.size &&
            (0 until listModel.size).all { listModel[it].name == matching[it].name }
        if (!sameList) {
            listModel.clear()
            matching.forEach { listModel.addElement(it) }
        }
        if (list.selectedIndex < 0 || !sameList) {
            val idx = if (previouslySelected != null) matching.indexOfFirst { it.name == previouslySelected.name } else -1
            list.selectedIndex = if (idx >= 0) idx else 0
        }
        // Title bar : visible uniquement dans les sous-menus
        if (currentTitle.isNotEmpty()) {
            titleLabel.text = "← $currentTitle  (Esc to go back)"
            titleLabel.isVisible = true
        } else {
            titleLabel.text = ""
            titleLabel.isVisible = false
        }
    }

    private fun ensurePopupVisible() {
        if (popup == null || popup?.isVisible != true) {
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(container, list)
                .setRequestFocus(false)
                .setFocusable(false)
                .setResizable(true)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .createPopup()
                .also { p ->
                    val caretRect = try { owner.modelToView2D(owner.caretPosition.coerceAtLeast(0)) } catch (_: Exception) { null }
                    val ownerLocation = owner.locationOnScreen
                    val x = ownerLocation.x + (caretRect?.x?.toInt() ?: 0)
                    val y = ownerLocation.y + (caretRect?.y?.toInt() ?: 0) + (caretRect?.height?.toInt() ?: owner.height)
                    p.showInScreenCoordinates(owner, Point(x, y - 280))  // above caret
                }
            list.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount >= 1) confirmSelection()
                }
            })
        }
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }

    fun isVisible(): Boolean = popup?.isVisible == true

    /**
     * Intercept des touches sur le textarea pour navigation popup. Retourne true si l'event
     * a été consommé (= ne PAS passer au handler normal du textarea).
     */
    fun handleKey(e: KeyEvent): Boolean {
        if (!isVisible()) return false
        return when (e.keyCode) {
            KeyEvent.VK_DOWN -> {
                e.consume()
                val next = (list.selectedIndex + 1).coerceAtMost(listModel.size - 1)
                list.selectedIndex = next
                list.ensureIndexIsVisible(next)
                true
            }
            KeyEvent.VK_UP -> {
                e.consume()
                val prev = (list.selectedIndex - 1).coerceAtLeast(0)
                list.selectedIndex = prev
                list.ensureIndexIsVisible(prev)
                true
            }
            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                e.consume()
                confirmSelection()
                true
            }
            KeyEvent.VK_ESCAPE -> {
                e.consume()
                // Si on est dans un sous-menu : back. Sinon on ferme.
                if (!popSubmenu()) hide()
                true
            }
            else -> false
        }
    }

    private fun confirmSelection() {
        val picked = list.selectedValue ?: return
        when {
            picked.submenuProvider != null -> {
                // Drill-down dans le même popup. On vide le textarea car le filter (`/m`)
                // n'a plus de sens une fois qu'on est dans un sous-menu — sinon il reste
                // dans le textarea et pollue les futures actions (ex: insertText prepend).
                owner.text = ""
                openSubmenu(picked.name, picked.submenuProvider.invoke())
            }
            picked.onActivate != null -> {
                hide()
                // Idem : vide le textarea avant d'invoquer l'action terminal pour que les
                // insertText/sendDirectly partent d'un état propre.
                owner.text = ""
                picked.onActivate.invoke()
            }
            else -> {
                hide()
                onPick(picked.name)
            }
        }
    }

    private class EntryRenderer : ListCellRenderer<Entry> {
        private val panel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(2, 8) }
        private val nameLabel = JLabel()
        private val descLabel = JLabel()
        init {
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.add(nameLabel)
            panel.add(descLabel)
        }
        override fun getListCellRendererComponent(
            list: JList<out Entry>?, value: Entry?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            if (value == null) return panel
            val tag = when {
                value.isPlugin -> "<span style='color:#5b89d9'>[plugin]</span> "
                else -> ""
            }
            val iconPrefix = value.icon?.let { "$it " } ?: ""
            val checkSuffix = if (value.checked) "  <span style='color:#4caf50;'>✓</span>" else ""
            val submenuSuffix = if (value.submenuProvider != null) "  <span style='color:gray;'>▸</span>" else ""
            val displayName = if (value.isPlugin || value.submenuProvider != null) "/${value.name}" else value.name
            nameLabel.text = "<html>$tag$iconPrefix<b>$displayName</b>$checkSuffix$submenuSuffix</html>"
            nameLabel.font = nameLabel.font.deriveFont(Font.PLAIN, 12f)
            descLabel.text = "<html><span style='color:gray;font-size:10px;'>${value.description}</span></html>"
            panel.background = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            nameLabel.foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else JBColor.foreground()
            return panel
        }
    }
}
