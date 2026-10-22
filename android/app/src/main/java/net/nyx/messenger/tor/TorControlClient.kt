package net.nyx.messenger.tor

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Locale

/**
 * Client minimal du control port Tor — miroir Kotlin de
 * `server/internal/tor/control.go` (commandes AUTHENTICATE, GETINFO, ADD_ONION,
 * DEL_ONION). Volontairement sans dépendance : le protocole est simple et stable.
 *
 * Authentification par cookie (CookieAuthentication 1 dans le torrc généré).
 */
class TorControlClient(private val controlPort: Int) : AutoCloseable {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private fun connect() {
        if (socket != null && socket!!.isConnected && !socket!!.isClosed) return
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", controlPort), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII))
    }

    /** Envoie une commande et lit le bloc de réponse multi-lignes. */
    private fun cmd(line: String): ControlResponse {
        // v0.2.16 — CEINTURE DE BÉLIERS sur les IOException du control port :
        // à l'arrêt du démon (TERM/SIGKILL, réveil nocturne de l'OS), le socket
        // peut être fermé PENDANT un read bloquant — libcore lève alors
        // InterruptedIOException « read interrupted by close() on another thread ».
        // Historique : traverser une coroutine en état Cancelling avec une
        // IOException brute produit un FATAL EXCEPTION même en présence d'un
        // CoroutineExceptionHandler (constaté en v0.2.9/v0.2.10 — drain depuis
        // converti en thread démon). Ici TOUTE IOException est convertie en
        // IllegalStateException (métier) : jamais d'exception réseau brute
        // ne remonte du client de contrôle.
        try {
            connect()
            writer!!.write(line)
            writer!!.write("\r\n")
            writer!!.flush()
        } catch (e: java.io.IOException) {
            close()
            throw IllegalStateException("control port : écriture impossible (${e.javaClass.simpleName})")
        }
        val lines = ArrayList<String>()
        val values = HashMap<String, String>()
        var code = 0
        while (true) {
            val raw = try {
                reader!!.readLine()
            } catch (e: java.io.IOException) {
                close()
                throw IllegalStateException("control port : lecture impossible (${e.javaClass.simpleName})")
            } ?: error("control port : connexion fermée (réponse à « $line »)")
            if (raw.length < 4) error("control port : ligne invalide « $raw »")
            code = raw.substring(0, 3).toIntOrNull() ?: error("control port : code invalide « $raw »")
            val rest = raw.substring(4)
            lines.add(rest)
            val eq = rest.indexOf('=')
            if (eq > 0) values[rest.substring(0, eq)] = rest.substring(eq + 1)
            if (raw[3] == ' ') break // ' ' = fin de bloc, '-' = continuation
        }
        if (code >= 400) error("control port : erreur Tor $code — ${lines.joinToString(" / ")}")
        return ControlResponse(code, lines, values)
    }

    /** AUTHENTICATE avec le cookie généré par tor. */
    fun authenticate(cookieFile: File) {
        val hex = cookieFile.readBytes().joinToString("") { "%02x".format(it) }
        cmd("AUTHENTICATE $hex")
    }

    /** GETINFO status/bootstrap-phase → « Bootstrapped 65% (…) ». */
    fun bootstrapPhase(): String = cmd("GETINFO status/bootstrap-phase")
        .values["status/bootstrap-phase"].orEmpty()

    /**
     * Publie le service onion v3 du client à partir de sa clé privée ed25519
     * (64 octets : graine + clé publique — même encodage que le serveur Go).
     * Port 80 côté onion → 127.0.0.1:[targetPort]. Retourne le ServiceID.
     *
     * v0.2.18 : SECOND port média [mediaPort] (canal direct P2P — appels +
     * fichiers téléphone-à-téléphone, ADR-015) : Port=47471,127.0.0.1:<port>.
     * Passer 0 pour publier le port 80 seul (comportement v0.2.17).
     *
     * CONTRAT TOR (bug v0.2.0 n°1, prouvé côté serveur — blocs A/P2) : tor lit
     * les 32 PREMIERS octets du blob comme un SCALAIRE BRUT (aucune expansion
     * sha512 de sa part) — lui envoyer le format libsodium/Go (graine||pub)
     * publie une adresse FANTÔME ≠ adresse calculée. Il faut dériver le scalaire
     * RFC 8032 : SHA512(graine)[0..32] puis clamp (h[0]&=248 ; h[31]&=127 ; h[31]|=64),
     * envoyé sur les 64 octets du digeste (miroir exact d'identity.OnionScalarB64).
     */
    fun publishOnionEd25519V3(privateKey64: ByteArray, targetPort: Int, mediaPort: Int = 0): String {
        require(privateKey64.size == 64) { "clé ed25519 : ${privateKey64.size} octets ≠ 64" }
        val h = java.security.MessageDigest.getInstance("SHA-512")
            .digest(privateKey64.copyOfRange(0, 32))       // graine = [0..32)
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = ((h[31].toInt() and 127) or 64).toByte()
        val keyB64 = Base64.getEncoder().encodeToString(h)
        val ports = StringBuilder("Port=80,127.0.0.1:$targetPort")
        if (mediaPort > 0) {
            ports.append(" Port=${net.nyx.messenger.p2p.P2pProtocol.MEDIA_ONION_PORT},127.0.0.1:$mediaPort")
        }
        // v0.2.34 — Flags=Detach (CAUSE RACINE « appels qui ne décrochent pas » +
        // « réplicas embarqués invisibles console », prouvée par les auto-sondes
        // v0.2.33) : un service ADD_ONION est ÉPHÉMÈRE — tor le DÉTRUIT dès la
        // fermeture de la connexion contrôle qui l'a créé (spéc. control-port ;
        // bug v0.2.0 n°2 documenté côté serveur Go, qui garde sa connexion ouverte
        // via Watch() — d'où des nœuds joignables mais des téléphones muets).
        // L'app fermait sa connexion juste après l'ADD_ONION : le service mourrait
        // à la naissance, descripteur jamais propagé (auto-sondes KO dès 15 s,
        // « No more HSDir available to query » en boucle) → média d'appel
        // impossible, réplica embarqué injoignable depuis la console. Detach :
        // le service survit à la fermeture ; DEL_ONION reste fonctionnel depuis
        // n'importe quelle connexion authentifiée (arrêt propre / republication).
        val resp = cmd("ADD_ONION ED25519-V3:$keyB64 Flags=Detach,DiscardPK $ports")
        return resp.values["ServiceID"] ?: error("ADD_ONION : ServiceID absent (${resp.lines})")
    }

    /** Retire un service onion publié (arrêt propre). */
    fun unpublish(serviceId: String) {
        runCatching { cmd("DEL_ONION $serviceId") }
    }

    /**
     * v0.2.37 — HSFETCH : force la RE-DEMANDE du descripteur d'un service
     * onion auprès des HSDirs responsables, EN CONTOURNANT le cache client.
     *
     * CAUSE RACINE terrain v0.2.36 (logs 22/10, source tor 0.4.9.14 lu) :
     * le cache client garde un descripteur v3 jusqu'au début de la PROCHAINE
     * période temporelle (hs_cache.c : expiration_ts =
     * hs_get_start_time_of_next_time_period — jusqu'à ~24 h !). Un descripteur
     * dont les points d'introduction sont MORTS (l'app du pair a redémarré :
     * nouvelle instance tor = nouveaux points d'intro) reste donc utilisé
     * jusqu'à ce que TOUS ses intro points soient marqués en échec — et même
     * alors, le refetch peut retomber sur un HSDir qui sert encore l'ANCIENNE
     * révision (8 HSDirs responsables, propagation non atomique). Symptôme :
     * « SOCKS server general failure » (échec d'INTRODUCE) immédiat et répété.
     *
     * HSFETCH (control-spec) appelle hs_client_launch_v3_desc_fetch SANS le
     * garde-fou « on a déjà un descripteur utilisable » (can_client_refetch_desc)
     * → le descripteur fraîchement publié par le pair est récupéré et REMPLACE
     * l'entrée de cache (révision plus haute = remplacement, cache_store_as_client).
     *
     * v0.2.39 — CORRECTIF CRITIQUE DE SYNTAXE (cause racine « toujours SOCKS
     * server failure » malgré v0.2.37, logs terrain 22/10 20:51) : le
     * control-spec exige HSAddress = 56*Base32Character — l'adresse NUÈ, SANS
     * suffixe « .onion ». La grammaire : "HSFETCH" SP (HSAddress / …).
     * Vérifié dans le source tor 0.4.9.14 : handle_control_hsfetch →
     * hs_address_is_valid → hs_parse_address_no_log → strlen(address) !=
     * HS_SERVICE_ADDR_LEN_BASE32 (= 56) → « Invalid length » → 513. v0.2.37
     * envoyait « HSFETCH <56>.onion » (62 caractères) → rejet 513 SYSTÉMATIQUE
     * sur tout le terrain (téléphones + console) → la récupération n'a JAMAIS
     * opéré, les descripteurs périmés sont restés en cache → appels toujours
     * impossibles à décrocher + réplica « SOCKS injoignable ».
     */
    fun hsfetch(onion56: String) {
        require(onion56.length == 56) { "adresse onion invalide (${onion56.length} ≠ 56)" }
        cmd("HSFETCH $onion56")
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    data class ControlResponse(val code: Int, val lines: List<String>, val values: Map<String, String>)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 15_000

        /** « Bootstrapped 65% (Loading relay descriptors…) » → 65, ou null si non parsable. */
        fun parsePercent(bootstrapPhase: String): Int? =
            Regex("Bootstrapped (\\d+)%").find(bootstrapPhase)?.groupValues?.get(1)?.toIntOrNull()

        /** True quand le bootstrap est terminé. */
        fun isDone(bootstrapPhase: String): Boolean =
            bootstrapPhase.lowercase(Locale.ROOT).contains("done")
    }
}
