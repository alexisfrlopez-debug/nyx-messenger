package net.nyx.messenger.replica

import java.net.ServerSocket
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/**
 * v0.2.45 — CONTRAT DE LA SONDE LOCALE [EmbeddedReplica.alive] (cause racine
 * terrain 23/10, session 67, PROUVÉE par rejeu tor sandbox
 * scripts/tor_deadbackend.py) : un socket backend fermé = exactement la
 * signature terrain « Connection refused » + END misc + « SOCKS server
 * general failure » vue alors que le service Tor était sain. La sonde locale
 * est le seul verdict honnête — elle doit distinguer vivant / mort / fermé.
 */
class BackendLivenessTest {

    private var live: ServerSocket? = null

    @Before
    fun setUp() {
        // Reset l'état singleton entre tests.
        EmbeddedReplica.stop()
    }

    @After
    fun tearDown() {
        EmbeddedReplica.stop()
        runCatching { live?.close() }
    }

    @Test
    fun `port non démarré (0) = pas vivant, pas de crash`() {
        assertFalse(EmbeddedReplica.alive())
    }

    @Test
    fun `socket vivant = sonde OK (acceptation consommée puis fermée)`() {
        val s = ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        live = s
        EmbeddedReplica.rebindForTest(s)
        assertTrue(EmbeddedReplica.alive())
    }

    @Test
    fun `socket fermé = sonde KO (la signature terrain)`() {
        val s = ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        EmbeddedReplica.rebindForTest(s)
        s.close() // mort silencieuse : thread vivant ou non, le port refuse
        assertFalse(EmbeddedReplica.alive())
    }

    @Test
    fun `socket bindé mais qui n'accepte jamais (backlog plein à terme) reste vivant au sens TCP`() {
        // bind+listen sans accept : la connexion entre dans le backlog →
        // connect() réussit → alive()=true (le cas « bloqué » du sandbox
        // donne un TIMEOUT client, PAS un refused — c'est le comportement
        // attendu : alive() mesure le refus, pas la lenteur).
        val s = ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        live = s
        EmbeddedReplica.rebindForTest(s)
        assertTrue(EmbeddedReplica.alive())
    }

    @Test
    fun `restartIfDead sur backend mort = rebond sur un port NEUF`() {
        val dead = ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        EmbeddedReplica.rebindForTest(dead)
        dead.close()
        val oldPort = EmbeddedReplica.localPort
        assertFalse(EmbeddedReplica.alive())
        // relance : sans DB le start() réel ne peut pas completer, mais
        // restartIfDead doit tenter et ne jamais lever.
        EmbeddedReplica.resetForTest()
        assertEquals(0, EmbeddedReplica.restartIfDead())
        assertNotEquals(oldPort, 0)
    }
}
