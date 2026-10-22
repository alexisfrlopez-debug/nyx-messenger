// Tests JVM de la POLITIQUE DE DÉFILEMENT de l'écran principal (v0.2.38).
//
// Anti-régression du retour opérateur (RÉCIDIVE sur v0.2.36 malgré le fix
// v0.2.31) : « l'écran principal de la messagerie ne défile pas, il faudrait
// vérifier systématiquement le défilement de toutes les fenêtres ». La cause
// racine : la zone FIXE (bannières + cartes de diagnostic du build test)
// n'avait AUCUN plafond — elle pouvait occuper plus que l'écran entier
// (appareil compact, grande police, bannières d'erreur multi-lignes, carte
// plan déployée) et la zone d'onglets weight(1f) tombait alors à ~0 px :
// plus rien ne défilait. ScrollPolicy (objet pur Kotlin, AUCUN type Compose)
// encode le contrat : zone fixe plafonnée à 40 % de la hauteur du corps,
// zone d'onglets garantie ≥ 60 % moins le chrome des onglets. Ce test
// verrouille l'invariant — aucune « simplification » du plafond ne
// repassera inaperçue (même motif que NyxContrastTest pour la palette).
package net.nyx.messenger.ui

import net.nyx.messenger.ui.conversations.ScrollPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollPolicyTest {

    /** Balayage des hauteurs de corps réalistes : 240 dp (compact extrême,
     *  clavier ouvert) → 1 200 dp (tablette). Pas de 40 dp. */
    private val bodies = (240..1200 step 40).map { it.toFloat() }

    @Test
    fun `plafond zone fixe = 40 pour cent exactement`() {
        assertEquals(0.40f, ScrollPolicy.HEADER_MAX_FRACTION)
        for (a in bodies) {
            assertEquals(
                "headerMaxDp($a)",
                a * 0.40f,
                ScrollPolicy.headerMaxDp(a),
                0.001f
            )
        }
    }

    @Test
    fun `liste garantie positive pour tout écran réaliste`() {
        // 160 dp de corps (écran minuscule + clavier) laisse encore
        // 0,6×160 − 56 = 40 dp de liste : JAMAIS zéro.
        for (a in bodies) {
            val list = ScrollPolicy.listMinDp(a)
            assertTrue(
                "zone d'onglets à ${"%.0f".format(a)} dp : $list dp (doit être > 0)",
                list > 0f
            )
        }
    }

    @Test
    fun `liste garde au moins la moitié de l'écran sur un téléphone`() {
        // Corps d'un téléphone réaliste : ≥ 600 dp utilisables (l'app bar est
        // hors corps). La liste défilante doit garder ≥ 50 % de la hauteur.
        for (a in bodies.filter { it >= 600f }) {
            val share = ScrollPolicy.listMinDp(a) / a
            assertTrue(
                "part liste à ${"%.0f".format(a)} dp : ${"%.0f".format(share * 100)} % (min 50 %)",
                share >= 0.50f
            )
        }
    }

    @Test
    fun `identité de non-chevauchement — fixe plus liste plus chrome égale corps`() {
        // headerMax + listMin + TAB_CHROME reconstruit EXACTEMENT la hauteur
        // du corps — aucune zone ne peut en empiéter sur une autre.
        for (a in bodies) {
            assertEquals(
                "somme des zones à ${"%.0f".format(a)} dp",
                a,
                ScrollPolicy.headerMaxDp(a) + ScrollPolicy.listMinDp(a) + ScrollPolicy.TAB_CHROME_DP,
                0.001f
            )
        }
    }

    @Test
    fun `croissance monotone des deux zones avec l'écran`() {
        for (i in 1 until bodies.size) {
            val prev = bodies[i - 1]; val cur = bodies[i]
            assertTrue(
                "headerMax non croissante entre $prev et $cur",
                ScrollPolicy.headerMaxDp(cur) >= ScrollPolicy.headerMaxDp(prev)
            )
            assertTrue(
                "listMin non croissante entre $prev et $cur",
                ScrollPolicy.listMinDp(cur) >= ScrollPolicy.listMinDp(prev)
            )
        }
    }

    /** Garde-fou documentaire : si quelqu'un modifie le plafond, ce test
     *  l'oblige à mettre à jour AUSSI le contrat ci-dessous (les deux moitiés
     *  de l'invariant bougent ensemble — la part de liste minimale est
     *  DÉFINIE par complément, pas par une constante indépendante). */
    @Test
    fun `parts complémentaires par construction`() {
        assertEquals(
            1f,
            ScrollPolicy.HEADER_MAX_FRACTION + ScrollPolicy.LIST_MIN_FRACTION,
            0.0001f
        )
    }
}
