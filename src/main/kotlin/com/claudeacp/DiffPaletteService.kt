package com.claudeacp

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.diff.util.TextDiffType
import java.awt.Color

/**
 * Application service qui applique des palettes de couleurs prédéfinies pour les diffs
 * IntelliJ. Modifie les `TextAttributes` du scheme actif pour les keys VCS/diff. Persiste
 * la palette choisie pour pouvoir restorer au boot suivant.
 *
 * Les palettes s'appliquent globalement (IntelliJ ne supporte pas le scope par-DiffRequest
 * de manière propre). Le user peut revert à n'importe quel moment.
 */
@Service(Service.Level.APP)
@State(name = "AgentNavDiffPalette", storages = [Storage("ClaudeACPSettings.xml")])
class DiffPaletteService : PersistentStateComponent<DiffPaletteService.State> {

    data class State(
        var selectedPalette: String = "default",
        // 6 couleurs persistées pour la palette CUSTOM (RGB hex)
        var customAddedBg: String = "#1f4d2b",
        var customAddedLine: String = "#2e7d32",
        var customDeletedBg: String = "#5a1e1e",
        var customDeletedLine: String = "#c62828",
        var customModifiedBg: String = "#44391e",
        var customModifiedLine: String = "#b78600"
    )

    private var state = State()
    override fun getState() = state
    override fun loadState(s: State) { state = s }

    val selectedPalette: String get() = state.selectedPalette

    enum class Palette(val displayName: String, val description: String) {
        DEFAULT("Default", "Reset to IntelliJ theme defaults"),
        PASTEL("Pastel", "Softer red/green, easier on the eyes"),
        SOFT("Soft", "Balanced — readable on both light and dark themes"),
        VIVID("Vivid", "Strong red/green, optimal for dark themes"),
        HIGH_CONTRAST("High contrast", "Max saturation, optimal for screen sharing/demos"),
        CUSTOM("Custom…", "Pick your own colors");

        fun colors(): PaletteColors? = when (this) {
            DEFAULT -> null
            PASTEL -> PaletteColors(
                addedBg = Color(0xc8e6c9), addedLine = Color(0x81c784),
                deletedBg = Color(0xffcdd2), deletedLine = Color(0xe57373),
                modifiedBg = Color(0xfff9c4), modifiedLine = Color(0xffd54f)
            )
            SOFT -> PaletteColors(
                addedBg = Color(0x3a7f47), addedLine = Color(0x66bb6a),
                deletedBg = Color(0x8b2a2a), deletedLine = Color(0xef5350),
                modifiedBg = Color(0x6e5417), modifiedLine = Color(0xffa726)
            )
            VIVID -> PaletteColors(
                addedBg = Color(0x1f4d2b), addedLine = Color(0x2e7d32),
                deletedBg = Color(0x5a1e1e), deletedLine = Color(0xc62828),
                modifiedBg = Color(0x44391e), modifiedLine = Color(0xb78600)
            )
            HIGH_CONTRAST -> PaletteColors(
                addedBg = Color(0x00c853), addedLine = Color(0x00e676),
                deletedBg = Color(0xd50000), deletedLine = Color(0xff1744),
                modifiedBg = Color(0xff6f00), modifiedLine = Color(0xffab00)
            )
            // CUSTOM est résolu via le state (settings persistés). On retourne null ici et
            // l'appelant utilise customColors() pour récupérer les valeurs courantes.
            CUSTOM -> null
        }
    }

    fun customColors(): PaletteColors = PaletteColors(
        addedBg = parseColor(state.customAddedBg, Color(0x1f4d2b)),
        addedLine = parseColor(state.customAddedLine, Color(0x2e7d32)),
        deletedBg = parseColor(state.customDeletedBg, Color(0x5a1e1e)),
        deletedLine = parseColor(state.customDeletedLine, Color(0xc62828)),
        modifiedBg = parseColor(state.customModifiedBg, Color(0x44391e)),
        modifiedLine = parseColor(state.customModifiedLine, Color(0xb78600))
    )

    fun setCustomColors(c: PaletteColors) {
        state.customAddedBg = c.addedBg.toHex()
        state.customAddedLine = c.addedLine.toHex()
        state.customDeletedBg = c.deletedBg.toHex()
        state.customDeletedLine = c.deletedLine.toHex()
        state.customModifiedBg = c.modifiedBg.toHex()
        state.customModifiedLine = c.modifiedLine.toHex()
        apply(Palette.CUSTOM)
    }

    private fun parseColor(hex: String, fallback: Color): Color = try {
        Color.decode(if (hex.startsWith("#")) hex else "#$hex")
    } catch (_: Exception) { fallback }

    private fun Color.toHex(): String = String.format("#%02x%02x%02x", red, green, blue)

    data class PaletteColors(
        val addedBg: Color, val addedLine: Color,
        val deletedBg: Color, val deletedLine: Color,
        val modifiedBg: Color, val modifiedLine: Color
    )

    /** Applique la palette au scheme global IntelliJ. Persiste le choix. */
    fun apply(palette: Palette) {
        state.selectedPalette = palette.name
        val scheme = EditorColorsManager.getInstance().globalScheme
        val colors: PaletteColors = when (palette) {
            Palette.DEFAULT -> { resetToDefaults(scheme); return }
            Palette.CUSTOM -> customColors()
            else -> palette.colors() ?: return
        }
        // Background des changements dans le diff (couleurs principales rouge/vert)
        scheme.setAttributes(diffKey("DIFF_INSERTED"), bgAttr(colors.addedBg))
        scheme.setAttributes(diffKey("DIFF_DELETED"), bgAttr(colors.deletedBg))
        scheme.setAttributes(diffKey("DIFF_MODIFIED"), bgAttr(colors.modifiedBg))
        // Marqueurs de lignes (gutter)
        scheme.setAttributes(diffKey("ADDED_LINES_COLOR"), bgAttr(colors.addedLine))
        scheme.setAttributes(diffKey("MODIFIED_LINES_COLOR"), bgAttr(colors.modifiedLine))
        // Highlights line bg côté éditeur
        runCatching {
            scheme.setColor(EditorColors.ADDED_LINES_COLOR, colors.addedLine)
            scheme.setColor(EditorColors.MODIFIED_LINES_COLOR, colors.modifiedLine)
            scheme.setColor(EditorColors.WHITESPACES_MODIFIED_LINES_COLOR, colors.modifiedLine.darker())
        }
    }

    private fun resetToDefaults(scheme: EditorColorsScheme) {
        listOf("DIFF_INSERTED", "DIFF_DELETED", "DIFF_MODIFIED",
            "ADDED_LINES_COLOR", "MODIFIED_LINES_COLOR").forEach {
            scheme.setAttributes(diffKey(it), TextAttributes())
        }
    }

    private fun diffKey(name: String): TextAttributesKey =
        TextAttributesKey.find(name)

    private fun bgAttr(c: Color): TextAttributes {
        val a = TextAttributes()
        a.backgroundColor = c
        return a
    }

    /** À appeler au boot pour ré-appliquer la palette persistée. */
    fun restoreOnStartup() {
        val p = runCatching { Palette.valueOf(state.selectedPalette) }.getOrNull() ?: Palette.DEFAULT
        apply(p)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun _diffType(@Suppress("unused") t: TextDiffType) {}

    companion object {
        fun getInstance(): DiffPaletteService = service()
    }
}
