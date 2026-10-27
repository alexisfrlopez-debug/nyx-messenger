package net.nyx.messenger.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.3.5 (session 75) — tests de la memoïsation du rendezvous signé.
 *
 * POURQUOI : la fusion des réplicas est rafraîchie à chaque cycle de poll
 * (30 s) ; AVANT v0.3.5 elle re-vérifiait la signature ed25519 et rejouait
 * l'avertissement « rendezvous signé INVALIDE » à chaque cycle — l'export
 * terrain v0.3.4 montrait 12 occurrences en 4 minutes (spam + travail
 * inutile pour une ressource embarquée immuable). L'Android vérifie UNE fois
 * au boot ; [RendezvousOnce] retrouve ce comportement.
 */
class RendezvousOnceTest {

    @Test
    fun `calcule une seule fois malgre des appels repetes`() {
        var calls = 0
        val once = RendezvousOnce { calls++; listOf("alpha.onion:80") }
        assertFalse(once.isComputed())
        assertEquals(listOf("alpha.onion:80"), once.entries())
        assertEquals(listOf("alpha.onion:80"), once.entries())
        assertEquals(listOf("alpha.onion:80"), once.entries())
        assertEquals("le calcul ne doit PAS être rejoué (spam 30 s tué)", 1, calls)
        assertTrue(once.isComputed())
    }

    @Test
    fun `liste vide du premier calcul reste servie du cache`() {
        var calls = 0
        val once = RendezvousOnce { calls++; emptyList() }
        assertEquals(emptyList<String>(), once.entries())
        assertEquals(emptyList<String>(), once.entries())
        assertEquals(1, calls)
    }

    @Test
    fun `concurrence - un seul calcul sous accès simultanés`() {
        var calls = 0
        val once = RendezvousOnce {
            Thread.sleep(50) // simule la vérification ed25519
            synchronized(this) { calls++ }
            listOf("beta.onion:80")
        }
        val threads = (1..4).map {
            Thread { assertEquals(listOf("beta.onion:80"), once.entries()) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("4 threads simultanés = exactement UN calcul", 1, calls)
    }
}
