package net.nyx.messenger.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.47 — CONTRAT DU BIND LOOPBACK IPv4 (cause racine des sessions 41 → 68,
 * PROUVÉE par le source AOSP : `InetAddress.getLoopbackAddress()` retourne
 * **::1** sur Android — jamais 127.0.0.1 — alors que l'ADD_ONION publie
 * `Port=…,127.0.0.1:<port>` : tor a donc TOUJOURS reçu « Connection refused »
 * sur les backends des téléphones, y compris quand les tests JVM du bac à
 * sable passaient — le JVM desktop retourne 127.0.0.1, pas ::1).
 *
 * Tout écouteur mappé sur l'onion DOIT passer par [Loopback.serverSocket] :
 * ce test verrouille le contrat (bind IPv4 explicite + joignabilité 127.0.0.1
 * + NON-joignabilité ::1, qui reproduit le miroir exact du piège Android).
 */
class LoopbackBindTest {

    private var sock: ServerSocket? = null

    @After
    fun tearDown() {
        runCatching { sock?.close() }
    }

    @Test
    fun `l'adresse de bind est exactement 127-0-0-1 en IPv4`() {
        assertEquals("127.0.0.1", Loopback.bindAddress.hostAddress)
        assertTrue(
            "le bind doit être IPv4 (le contrat ADD_ONION cible 127.0.0.1)",
            Loopback.bindAddress is java.net.Inet4Address
        )
    }

    @Test
    fun `serverSocket joignable sur 127-0-0-1 - le contrat ADD_ONION`() {
        sock = Loopback.serverSocket(backlog = 16)
        val s = sock!!
        assertEquals("127.0.0.1", s.inetAddress.hostAddress)
        // Ce que tor fait pour chaque flux entrant : connect 127.0.0.1:port.
        Socket().use { c ->
            c.soTimeout = 1_500
            c.connect(InetSocketAddress("127.0.0.1", s.localPort), 1_500)
            assertTrue("la connexion IPv4 doit aboutir", c.isConnected)
        }
    }

    @Test
    fun `serverSocket NON joignable sur IPv6 - le piège Android à l'envers`() {
        sock = Loopback.serverSocket(backlog = 16)
        val port = sock!!.localPort
        // Miroir exact du terrain : si l'écouteur était bindé sur ::1 (ce que
        // getLoopbackAddress() fait sur Android), un client 127.0.0.1 serait
        // REFUSÉ. Ici on exige l'inverse : ::1 ne répond PAS, 127.0.0.1 oui.
        // (Les piles IPv4 et IPv6 peuvent coexister sur le même numéro de
        // port — le refus de ::1 prouve que le bind n'est PAS dual-stack.)
        var joinedV6 = true
        try {
            Socket().use { c ->
                c.soTimeout = 800
                c.connect(InetSocketAddress(InetAddress.getByName("::1"), port), 800)
            }
        } catch (_: Exception) {
            joinedV6 = false
        }
        assertFalse(
            "rien ne doit écouter sur [::1]:$port — un écouteur IPv6 serait le " +
                "piège Android (getLoopbackAddress) rouvert",
            joinedV6
        )
        // Et le même port répond bien en IPv4 :
        Socket().use { c ->
            c.soTimeout = 1_500
            c.connect(InetSocketAddress("127.0.0.1", port), 1_500)
            assertTrue(c.isConnected)
        }
    }

    @Test
    fun `deux serverSocket successifs = ports distincts, bind IPv4 stable`() {
        val a = Loopback.serverSocket()
        sock = a
        val b = Loopback.serverSocket()
        try {
            assertTrue("ports éphémères distincts attendus", a.localPort != b.localPort)
            assertEquals("127.0.0.1", a.inetAddress.hostAddress)
            assertEquals("127.0.0.1", b.inetAddress.hostAddress)
        } finally {
            runCatching { b.close() }
        }
    }
}
