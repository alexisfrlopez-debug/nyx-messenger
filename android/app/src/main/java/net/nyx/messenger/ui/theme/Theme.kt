package net.nyx.messenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.nyx.messenger.settings.SettingsManager

/**
 * Thème « Nuit de Nyx » (v0.2.35 — refonte esthétique, demande opérateur :
 * « moins technique et plus agréable, tout en gardant la thématique
 * Nyx — déesse de la nuit »).
 *
 * PALETTE : la nuit garde la profondeur (indigo presque noir en bas de ciel,
 * violet doux au zénith), la LUNE devient la couleur principale — un or pâle
 * et chaleureux (boutons, actions) —, la nébuleuse (lavande) accompagne, et
 * un jade tendre remplace l'ancien « vert oignon » pour les états positifs
 * (clin d'œil conservé, adouci). Les messages d'erreur passent au rose crépuscule.
 * L'ancien couple violet nuit / vert olive (flat, « technique ») est retiré.
 *
 * FORMES : coins généreux (cartes 18 dp, dialogues 24 dp, éléments 12 dp) —
 * plus doux à l'œil que les angles M3 par défaut.
 *
 * FOND : dégradé de nuit vertical derrière TOUT l'écran ([nyxNightBackground] —
 * appliqué à la racine de MainActivity ; les Scaffold sont transparents).
 * v0.2.0 : le mode (système / clair / sombre) reste un réglage utilisateur
 * ([SettingsManager.ThemeMode]) — la valeur explicite prime sur le système ;
 * le mode clair est une « aube » ivoire-lavande, toujours sans éclat agressif.
 */
// — Nuit (sombre) —
private val ZenithViolet = Color(0xFF1A1440)   // haut du ciel
private val NightIndigo = Color(0xFF110D2B)    // milieu (couleur de fond unie)
private val HorizonDark = Color(0xFF0C0922)     // bas du ciel, presque noir
private val MoonGold = Color(0xFFE8D5A8)        // clair de lune — principal
private val OnMoon = Color(0xFF241B06)         // texte sur or
private val NebulaLavender = Color(0xFFC7B8FF)  // nébuleuse — secondaire
private val SoftJade = Color(0xFF8FD8C6)        // jade tendre — états positifs
private val DuskRose = Color(0xFFFFB4AB)        // rose crépuscule — erreurs
private val MoonlitWhite = Color(0xFFF1EDFB)   // texte sur nuit
private val NightSurface = Color(0xFF1B1540)    // cartes
private val NightOutline = Color(0xFF8A82B8)    // contours indigo doux

// — Aube (clair) —
private val DawnIvory = Color(0xFFFDFAFF)
private val DawnHaze = Color(0xFFF1EDFB)
private val DawnIndigo = Color(0xFF4B3F96)      // indigo — principal clair
private val BronzeDawn = Color(0xFF7A5E33)      // or de bronze — secondaire clair
private val JadeDawn = Color(0xFF1F6E5C)
private val InkNavy = Color(0xFF1D1A33)

private val DarkScheme = darkColorScheme(
    primary = MoonGold,
    onPrimary = OnMoon,
    secondary = NebulaLavender,
    onSecondary = Color(0xFF241B52),
    tertiary = SoftJade,
    onTertiary = Color(0xFF06231D),
    background = NightIndigo,
    onBackground = MoonlitWhite,
    surface = NightSurface,
    onSurface = MoonlitWhite,
    surfaceVariant = Color(0xFF241C52),
    onSurfaceVariant = Color(0xFFD6D0F0),
    error = DuskRose,
    onError = Color(0xFF4A0716),
    errorContainer = Color(0xFF4A0716),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = NightOutline,
    outlineVariant = Color(0xFF3A3166)
)

private val LightScheme = lightColorScheme(
    primary = DawnIndigo,
    onPrimary = Color.White,
    secondary = BronzeDawn,
    onSecondary = Color.White,
    tertiary = JadeDawn,
    background = DawnIvory,
    onBackground = InkNavy,
    surface = Color.White,
    onSurface = InkNavy,
    surfaceVariant = Color(0xFFEDE9FA),
    onSurfaceVariant = Color(0xFF453F66),
    outline = Color(0xFF8A82B8)
)

/** Coins doux — cartes 18 dp, dialogues 24 dp, petits éléments 12 dp. */
private val NyxShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * Dégradé de fond « ciel de nuit » (sombre) / « aube » (clair) — appliqué à la
 * RACINE de l'UI (MainActivity) : les écrans, Scaffold et LockScreen dessinent
 * par-dessus sans le masquer (containers transparents).
 */
fun nyxNightBackground(dark: Boolean): Brush = if (dark) {
    Brush.verticalGradient(listOf(ZenithViolet, NightIndigo, HorizonDark))
} else {
    Brush.verticalGradient(listOf(DawnIvory, DawnHaze))
}

/** Fond commun — enveloppe la racine de l'application (v0.2.35). */
@Composable
fun NyxBackground(dark: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(nyxNightBackground(dark))) { content() }
}

/** Variante par réglage utilisateur — déduit le mode et enveloppe (v0.2.35). */
@Composable
fun NyxBackground(mode: SettingsManager.ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        SettingsManager.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        SettingsManager.ThemeMode.LIGHT -> false
        SettingsManager.ThemeMode.DARK -> true
    }
    NyxBackground(dark = dark, content = content)
}

@Composable
fun NyxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = NyxShapes,
        content = content
    )
}

/** Variante pilotée par le réglage utilisateur (v0.2.0) : mode explicite. */
@Composable
fun NyxTheme(
    mode: SettingsManager.ThemeMode,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        SettingsManager.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        SettingsManager.ThemeMode.LIGHT -> false
        SettingsManager.ThemeMode.DARK -> true
    }
    NyxTheme(darkTheme = dark, content = content)
}
