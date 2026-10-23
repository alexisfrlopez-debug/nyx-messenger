package net.nyx.messenger.repo

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * v0.2.45 — CONTRAT DU HSFETCH DE GUÉRISON (cause racine terrain 23/10,
 * session 67 : Blackview v0.2.44 timeout 25 s en boucle vers le serveur
 * après le déploiement 19:03:17 — descripteur orphelin CACHÉ ~24 h côté
 * client ; Xiaomi au cache vierge le joignait en 227 ms).
 */
class DescriptorHealTest {

    private val COOLDOWN = 5 * 60_000L

    @Test
    fun `un seul échec ne déclenche rien (panne passagère - pas un cache empoisonné)`() {
        assertFalse(
            DescriptorHeal.shouldHeal(1, 0L, 1_000L, COOLDOWN)
        )
    }

    @Test
    fun `deux échecs consécutifs déclenchent le HSFETCH de guérison`() {
        assertTrue(
            DescriptorHeal.shouldHeal(2, 0L, 1_000L, COOLDOWN)
        )
    }

    @Test
    fun `cooldown - pas de second HSFETCH dans les 5 minutes (anti-spam contrôle)`() {
        // Dernière guérison il y a 2 min → nouvel échec consécutif → refus.
        assertFalse(
            DescriptorHeal.shouldHeal(3, 10_000L, 10_000L + 2 * 60_000L, COOLDOWN)
        )
    }

    @Test
    fun `après le cooldown, un nœud toujours en échec est re-guéri`() {
        assertTrue(
            DescriptorHeal.shouldHeal(4, 10_000L, 10_000L + COOLDOWN + 1, COOLDOWN)
        )
    }

    @Test
    fun `jamais de guérison sous le seuil même après cooldown`() {
        assertFalse(
            DescriptorHeal.shouldHeal(1, 0L, 100L * COOLDOWN, COOLDOWN)
        )
    }
}
