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
                    // v0.2.39 — le faux control port VALIDE HSFETCH comme le vrai
                    // tor 0.4.9.14 (handle_control_hsfetch → hs_address_is_valid →
                    // hs_parse_address_no_log : strlen == 56 base32 EXACTEMENT,
                    // SINON « 513 Invalid argument »). v0.2.37-v0.2.38 envoyaient
                    // « <56>.onion » (62 car.) → 513 sur TOUT le terrain.
                    line.startsWith("HSFETCH") -> {
                        val addr = line.removePrefix("HSFETCH ").trim()
                        if (Regex("^[a-z2-7]{56}$").matches(addr)) {
                            writer.write("250 OK\r\n")
                        } else {
                            writer.write("513 Invalid argument \"$addr\"\r\n")
                        }
                    }
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
     * v0.2.39 — RÉGRESSION « HSFETCH rejeté 513 » (cause racine « toujours
     * SOCKS server failure + décrochage impossible » MALGRÉ le correctif
     * HSFETCH v0.2.37, logs terrain 22/10 20:51 : téléphones ET console
     * affichaient « erreur Tor 513 — Invalid argument » à CHAQUE HSFETCH).
     *
     * Le control-spec impose HSAddress = 56*Base32Character — l'adresse NUÈ
     * SANS suffixe « .onion » (grammaire : "HSFETCH" SP HSAddress ; exemple
     * officiel : « HSFETCH vww6ybal…pnpyyd » sans suffixe). Le source tor
     * 0.4.9.14 confirme : handle_control_hsfetch → hs_address_is_valid →
     * hs_parse_address_no_log → strlen(address) != HS_SERVICE_ADDR_LEN_BASE32
     * (= 56) → « Invalid length » → 513 — la récupération n'a donc JAMAIS
     * opéré sur le terrain et les descripteurs périmés sont restés en cache.
     *
     * CONTRAT (vérifié par le faux control port ci-dessus qui rejette en 513
     * toute adresse non conforme, comme le vrai tor) : hsfetch() doit envoyer
     * exactement « HSFETCH <56 base32> » et recevoir 250 OK — un suffixe
     * « .onion » ressusciterait le 513 et re-casserait appels + réplicas.
     */
    @Test
    fun `HSFETCH envoie l adresse nue de 56 caracteres exigee par le control-spec`() {
        val onion56 = "vxb5tsk44ikklazo2spqpng33w4wjfwewcylnw4kiheit25u6ive7zyd"
        TorControlClient(port()).use { c ->
            // Lève IllegalStateException si le (faux) tor répond 513 —
            // c'est exactement ce que le vrai tor faisait à v0.2.37/v0.2.38.
            c.hsfetch(onion56)
        }
        assertTrue(
            "HSFETCH doit être « HSFETCH <56 base32> » SANS suffixe .onion " +
                "(spec: HSAddress = 56*Base32Character) — reçu : ${received.joinToString(" | ")}",
            received.any { it == "HSFETCH $onion56" }
        )
        assertTrue(
            "Le suffixe .onion est INTERDIT par le control-spec (tor répond 513 — " +
                "régression terrain v0.2.37) — reçu : ${received.joinToString(" | ")}",
            received.none { it == "HSFETCH $onion56.onion" }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `HSFETCH refuse une adresse qui n a pas 56 caracteres`() {
        TorControlClient(port()).use { c ->
            c.hsfetch("trop-court")
        }
    }
}
