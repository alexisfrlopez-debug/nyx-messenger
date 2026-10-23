package net.nyx.messenger.p2p

import net.nyx.messenger.net.WireEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v0.2.41 — ÉCHANGE COMPLET du dépôt direct d'événements (ADR-017) contre le
 * VRAI écouteur [P2pServer] sur socket loopback réelle : HELLO « events »
 * authentifié → trames F_EVENT → demi-fermeture → ACK du nombre d'ingérés.
 *
 * L'émetteur de test reproduit EXACTEMENT le protocole de P2pClient.pushEvents
 * (au Socket Tor près — en production la connexion passe par le SOCKS de tor
 * vers l'onion du pair ; ici loopback direct, le filaire est identique).
 */
class P2pEventsExchangeTest {

    private val author = "a".repeat(56) + ".onion"
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }

    private fun ev(seq: Long, to: String = "b".repeat(56) + ".onion") = WireEvent(
        type = "msg", author = author, seq = seq, ts = 1_728_000_000L, ttl = 0,
        to = to, body = "Q09SUFM=", sig = "c2ln"
    )

    /** Écrit le protocole émetteur complet et retourne l'ACK (ou null). */
    private fun exchange(
        events: List<WireEvent>,
        isAllowed: Boolean = true,
        sinkResult: Int? = null,
    ): Int? {
        P2pServer.isAllowed = { isAllowed }
        P2pServer.eventSink = { a, evs ->
            assertEquals(author, a)
            assertEquals(events.size, evs.size)
            sinkResult ?: evs.size
        }
        // Vérificateur HELLO permissif (la vraie vérification ed25519 est
        // couverte par les tests crypto ; ici on teste le routage events).
        P2pVerifier.get = { _, _, _ -> true }
        P2pServer.start()
        assertTrue(P2pServer.localPort > 0)

        val latch = CountDownLatch(1)
        var ack: Int? = null
        val t = Thread({
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress("127.0.0.1", P2pServer.localPort), 5_000)
                    sock.soTimeout = 10_000
                    val out: OutputStream = sock.getOutputStream()
                    val sig = "TESTSIG"
                    P2pProtocol.write(
                        out, P2pProtocol.F_HELLO,
                        P2pProtocol.encodeHello(
                            P2pProtocol.Hello("events", author, "alias", "", "", events.size, ""), sig
                        )
                    )
                    for (e in events) {
                        P2pProtocol.write(
                            out, P2pProtocol.F_EVENT,
                            json.encodeToString(WireEvent.serializer(), e).toByteArray(Charsets.UTF_8)
                        )
                    }
                    sock.shutdownOutput() // demi-fermeture — le serveur lit l'ACK
                    val frame = P2pProtocol.read(sock.getInputStream())
                    ack = frame?.takeIf { it.type == P2pProtocol.F_ACK && it.payload.size >= 4 }
                        ?.let { f ->
                            ((f.payload[0].toInt() and 0xFF) shl 24) or
                                ((f.payload[1].toInt() and 0xFF) shl 16) or
                                ((f.payload[2].toInt() and 0xFF) shl 8) or
                                (f.payload[3].toInt() and 0xFF)
                        }
                }
            } finally {
                latch.countDown() // même si le serveur ferme pendant l'écriture
            }
        }, "test-events-sender")
        t.isDaemon = true
        t.start()
        assertTrue("échange non terminé (timeout)", latch.await(15, TimeUnit.SECONDS))
        return ack
    }

    @Test
    fun `dépôt 1 événement - ACK 1 (chemin nominal message texte)`() {
        assertEquals(1, exchange(listOf(ev(1))))
    }

    @Test
    fun `dépôt 3 événements - ACK 3 (lot signalisation + message)`() {
        assertEquals(3, exchange(listOf(ev(1), ev(2), ev(3))))
    }

    @Test
    fun `lot vide - ACK 0 immédiat`() {
        assertEquals(0, exchange(emptyList()))
    }

    @Test
    fun `sink refuse tout - ACK 0 (l'émetteur retombera sur le journal)`() {
        assertEquals(0, exchange(listOf(ev(1)), isAllowed = true, sinkResult = 0))
    }

    @Test
    fun `contact non accepté - connexion fermée SANS ACK`() {
        // Un pair v0.2.18-0.2.40 (ou un inconnu) : pas d'ACK — l'émetteur
        // doit considérer le direct ÉCHOUÉ et repasser par le journal.
        assertNull(exchange(listOf(ev(1)), isAllowed = false))
    }
}
