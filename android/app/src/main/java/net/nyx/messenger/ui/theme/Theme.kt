package net.nyx.messenger.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import net.nyx.messenger.settings.SettingsManager

/**
 * Thème « Nuit de Nyx » (v0.2.35 — refonte esthétique, demande opérateur :
 * « moins technique et plus agréable, tout en gardant la thématique
 * Nyx — déesse de la nuit »).
 *
 * PALETTE : voir [NyxPalette] (sortie en objet interne v0.2.36 pour les
 * tests de contraste NyxContrastTest) — la nuit garde la profondeur (indigo
 * presque noir en bas de ciel, violet doux au zénith), la LUNE devient la
 * couleur principale — un or pâle et chaleureux (boutons, actions) —, la
 * nébuleuse (lavande) accompagne, et un jade tendre signale les états
 * positifs. Les messages d'erreur passent au rose crépuscule.
 *
 * FORMES : coins généreux (cartes 18 dp, dialogues 24 dp, éléments 12 dp).
 *
 * FOND : dégradé de nuit vertical derrière TOUT l'écran ([nyxNightBackground]
 * appliqué à la racine de MainActivity ; les Scaffold sont transparents).
 * Le mode (système / clair / sombre) reste un réglage utilisateur
 * ([SettingsManager.ThemeMode]) — la valeur explicite prime sur le système ;
 * le mode clair est une « aube » ivoire-lavande.
 *
 * v0.2.36 — CORRECTIF LISIBILITÉ MODE SOMBRE (retour opérateur : « certains
 * textes sont affichés en noir sur fond bleu foncé ») : [NyxBackground]
 * fournit désormais [LocalContentColor] = onBackground — les Scaffold
 * transparents (contentColorFor(Transparent) = Unspecified) héritaient du
 * DÉFAUT NOIR de ce CompositionLocal pour tout Text()/Icon() sans couleur
 * explicite. Verrouillé par NyxContrastTest (ratios WCAG ≥ 4,5:1).
 */
fun nyxNightBackground(dark: Boolean): Brush = NyxPalette.nightBackground(dark)

/** Fond commun — enveloppe la racine de l'application (v0.2.35). */
@Composable
fun NyxBackground(dark: Boolean, content: @Composable () -> Unit) {
    // v0.2.36 — CORRECTIF LISIBILITÉ : fournir la couleur de contenu racine.
    // NyxBackground est un simple Box : sans fournisseur explicite,
    // LocalContentColor garde son défaut M3 (NOIR) ; les Scaffold transparents
    // ne le redéfinissent pas (contentColorFor(Transparent) = Unspecified) et
    // tout texte sans couleur explicite rendait en noir sur le ciel de nuit.
    CompositionLocalProvider(
        LocalContentColor provides NyxPalette.rootContentColor(MaterialTheme.colorScheme)
    ) {
        Box(Modifier.fillMaxSize().background(NyxPalette.nightBackground(dark))) { content() }
    }
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

/** Ambre « en attente » thème-aware (v0.2.36) — lisible nuit ET aube ;
 *  remplace le #F9A825 codé en dur (faible contraste sur l'aube ivoire). */
@Composable
fun pendingAmber(): Color =
    NyxPalette.pendingAmber(MaterialTheme.colorScheme.background.luminance() > 0.5f)

@Composable
fun NyxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NyxPalette.darkScheme else NyxPalette.lightScheme,
        shapes = NyxPalette.shapes,
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
