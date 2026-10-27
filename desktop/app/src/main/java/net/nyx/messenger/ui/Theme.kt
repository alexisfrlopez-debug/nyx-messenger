package net.nyx.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Palette « Nuit de Nyx » — portage desktop de NyxPalette Android (v0.2.36) :
 * mêmes couleurs (la nuit garde la profondeur, la LUNE est la couleur
 * principale, jade = positif, rose crépuscule = erreurs ; aube ivoire-lavande
 * en clair).
 */
object NyxPalette {
    // — Nuit (sombre) —
    val zenithViolet = Color(0xFF1A1440)
    val nightIndigo = Color(0xFF110D2B)
    val horizonDark = Color(0xFF0C0922)
    val moonGold = Color(0xFFE8D5A8)
    val onMoon = Color(0xFF241B06)
    val nebulaLavender = Color(0xFFC7B8FF)
    val softJade = Color(0xFF8FD8C6)
    val duskRose = Color(0xFFFFB4AB)
    val moonlitWhite = Color(0xFFF1EDFB)
    val nightSurface = Color(0xFF1B1540)
    val nightOutline = Color(0xFF8A82B8)

    // — Aube (clair) —
    val dawnIvory = Color(0xFFFDFAFF)
    val dawnHaze = Color(0xFFF1EDFB)
    val dawnIndigo = Color(0xFF4B3F96)
    val bronzeDawn = Color(0xFF7A5E33)
    val jadeDawn = Color(0xFF1F6E5C)
    val inkNavy = Color(0xFF1D1A33)

    val amberNight = Color(0xFFF9A825)
    val amberDawn = Color(0xFF806000)

    val darkScheme = darkColorScheme(
        primary = moonGold,
        onPrimary = onMoon,
        secondary = nebulaLavender,
        onSecondary = Color(0xFF241B52),
        tertiary = softJade,
        onTertiary = Color(0xFF06231D),
        background = nightIndigo,
        onBackground = moonlitWhite,
        surface = nightSurface,
        onSurface = moonlitWhite,
        surfaceVariant = Color(0xFF241C52),
        onSurfaceVariant = Color(0xFFD6D0F0),
        error = duskRose,
        onError = Color(0xFF4A0716),
        outline = nightOutline,
        outlineVariant = Color(0xFF3A3166)
    )

    val lightScheme = lightColorScheme(
        primary = dawnIndigo,
        onPrimary = Color.White,
        secondary = bronzeDawn,
        onSecondary = Color.White,
        tertiary = jadeDawn,
        background = dawnIvory,
        onBackground = inkNavy,
        surface = dawnIvory,
        onSurface = inkNavy,
        surfaceVariant = dawnHaze,
        onSurfaceVariant = Color(0xFF4B4670),
        error = Color(0xFFB3261E),
        onError = Color.White,
        outline = Color(0xFF7A7392),
        outlineVariant = Color(0xFFD9D2E8)
    )
}

/** Thème selon le réglage (« nuit » par défaut — la messagerie vit la nuit). */
@Composable
fun NyxTheme(setting: String, content: @Composable () -> Unit) {
    val dark = when (setting) {
        "aube" -> false
        "nuit" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) NyxPalette.darkScheme else NyxPalette.lightScheme,
        content = content
    )
}
