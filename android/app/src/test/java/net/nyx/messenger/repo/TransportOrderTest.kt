package net.nyx.messenger.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.41 — Décisions de transport ADR-017 : fenêtre de dégradation (ordre
 * direct-d'abord) et garde anti-churn du redémarrage tor (le scénario exact
 * des logs terrain 23/10 01:06 y est rejoué : il ne doit PLUS redémarrer).
 */
class TransportOrderTest {

    private val now = 1_000_000_000L
    private val window = 3 * 60_000L

    // ---------- relayDegraded ----------

    @Test
    fun `jamais échoué - chemin journal sain`() {
        assertFalse(TransportOrder.relayDegraded(0L, now, window))
    }

    @Test
    fun `échec tout récent - dégradé (direct d'abord)`() {
        assertTrue(TransportOrder.relayDegraded(now - 10_000L, now, window))
    }

    @Test
    fun `échec à l'intérieur de la fenêtre - dégradé`() {
        assertTrue(TransportOrder.relayDegraded(now - window + 1, now, window))
    }

    @Test
    fun `échec au bord exact de la fenêtre - sain`() {
        assertFalse(TransportOrder.relayDegraded(now - window, now, window))
    }

    @Test
    fun `échec dépassé (3 min écoulées) - sain à nouveau`() {
        assertFalse(TransportOrder.relayDegraded(now - window - 1, now, window))
    }

    // ---------- shouldHealTor (garde anti-churn) ----------

    @Test
    fun `scénario logs terrain 23-10 01h06 - bootstrap récent + 3 échecs = PAS de redémarrage`() {
        // Terrain : bootstrap 01:04:36, heal déclenché 01:06:52 après TROIS
        // timeouts 25 s (Ready depuis ~2,3 min) → mort des points
        // d'introduction → « SOCKS server general failure ». Interdit désormais.
        assertFalse(
            TransportOrder.shouldHealTor(
                consecutiveTransportFails = 3,
                readyForMs = 2 * 60_000L + 16_000L, // 2 min 16 s
                msSinceLastHeal = Long.MAX_VALUE,
            )
        )
    }

    @Test
    fun `5 échecs mais démon Ready depuis 4 min - PAS de redémarrage (circuits en construction)`() {
        assertFalse(
            TransportOrder.shouldHealTor(
                consecutiveTransportFails = 5,
                readyForMs = 4 * 60_000L,
                msSinceLastHeal = Long.MAX_VALUE,
            )
        )
    }

    @Test
    fun `5 échecs, Ready depuis 12 min, cooldown écoulé - redémarrage AUTORISÉ`() {
        assertTrue(
            TransportOrder.shouldHealTor(
                consecutiveTransportFails = 5,
                readyForMs = 12 * 60_000L,
                msSinceLastHeal = 11 * 60_000L,
            )
        )
    }

    @Test
    fun `cooldown non écoulé - PAS de redémarrage (même coincé)`() {
        assertFalse(
            TransportOrder.shouldHealTor(
                consecutiveTransportFails = 9,
                readyForMs = 30 * 60_000L,
                msSinceLastHeal = 9 * 60_000L, // < cooldown 10 min
            )
        )
    }

    @Test
    fun `échecs insuffisants - PAS de redémarrage (même vieux démon)`() {
        assertFalse(
            TransportOrder.shouldHealTor(
                consecutiveTransportFails = 4,
                readyForMs = 60 * 60_000L,
                msSinceLastHeal = Long.MAX_VALUE,
            )
        )
    }
}
