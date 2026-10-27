package net.nyx.messenger.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.46 — DÉCISION DU REPLI LIEN INVERSÉ (ADR-018) : vecteurs purs du
 * terrain 23/10 — l'onion d'un PAIR injoignable se sert par le canal sortant
 * du téléphone, un SERVEUR de flotte en échec ne le peut PAS (le WAIT
 * brûlerait 75 s pour rien), et le routeur n'est JAMAIS le pair lui-même.
 */
class LinkFallbackTest {

    // Onions RÉELS du terrain 23/10 (56 caractères base32 — le kxcw… du
    // Blackview, tronqués à 52 car. le test échouait sur la garde de longueur).
    private val peer1 = "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774qklid.onion"
    private val peer1Port = "$peer1:80"
    private val peer2 = "vxb5tsk44ikklazo2spqpng33w4wjfwewcylnw4kiheit25u6ive7zyd.onion"
    private val fleet = "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion"

    @Test
    fun `réplica de pair en échec - repli applicable`() {
        // Le pair1 (découvert) est injoignable ; le serveur de flotte route.
        assertTrue(
            LinkFallback.applicable(
                peer1,
                discovered = listOf(peer1Port, peer2),
                others = listOf(fleet, peer1),
                socksPort = 9050,
            )
        )
    }

    @Test
    fun `serveur de flotte en échec - repli NON applicable (pas de canal serveur)`() {
        // Le serveur central n'est pas un téléphone : personne ne tirera les
        // requêtes /link/pull — ne pas tenter (le WAIT de 75 s serait brûlé).
        assertFalse(
            LinkFallback.applicable(
                fleet,
                discovered = listOf(peer1Port, peer2),
                others = listOf(peer1),
                socksPort = 9050,
            )
        )
    }

    @Test
    fun `adresse inconnue - non applicable`() {
        assertFalse(
            LinkFallback.applicable(
                "pasunonion",
                discovered = listOf(peer1Port),
                others = listOf(fleet),
                socksPort = 9050,
            )
        )
        // Onion mal formé (56 caractères exigés).
        assertFalse(
            LinkFallback.applicable(
                "abcdef.onion",
                discovered = listOf("abcdef.onion"),
                others = listOf(fleet),
                socksPort = 9050,
            )
        )
    }

    @Test
    fun `aucun autre réplica pour router - non applicable`() {
        // Le pair est le SEUL réplica connu : le routeur serait le pair
        // lui-même — LE chemin mort par définition (miroir relayHintFor).
        assertFalse(
            LinkFallback.applicable(
                peer1,
                discovered = listOf(peer1Port),
                others = emptyList(),
                socksPort = 9050,
            )
        )
    }

    @Test
    fun `tor pas prêt - non applicable`() {
        assertFalse(
            LinkFallback.applicable(
                peer1,
                discovered = listOf(peer1Port),
                others = listOf(fleet),
                socksPort = 0,
            )
        )
        assertFalse(
            LinkFallback.applicable(
                peer1,
                discovered = listOf(peer1Port),
                others = listOf(fleet),
                socksPort = -1,
            )
        )
    }

    @Test
    fun `normalisation hôte - port et suffixe ignorés`() {
        // « onion », « onion:80 » et « ONION.onion:80 » désignent le même hôte.
        assertEquals(
            LinkFallback.hostKey(peer1),
            LinkFallback.hostKey(peer1Port)
        )
        // Majuscules : la base SANS suffixe, puis «.ONION:80» — pas de double
        // suffixe (peer1 contient déjà « .onion », l'uppercase le dupliquerait).
        val hostUp = peer1.removeSuffix(".onion").uppercase() + ".ONION:80"
        assertEquals(
            LinkFallback.hostKey(peer1),
            LinkFallback.hostKey(hostUp)
        )
        assertEquals(56, LinkFallback.hostKey(peer1Port).length)
    }

    @Test
    fun `découverte avec ou sans port - le pair est reconnu des deux façons`() {
        assertTrue(
            LinkFallback.applicable(
                peer1, // en échec, sans port
                discovered = listOf(peer1Port), // annoncé AVEC port
                others = listOf(fleet),
                socksPort = 9050,
            )
        )
        assertTrue(
            LinkFallback.applicable(
                peer1Port, // en échec, avec port
                discovered = listOf(peer1), // annoncé sans port
                others = listOf(fleet),
                socksPort = 9050,
            )
        )
    }
}
