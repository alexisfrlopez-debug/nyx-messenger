package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * v0.2.44 — P2pClient.relayTarget : normalisation de la cible relais média.
 *
 * CAUSE (bug attrapé avant build, session 65) : le registre/rendezvous
 * portent « xxx.onion:80 » (RelayClient laisse OkHttp parser l'URL) mais
 * connectViaRelay ouvre une socket BRUTE — InetSocketAddress.createUnresolved
 * exige un hôte NU : passer « xxx.onion:80 » échoue silencieusement (hôte
 * invalide) et le repli média n'aurait JAMAIS fonctionné sur le terrain.
 */
class RelayTargetTest {

    private val ONION = "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion"

    @Test
    fun `format registre onion deux-points 80`() {
        assertEquals(ONION to 80, P2pClient.relayTarget("$ONION:80"))
    }

    @Test
    fun `onion nu - port 80 par defaut`() {
        assertEquals(ONION to 80, P2pClient.relayTarget(ONION))
    }

    @Test
    fun `avec suffixe url - nettoyé`() {
        assertEquals(ONION to 80, P2pClient.relayTarget("http://$ONION:80/"))
    }

    @Test
    fun `port explicite non standard - conserve`() {
        assertEquals(ONION to 8080, P2pClient.relayTarget("$ONION:8080"))
    }

    @Test
    fun `casse et espaces - normalises`() {
        assertEquals(ONION to 80, P2pClient.relayTarget("  ${ONION.uppercase()}:80 "))
    }

    @Test
    fun `hote non onion - rejeté`() {
        try {
            P2pClient.relayTarget("example.com:80")
            fail("un hôte non .onion doit être rejeté (zéro trafic hors Tor)")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `port invalide - retombe sur 80`() {
        assertEquals(ONION to 80, P2pClient.relayTarget("$ONION:http"))
    }
}
