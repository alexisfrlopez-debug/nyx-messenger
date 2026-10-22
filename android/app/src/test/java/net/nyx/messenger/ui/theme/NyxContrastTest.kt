// Tests JVM de CONTRASTE de la palette « Nuit de Nyx » (v0.2.36).
//
// Anti-régression du retour opérateur v0.2.35 : « en mode "sombre" certains
// textes sont affichés en noir sur fond bleu foncé, ce qui est peu lisible ».
// La palette vit dans NyxPalette (objet pur Kotlin) précisément pour ces
// tests : chaque paire texte/fond des DEUX schémas (nuit + aube), des DEUX
// dégradés (3 arrêts nuit, 2 arrêts aube) et de l'ambre d'attente doit
// respecter le ratio WCAG 2.1 ≥ 4,5:1 (seuil « texte normal lisible »).
// La fonction racine rootContentColor() est verrouillée : c'est elle que
// NyxBackground fournit à LocalContentColor (le défaut M3 est NOIR).
package net.nyx.messenger.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NyxContrastTest {

    // ---- WCAG 2.1 : luminance relative + ratio de contraste ----

    private fun linear(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertLisible(texte: String, fond: String, r: Double) {
        assertTrue(
            "contraste insuffisant %.2f:1 (min 4,5) : $texte sur $fond".format(Locale.ROOT, r),
            r >= 4.5
        )
    }

    private fun verrouille(paires: List<Triple<String, String, Double>>) {
        paires.forEach { (t, f, r) -> assertLisible(t, f, r) }
    }

    // ---- Racine : rootContentColor (le fix v0.2.36) ----

    @Test
    fun `racine - contenu jamais noir sur le ciel de nuit`() {
        val racine = NyxPalette.rootContentColor(NyxPalette.darkScheme)
        verrouille(
            listOf(
                Triple("rootContentColor(nuit)", "zénith violet", ratio(racine, NyxPalette.zenithViolet)),
                Triple("rootContentColor(nuit)", "indigo nuit", ratio(racine, NyxPalette.nightIndigo)),
                Triple("rootContentColor(nuit)", "horizon sombre", ratio(racine, NyxPalette.horizonDark)),
                Triple("rootContentColor(nuit)", "carte nuit", ratio(racine, NyxPalette.nightSurface)),
                Triple("rootContentColor(nuit)", "variante surface", ratio(racine, NyxPalette.darkScheme.surfaceVariant)),
            )
        )
        // Le bug v0.2.35 : le DÉFAUT de LocalContentColor est NOIR — le ratio
        // sur l'indigo nuit doit être CATASTROPHIQUE (preuve du correctif).
        val noir = Color.Black
        assertTrue(
            "le noir par défaut devrait être illisible sur l'indigo nuit (sinon le test ne prouve rien)",
            ratio(noir, NyxPalette.nightIndigo) < 2.0
        )
    }

    @Test
    fun `racine - contenu encre marine sur l'aube`() {
        val racine = NyxPalette.rootContentColor(NyxPalette.lightScheme)
        verrouille(
            listOf(
                Triple("rootContentColor(aube)", "ivoire aube", ratio(racine, NyxPalette.dawnIvory)),
                Triple("rootContentColor(aube)", "brume aube", ratio(racine, NyxPalette.dawnHaze)),
                Triple("rootContentColor(aube)", "surface blanche", ratio(racine, Color.White)),
            )
        )
    }

    // ---- Schéma NUIT : paires texte/fond ----

    @Test
    fun `nuit - rôles de texte sur fonds du schéma sombre`() {
        val s = NyxPalette.darkScheme
        verrouille(
            listOf(
                Triple("onBackground", "background", ratio(s.onBackground, s.background)),
                Triple("onSurface", "surface", ratio(s.onSurface, s.surface)),
                Triple("onSurfaceVariant", "surfaceVariant", ratio(s.onSurfaceVariant, s.surfaceVariant)),
                Triple("onSurfaceVariant", "surface", ratio(s.onSurfaceVariant, s.surface)),
                Triple("primary (lune)", "background", ratio(s.primary, s.background)),
                Triple("primary (lune)", "surface", ratio(s.primary, s.surface)),
                Triple("secondary (nébuleuse)", "background", ratio(s.secondary, s.background)),
                Triple("tertiary (jade)", "background", ratio(s.tertiary, s.background)),
                Triple("tertiary (jade)", "surface", ratio(s.tertiary, s.surface)),
                Triple("error (rose crépuscule)", "background", ratio(s.error, s.background)),
                Triple("error (rose crépuscule)", "surface", ratio(s.error, s.surface)),
                Triple("onErrorContainer", "errorContainer", ratio(s.onErrorContainer, s.errorContainer)),
            )
        )
    }

    @Test
    fun `nuit - texte sur couleurs pleines (boutons, badges)`() {
        val s = NyxPalette.darkScheme
        verrouille(
            listOf(
                Triple("onPrimary", "primary", ratio(s.onPrimary, s.primary)),
                Triple("onSecondary", "secondary", ratio(s.onSecondary, s.secondary)),
                Triple("onTertiary", "tertiary", ratio(s.onTertiary, s.tertiary)),
                Triple("onError", "error", ratio(s.onError, s.error)),
            )
        )
    }

    // ---- Schéma AUBE : paires texte/fond ----

    @Test
    fun `aube - rôles de texte sur fonds du schéma clair`() {
        val s = NyxPalette.lightScheme
        verrouille(
            listOf(
                Triple("onBackground", "background", ratio(s.onBackground, s.background)),
                Triple("onSurface", "surface", ratio(s.onSurface, s.surface)),
                Triple("onSurfaceVariant", "surfaceVariant", ratio(s.onSurfaceVariant, s.surfaceVariant)),
                Triple("onSurfaceVariant", "background", ratio(s.onSurfaceVariant, s.background)),
                Triple("primary (indigo)", "background", ratio(s.primary, s.background)),
                Triple("secondary (bronze)", "background", ratio(s.secondary, s.background)),
                Triple("tertiary (jade aube)", "background", ratio(s.tertiary, s.background)),
                Triple("error", "background", ratio(s.error, s.background)),
                Triple("onPrimary (blanc)", "primary", ratio(s.onPrimary, s.primary)),
            )
        )
    }

    // ---- Ambre « en attente » (remplace le #F9A825 codé en dur) ----

    @Test
    fun `ambre d attente lisible en nuit et en aube`() {
        verrouille(
            listOf(
                Triple("ambre nuit", "indigo nuit", ratio(NyxPalette.amberNight, NyxPalette.nightIndigo)),
                Triple("ambre nuit", "carte nuit", ratio(NyxPalette.amberNight, NyxPalette.nightSurface)),
                Triple("ambre nuit", "horizon sombre", ratio(NyxPalette.amberNight, NyxPalette.horizonDark)),
                Triple("ambre aube", "ivoire aube", ratio(NyxPalette.amberDawn, NyxPalette.dawnIvory)),
                Triple("ambre aube", "brume aube", ratio(NyxPalette.amberDawn, NyxPalette.dawnHaze)),
                Triple("ambre aube", "surface blanche", ratio(NyxPalette.amberDawn, Color.White)),
            )
        )
        // pendingAmber(dark) rend la bonne teinte selon le mode.
        assertTrue(NyxPalette.pendingAmber(true) == NyxPalette.amberNight)
        assertTrue(NyxPalette.pendingAmber(false) == NyxPalette.amberDawn)
    }
}
