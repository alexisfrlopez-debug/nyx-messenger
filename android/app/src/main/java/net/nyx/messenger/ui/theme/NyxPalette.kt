package net.nyx.messenger.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Palette « Nuit de Nyx » — v0.2.36 : couleurs, schémas et formes sortis de
 * Theme.kt vers cet objet INTERNE (pur Kotlin, aucune API Android) pour être
 * TESTABLES en JVM : [NyxContrastTest] verrouille les ratios de contraste
 * WCAG ≥ 4,5:1 de toutes les paires texte/fond — anti-régression du retour
 * opérateur v0.2.35 (« textes en noir sur fond bleu foncé »).
 *
 * Sémantique inchangée depuis la refonte v0.2.35 : la nuit garde la
 * profondeur (indigo presque noir en bas de ciel, violet doux au zénith), la
 * LUNE est la couleur principale (or pâle), la nébuleuse (lavande)
 * accompagne, le jade tendre signale les états positifs, le rose crépuscule
 * les erreurs. Le mode clair est une « aube » ivoire-lavande.
 */
internal object NyxPalette {

    // — Nuit (sombre) —
    val zenithViolet = Color(0xFF1A1440)   // haut du ciel
    val nightIndigo = Color(0xFF110D2B)    // milieu (couleur de fond unie)
    val horizonDark = Color(0xFF0C0922)    // bas du ciel, presque noir
    val moonGold = Color(0xFFE8D5A8)       // clair de lune — principal
    val onMoon = Color(0xFF241B06)         // texte sur or
    val nebulaLavender = Color(0xFFC7B8FF) // nébuleuse — secondaire
    val softJade = Color(0xFF8FD8C6)       // jade tendre — états positifs
    val duskRose = Color(0xFFFFB4AB)       // rose crépuscule — erreurs
    val moonlitWhite = Color(0xFFF1EDFB)   // texte sur nuit
    val nightSurface = Color(0xFF1B1540)   // cartes
    val nightOutline = Color(0xFF8A82B8)   // contours indigo doux

    // — Aube (clair) —
    val dawnIvory = Color(0xFFFDFAFF)
    val dawnHaze = Color(0xFFF1EDFB)
    val dawnIndigo = Color(0xFF4B3F96)     // indigo — principal clair
    val bronzeDawn = Color(0xFF7A5E33)     // or de bronze — secondaire clair
    val jadeDawn = Color(0xFF1F6E5C)
    val inkNavy = Color(0xFF1D1A33)

    // — Ambre « en attente » (v0.2.36 — remplace le #F9A825 codé en dur,
    //    illisible sur l'aube ivoire) —
    val amberNight = Color(0xFFF9A825)     // ambre lumineux (sur nuit)
    val amberDawn = Color(0xFF806000)      // ambre profond (sur aube)

    val darkScheme: ColorScheme = darkColorScheme(
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
        errorContainer = Color(0xFF4A0716),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = nightOutline,
        outlineVariant = Color(0xFF3A3166)
    )

    val lightScheme: ColorScheme = lightColorScheme(
        primary = dawnIndigo,
        onPrimary = Color.White,
        secondary = bronzeDawn,
        onSecondary = Color.White,
        tertiary = jadeDawn,
        background = dawnIvory,
        onBackground = inkNavy,
        surface = Color.White,
        onSurface = inkNavy,
        surfaceVariant = Color(0xFFEDE9FA),
        onSurfaceVariant = Color(0xFF453F66),
        outline = Color(0xFF8A82B8)
    )

    /** Coins doux — cartes 18 dp, dialogues 24 dp, petits éléments 12 dp. */
    val shapes = Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp)
    )

    /** Dégradé de fond « ciel de nuit » (sombre) / « aube » (clair). */
    fun nightBackground(dark: Boolean): Brush = if (dark) {
        Brush.verticalGradient(listOf(zenithViolet, nightIndigo, horizonDark))
    } else {
        Brush.verticalGradient(listOf(dawnIvory, dawnHaze))
    }

    /**
     * Couleur de contenu racine (v0.2.36) : ce que [LocalContentColor] DOIT
     * valoir à la racine de l'app. Le défaut M3 est NOIR — depuis que les
     * Scaffold sont transparents (v0.2.35), tout Text()/Icon() sans couleur
     * explicite héritait du noir sur le ciel de nuit (retour opérateur).
     * Pure pour testabilité (contrastée dans NyxContrastTest).
     */
    fun rootContentColor(scheme: ColorScheme): Color = scheme.onBackground

    /** Ambre « en attente » thème-aware (v0.2.36) — lisible nuit ET aube. */
    fun pendingAmber(dark: Boolean): Color = if (dark) amberNight else amberDawn
}
