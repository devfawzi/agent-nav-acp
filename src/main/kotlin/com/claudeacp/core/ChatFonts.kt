package com.claudeacp.core

import java.awt.Font
import java.awt.GraphicsEnvironment

/**
 * Pickup du font pour le chat. On vise un rendu type Cursor (sans-serif moderne, clean).
 *
 * Stratégie de fallback :
 *   1. Inter (bundlé par IntelliJ ≥ 2024.2 et installable sur la plupart des OS, identique à Cursor)
 *   2. SF Pro Text / SF Pro Display (Mac)
 *   3. Segoe UI Variable / Segoe UI (Windows)
 *   4. Cantarell (GNOME), Ubuntu, Noto Sans (Linux)
 *   5. Font.SANS_SERIF (Java built-in fallback)
 *
 * Le système Java renvoie Font.SANS_SERIF si on demande une famille inconnue, donc on
 * pré-scan availableFontFamilyNames pour choisir un nom EXISTANT et éviter le fallback
 * silencieux qui donne un Liberation Sans pas terrible.
 */
object ChatFonts {

    private val preferredFamilies = listOf(
        "Inter Variable", "Inter",
        "SF Pro Text", "SF Pro Display",
        "Segoe UI Variable", "Segoe UI",
        "Cantarell", "Ubuntu", "Noto Sans"
    )

    val family: String by lazy {
        val available = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        preferredFamilies.firstOrNull { it in available } ?: Font.SANS_SERIF
    }

    fun regular(size: Int): Font = Font(family, Font.PLAIN, size)
    fun bold(size: Int): Font = Font(family, Font.BOLD, size)
    fun italic(size: Int): Font = Font(family, Font.ITALIC, size)

    /** Family CSS pour les composants HTML (AssistantMessage rendu en JEditorPane). */
    fun cssFamily(): String = """"$family", -apple-system, "Segoe UI", "Helvetica Neue", sans-serif"""
}
