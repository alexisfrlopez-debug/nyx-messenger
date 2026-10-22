package net.nyx.messenger.tor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * v0.2.34 — RÉGRESSION « appels qui ne décrochent pas » : la cause racine
 * (terrain v0.2.33, auto-sondes KO dès 15 s, « No more HSDir available to
 * query » en boucle) était l'ADD_ONION sans flag Detach : tor DÉTRUIT le
 * service éphémère dès la fermeture de la connexion contrôle qui l'a créé
 * (spéc. control-port ; le serveur Go le sait — bug v0.2.0 n°2 — et garde
 * sa connexion ouverte via Watch()). L'app fermait la sienne immédiatement :
 * l'onion du téléphone n'a donc JAMAIS été joignable → média d'appel
 * impossible + réplica embarqué invisible depuis la console.
 *
 * Ce test fixe le CONTRAT : la commande ADD_ONION DOIT contenir
 * Flags=Detach,DiscardPK et les deux ports (80 + média). Sans lui, une
 * « simplification » du flag casserait à nouveau tous les appels.
 */
class TorControlClientTest {

    /** Lignes reçues par le faux control port (une par commande). */
    private val received = ConcurrentLinkedQueue<String>()

    private var server: ServerSocket? = null
    private var thread: Thread? = null

    @Before
    fun startFakeControlPort() {
        val ss = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        server = ss
        thread = Thread {
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (e: java.io.IOException) {
                    return@Thread
                }
                handle(client)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            val writer = java.io.OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
            while (true) {
                val line = reader.readLine() ?: return
                received.add(line)
                when {
                    line.startsWith("AUTHENTICATE") -> writer.write("250 OK\r\n")
                    line.startsWith("ADD_ONION") -> {
                        // Réponse multi-lignes : 250-ServiceID=… puis 250 OK.
                        writer.write("250-ServiceID=aaaaaaaaaaaaaaaaaaaaaaaa\r\n")
                        writer.write("250 OK\r\n")
                    }
                    line.startsWith("DEL_ONION") -> writer.write("250 OK\r\n")
                    else -> writer.write("250 OK\r\n")
                }
                writer.flush()
            }
        }
    }

    @After
    fun stopFakeControlPort() {
        runCatching { server?.close() }
        runCatching { thread?.interrupt() }
    }

    private fun port(): Int = server!!.localPort

    /** Clé 64 octets bidon (graine + pub) — le client dérive SHA-512(graine). */
    private val key64 = ByteArray(64) { (it + 1).toByte() }

    @Test
    fun `ADD_ONION exige Flags Detach sinon le service meurt a la fermeture de la connexion`() {
        TorControlClient(port()).use { c ->
            val sid = c.publishOnionEd25519V3(key64, targetPort = 34747, mediaPort = 33739)
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaa", sid)
        }
        val addOnion = received.first { it.startsWith("ADD_ONION") }
        assertTrue(
            "ADD_ONION sans Flags=Detach : tor détruirait le service à la fermeture " +
                "de la connexion contrôle (cause racine appels v0.2.33) — reçu : $addOnion",
            addOnion.contains("Flags=Detach,DiscardPK")
        )
    }

    @Test
    fun `ADD_ONION publie le port 80 et le port media`() {
        TorControlClient(port()).use { c ->
            c.publishOnionEd25519V3(key64, targetPort = 34747, mediaPort = 33739)
        }
        val addOnion = received.first { it.startsWith("ADD_ONION") }
        assertTrue("port 80 manquant : $addOnion", addOnion.contains("Port=80,127.0.0.1:34747"))
        assertTrue(
            "port média (47471) manquant : $addOnion",
            addOnion.contains("Port=${net.nyx.messenger.p2p.P2pProtocol.MEDIA_ONION_PORT},127.0.0.1:33739")
        )
    }

    @Test
    fun `DEL_ONION reste utilisable depuis une nouvelle connexion (service Detach)`() {
        // Publication puis fermeture de la connexion (le .use {} ci-dessous).
        TorControlClient(port()).use { c ->
            c.publishOnionEd25519V3(key64, targetPort = 34747, mediaPort = 33739)
        }
        // Arrêt propre depuis une NOUVELLE connexion : DEL_ONION doit partir.
        TorControlClient(port()).use { c ->
            c.unpublish("aaaaaaaaaaaaaaaaaaaaaaaa")
        }
        assertTrue(received.any { it == "DEL_ONION aaaaaaaaaaaaaaaaaaaaaaaa" })
    }

    /**
     * v0.2.37 — RÉGRESSION « SOCKS server general failure » en boucle : la
     * récupération des appels repose sur HSFETCH (force la re-demande du
     * descripteur au HSDirs EN CONTOURNANT le cache client — cause racine
     * terrain v0.2.36 : descripteur périmé à points d'introduction morts
     * après un redémarrage de l'app du pair, gardé jusqu'à 24 h par tor).
     * CONTRAT : la commande doit être exactement « HSFETCH <onion56>.onion »
     * — une adresse incomplète (sans .onion) ou une autre commande serait
     * silencieusement ignorée par tor et la récupération ne servirait à rien.
     */
    @Test
    fun `HSFETCH demande le descripteur d une onion avec suffixe complet`() {
        val onion56 = "vxb5tsk44ikklazo2spqpng33w4wjfwewcylnw4kiheit25u6ive7zyd"
        TorControlClient(port()).use { c ->
            c.hsfetch(onion56)
        }
        assertTrue(
            "HSFETCH malformé — la récupération descripteur serait un no-op : " +
                received.joinToString(" | "),
            received.any { it == "HSFETCH $onion56.onion" }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `HSFETCH refuse une adresse qui n a pas 56 caracteres`() {
        TorControlClient(port()).use { c ->
            c.hsfetch("trop-court")
        }
    }
}
