package net.nyx.messenger.desktop

import net.nyx.messenger.tor.TorControlClient
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tor embarqué desktop (ADR-007/ADR-019) : tor.exe de l'expert bundle lancé
 * comme processus enfant, torrc généré (portage de NyxTorConfig — mêmes
 * options : SocksPort/ControlPort sondés, cookie auth, DataDirectory privé,
 * ConfluxEnabled 0, Log info fichier, SafeLogging 1, __OwningControllerProcess).
 *
 * Cycle : [start] → écrit le torrc → lance le démon → attend le control port
 * (cookie) → AUTHENTICATE → observe le bootstrap (GETINFO) jusqu'à « done » →
 * [publishOnion] (ADD_ONION Detach, port 80 → réplique embarquée).
 * [shutdownNow] : DEL_ONION puis destruction PROPRE du processus (arrêt
 * bloquant ≤ 5 s, destroy en dernier recours).
 *
 * v0.3.3 (session 73 — retour terrain « l'appli windows ne se connecte pas à
 * tor ni aux replicas ») : TROIS aveuglements corrigés, miroir des leçons
 * Android v0.2.9/v0.2.33 :
 *  1. **stdout/stderr du démon CAPTURÉS** (queue bornée) — avant, le drain
 *     les JETAIT : un tor.exe mort immédiatement (dll manquante, antivirus,
 *     DataDirectory verrouillé par un tor orphelin…) produisait l'erreur
 *     générique « le démon tor est mort au démarrage (voir tor/tor.log) »
 *     sans AUCUNE explication — et tor.log peut ne pas exister (mort avant
 *     l'ouverture du Log). La queue alimente les messages d'erreur (bannière)
 *     ET l'export de logs ;
 *  2. **killLeftoverTor AVANT tout nouveau démon** (portage ADR-007 v0.2.9) :
 *     un tor.exe orphelin d'une session tuée (crash v0.3.0, Task Manager sur
 *     javaw) garde le verrou du DataDirectory → tout nouveau démon meurt
 *     « Tor is already running » → connexion impossible JUSQU'À UN REBOOT.
 *     Ciblage STRICT par chemin d'exécutable (jamais le tor du navigateur
 *     Tor Browser de l'utilisateur) ;
 *  3. **progression du bootstrap journalisée** (nyx-tor) — un Tor bloqué à
 *     15 % pendant 3 min est maintenant lisible dans l'export (avant : seule
 *     la bannière UI voyait le pourcentage, jamais le journal).
 *
 * v0.3.4 (session 74 — PREUVE PAR LES LOGS TERRAIN du 27/10 15:28, export
 * via le bouton d'envoi de logs) : COURSE DU PORT DE CONTRÔLE — la cause
 * racine RÉELLE de « tor ne se connecte pas » :
 *  - l'attente du cookie `control.authcookie` passait INSTANTANÉMENT car le
 *    fichier PERSISTE d'une session précédente (tor ne le supprime jamais) ;
 *  - le connect TCP suivant frappait 127.0.0.1:9051 alors que tor.exe —
 *    lancé 16 à 62 ms plus tôt — n'avait pas encore ouvert son listener :
 *    « control port : écriture impossible (ConnectException) » → backend
 *    FAILED, ALORS QUE le démon finissait son bootstrap ~14 s plus tard
 *    (tor.log du terrain : circuits construits, garde primaire en succès
 *    25/25) — un Tor parfaitement vivant, orphelin de son contrôleur ;
 *  - le retry tuait ensuite CE démon sain via killLeftoverTor.
 * Correctif : le cookie PÉRIMÉ est supprimé avant le lancement (seul un
 * cookie RÉGÉNÉRÉ par CE démon fait foi) ET l'attente porte sur un connect
 * TCP RÉELLEMENT ACCEPTÉ par le port de contrôle (poll 250 ms, ≤ 60 s).
 */
class DesktopTor(private val bin: File?) {

    data class Ports(val socks: Int, val control: Int, val onionLocal: Int)

    var ports: Ports? = null
        private set

    private var process: Process? = null
    @Volatile var serviceId: String? = null
        private set

    val socksPort: Int get() = ports?.socks ?: 0
    val controlPort: Int get() = ports?.control ?: 0

    private val stopping = AtomicBoolean(false)

    /** Tor est-il bootstrappé (progression publiée via [onBootstrap]) ? */
    @Volatile var bootstrapped: Boolean = false
        private set

    var onBootstrap: ((percent: Int?) -> Unit)? = null

    /** v0.3.3 — queue de la sortie console du démon ( erreur dll/verrou…). */
    private val stdoutRing = ArrayDeque<String>()
    private val STDOUT_MAX_LINES = 400

    private fun captureOut(line: String) {
        synchronized(stdoutRing) {
            stdoutRing.addLast(line)
            while (stdoutRing.size > STDOUT_MAX_LINES) stdoutRing.removeFirst()
        }
    }

    /** v0.3.3 — queue de la console du démon (première cause lisible d'une mort immédiate). */
    fun stdoutTail(n: Int = 25): String = synchronized(stdoutRing) {
        stdoutRing.takeLast(n.coerceAtMost(stdoutRing.size)).joinToString("\n")
    }

    /** v0.3.3 — queue de tor.log (progression bootstrap réelle côté démon). */
    fun torLogTail(maxChars: Int = 8_000): String = runCatching {
        val f = File(DesktopPaths.torDir, "tor.log")
        if (!f.isFile) return ""
        val bytes = f.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        if (text.length <= maxChars) text else text.takeLast(maxChars)
    }.getOrDefault("")

    fun writeTorrc(appPid: Long): File {
        val p = ports ?: Ports(freePort(9050), freePort(9051), freePort(8090)).also { ports = it }
        val dataDir = File(DesktopPaths.torDir, "data").apply { mkdirs() }
        val torrc = File(DesktopPaths.torDir, "torrc")
        val log = File(DesktopPaths.torDir, "tor.log")
        torrc.writeText(
            buildString {
                appendLine("# Nyx desktop — torrc généré (ADR-019). Ne pas éditer : régénéré à chaque démarrage.")
                appendLine("SocksPort 127.0.0.1:${p.socks} IsolateDestAddr")
                appendLine("ControlPort 127.0.0.1:${p.control}")
                appendLine("CookieAuthentication 1")
                appendLine("CookieAuthFile ${File(dataDir, "control.authcookie").absolutePath}")
                appendLine("DataDirectory ${dataDir.absolutePath}")
                // (miroir v0.2.44 Android) Conflux coupé : churn de guards sans
                // perte (zéro trafic exit — tout est onion↔onion).
                appendLine("ConfluxEnabled 0")
                // v0.3.3 — GeoIP de l'expert bundle si présent à côté du binaire
                // (chargés par défaut par tor-android ; ici optionnels et
                // STRICTEMENT best-effort : absents = avertissement tor sans gravité).
                bin?.parentFile?.let { dir ->
                    File(dir, "geoip").takeIf { it.isFile }?.let {
                        appendLine("GeoIPFile ${it.absolutePath}")
                    }
                    File(dir, "geoip6").takeIf { it.isFile }?.let {
                        appendLine("GeoIPv6File ${it.absolutePath}")
                    }
                }
                appendLine("Log info file ${log.absolutePath}")
                appendLine("__OwningControllerProcess $appPid")
                appendLine("SafeLogging 1")
            }
        )
        return torrc
    }

    /**
     * Lance le démon et attend le bootstrap. Retourne les ports résolus.
     * @param identityPriv64 clé ed25519 (64 o seed||pk) pour la publication
     * @param backendLocalPort port local du réplica embarqué (port 80 onion)
     * @param mediaLocalPort v0.3.6 — port local de l'écouteur média (2ᵉ directive
     * Port= de ADD_ONION : 47471 → appels P2P directs, ADR-015) ; 0 = port 80 seul.
     */
    fun startAndWait(identityPriv64: ByteArray, backendLocalPort: Int, mediaLocalPort: Int = 0): Ports {
        val binFile = bin ?: error(
            "binaire tor introuvable — tor.exe a été supprimé ou bloqué ? " +
                "(antivirus : ajoutez le dossier Nyx aux exclusions et réinstallez) " +
                "ou installez Tor système à côté de l'application"
        )
        stopping.set(false)
        bootstrapped = false
        synchronized(stdoutRing) { stdoutRing.clear() }

        // 0. v0.3.3 — tor orphelin d'une session précédente ? Il verrouille le
        // DataDirectory (miroir killLeftoverTor Android v0.2.9) : on le tue
        // D'ABORD, sinon le nouveau démon meurt « Tor is already running ».
        killLeftoverTor(binFile)

        // 1. Ports libres (préférés d'abord — cohabitation avec un Tor système).
        val p = Ports(freePort(9050), freePort(9051), freePort(8090))
        ports = p

        // 2. torrc + démon
        // v0.3.4 — le COOKIE DE LA SESSION PRÉCÉDENTE est supprimé AVANT le
        // lancement : tor ne l'efface jamais lui-même, et « cookie.isFile »
        // passait donc INSTANTANÉMENT (course du port de contrôle, cf. doc
        // classe — logs terrain 27/10 15:28 : ConnectException à +62 ms alors
        // que le listener n'ouvrait que 500 ms à 2 s plus tard). Seul un
        // cookie RÉGÉNÉRÉ par CE démon fait désormais foi.
        purgeStaleCookie()
        val torrc = writeTorrc(ProcessHandle.current().pid())
        DesktopLog.i(
            "nyx-tor",
            "démarrage du démon : ${binFile.absolutePath} (socks ${p.socks}, control ${p.control})"
        )
        val pb = ProcessBuilder(binFile.absolutePath, "-f", torrc.absolutePath)
            .redirectErrorStream(true)
        process = pb.start()
        val stdout = process!!.inputStream
        Thread({
            // v0.3.3 — drain + CAPTURE (avant : jetés — un mort immédiate
            // était INDIQUABLE, cf. doc classe).
            try {
                BufferedReader(InputStreamReader(stdout, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { captureOut(it) }
                }
            } catch (_: Exception) { }
        }, "nyx-tor-drain").apply { isDaemon = true }.start()

        // 3. Attente du control port (≤ 60 s) — v0.3.4 : le cookie SEUL ne
        // suffit plus ; on exige en PLUS un connect TCP ACCEPTÉ sur le port
        // de contrôle. C'est la correction de la course qui marquait le
        // backend FAILED pendant que Tor vivait son bootstrap tout seul.
        val cookie = File(DesktopPaths.torDir, "data/control.authcookie")
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (!process!!.isAlive) {
                throw IllegalStateException(
                    "le démon tor est mort au démarrage.\nSortie du démon :\n${stdoutTail(15)}"
                )
            }
            if (cookie.isFile && isPortOpen(p.control)) break
            Thread.sleep(250)
        }
        if (!(cookie.isFile && isPortOpen(p.control))) {
            process?.destroy()
            throw IllegalStateException(
                "control port tor indisponible (cookie ${if (cookie.isFile) "présent" else "absent"}, " +
                    "port ${p.control} fermé après 60 s).\n" +
                    "Sortie du démon :\n${stdoutTail(15)}\n${torLogTailSection()}"
            )
        }
        DesktopLog.i(
            "nyx-tor",
            "control port ouvert (cookie régénéré + connect TCP accepté sur ${p.control})"
        )

        // 4. Bootstrap → publication.
        try {
            TorControlClient(p.control).use { ctl ->
                ctl.authenticate(cookie)
                val bootDeadline = System.currentTimeMillis() + 180_000
                var lastPct = -1
                while (true) {
                    val phase = ctl.bootstrapPhase()
                    val pct = TorControlClient.parsePercent(phase)
                    // v0.3.3 — la PROGRESSION va au journal (diagnostic exporté),
                    // pas seulement à la bannière UI.
                    if (pct != null && pct != lastPct) {
                        lastPct = pct
                        DesktopLog.i("nyx-tor", "bootstrap $pct% — ${phase.take(120)}")
                    }
                    onBootstrap?.invoke(pct)
                    if (TorControlClient.isDone(phase)) break
                    if (System.currentTimeMillis() > bootDeadline) throw IllegalStateException(
                        "bootstrap tor expiré (3 min) — réseau bloqué ou horloge fausse ?\n" +
                            "Dernier état : ${phase.take(160)}\n${torLogTailSection()}"
                    )
                    Thread.sleep(500)
                }
                bootstrapped = true
                onBootstrap?.invoke(100)
                DesktopLog.i("nyx-tor", "bootstrap terminé (100%)")

                // Publication de l'onion d'identité : port 80 → réplique embarquée.
                // (v0.2.34+) Flags=Detach : le service survit à la fermeture de CETTE
                // connexion contrôle ; DEL_ONION reste possible depuis une nouvelle
                // connexion authentifiée à l'arrêt.
                val sid = ctl.publishOnionEd25519V3(identityPriv64, backendLocalPort, mediaLocalPort)
                serviceId = sid
                DesktopLog.i(
                    "nyx-tor",
                    "onion d'identité publié : $sid.onion (port 80 → local $backendLocalPort" +
                        (if (mediaLocalPort > 0) ", média 47471 → local $mediaLocalPort)" else ")")
                )
            }
        } catch (e: Exception) {
            // Le démon peut mourir PENDANT le bootstrap (antivirus…) : la sortie
            // capturée explique la cause.
            if (process?.isAlive == false) {
                throw IllegalStateException(
                    "${e.message}\nLe démon tor est MORT pendant le bootstrap.\n" +
                        "Sortie du démon :\n${stdoutTail(15)}",
                    e
                )
            }
            throw e
        }
        return p
    }

    private fun torLogTailSection(): String {
        val t = torLogTail(2_000).trim()
        return if (t.isEmpty()) "(tor.log absent)" else "Fin de tor.log :\n$t"
    }

    /**
     * v0.3.4 — supprime le cookie d'authentification d'une session
     * PRÉCÉDENTE (tor ne l'efface jamais en quittant). Sans cette purge,
     * « cookie.isFile » passait INSTANTANÉMENT au démarrage suivant et le
     * connect TCP ratrait le listener pas encore ouvert — LA course du port
     * de contrôle (preuve : logs terrain 27/10 15:28, échecs à +62/+17 ms).
     */
    internal fun purgeStaleCookie() {
        runCatching { File(DesktopPaths.torDir, "data/control.authcookie").delete() }
    }

    /** Arrêt propre : DEL_ONION puis fin du démon (bloquant ≤ 5 s). */
    fun shutdownNow() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching {
            serviceId?.let { sid ->
                TorControlClient(controlPort).use { ctl ->
                    ctl.authenticate(File(DesktopPaths.torDir, "data/control.authcookie"))
                    ctl.unpublish(sid)
                }
            }
        }
        runCatching { process?.destroy() }   // SIGTERM / terminate
        val deadline = System.currentTimeMillis() + 5_000
        while (process?.isAlive == true && System.currentTimeMillis() < deadline) Thread.sleep(100)
        if (process?.isAlive == true) runCatching { process?.destroyForcibly() }
        process = null
        serviceId = null
        bootstrapped = false
        ports = null
    }

    /** HSFETCH de guérison (portage v0.2.37 — contournement du cache client). */
    fun forceDescriptorRefresh(onion: String): Result<Unit> = runCatching {
        val host = onion.trim().removeSuffix(".onion").substringBefore(":")
        TorControlClient(controlPort).use { ctl ->
            ctl.authenticate(File(DesktopPaths.torDir, "data/control.authcookie"))
            ctl.hsfetch(host)
        }
    }

    /**
     * v0.3.3 — tue les tor.exe ORPHELINS qui exécutent NOTRE binaire (portage
     * killLeftoverTor ADR-007 v0.2.9). Cause racine visée : un démon survivant
     * à son application (crash, Task Manager) garde le verrou du
     * DataDirectory → « Tor is already running » à chaque lancement →
     * l'application ne se connecte PLUS à Tor jusqu'au redémarrage de Windows.
     *
     * Ciblage STRICT PAR CHEMIN D'EXÉCUTABLE : le tor du navigateur Tor
     * Browser de l'utilisateur n'est JAMAIS touché. Best-effort borné (8 s) :
     * un échec de la procédure n'empêche JAMAIS le démarrage.
     */
    fun killLeftoverTor(binFile: File): Int {
        return try {
            val killed: Int
            if (DesktopPaths.isWindows) {
                // PowerShell : processus tor.exe dont ExecutablePath == NOTRE
                // binaire (comparaison insensible à la casse — chemins Windows).
                val target = binFile.absolutePath.replace("'", "''")
                val ps = "Get-CimInstance Win32_Process -Filter \"Name='tor.exe'\" | " +
                    "Where-Object { \$_.ExecutablePath -eq '$target' } | " +
                    "ForEach-Object { Stop-Process -Id \$_.ProcessId -Force; Write-Output \$_.ProcessId }"
                val out = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                    .start()
                if (!out.waitFor(8, TimeUnit.SECONDS)) {
                    out.destroyForcibly()
                    DesktopLog.w("nyx-tor", "killLeftoverTor : expiration du délai PowerShell (ignoré)")
                    0
                } else {
                    val n = out.inputStream.bufferedReader().readText().trim().split('\n')
                        .count { it.isNotBlank() }
                    killed = n
                    if (n > 0) DesktopLog.w("nyx-tor", "killLeftoverTor : $n tor.exe orphelin(s) arrêté(s) avant démarrage")
                    killed
                }
            } else {
                // Bac à sable Linux (dev/smoke) : pgrep -f sur le chemin exact.
                val out = ProcessBuilder("pgrep", "-f", binFile.absolutePath).start()
                if (!out.waitFor(3, TimeUnit.SECONDS)) {
                    out.destroyForcibly(); return 0
                }
                val pids = out.inputStream.bufferedReader().readText().trim()
                if (pids.isBlank()) return 0
                var n = 0
                for (pid in pids.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
                    runCatching {
                        ProcessBuilder("kill", pid).start().waitFor(3, TimeUnit.SECONDS)
                        n++
                    }
                }
                if (n > 0) DesktopLog.w("nyx-tor", "killLeftoverTor : $n tor orphelin(s) arrêté(s)")
                n
            }
        } catch (e: Exception) {
            DesktopLog.w("nyx-tor", "killLeftoverTor ignoré (${e.javaClass.simpleName}: ${e.message})")
            0
        }
    }

    companion object {
        /**
         * v0.3.4 — connect TCP éphémère vers 127.0.0.1:[port] : true si un
         * listener l'ACCEPTE. Timeout court (localhost) : un port fermé refuse
         * immédiatement (WSECONNREFUSED) — c'est exactement le signal qui
         * manquait à l'ancienne attente sur le seul fichier cookie.
         */
        internal fun isPortOpen(port: Int): Boolean = try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                true
            }
        } catch (_: Exception) {
            false
        }

        private fun freePort(preferred: Int): Int {
            for (p in preferred until preferred + 40) {
                if (isFree(p)) return p
            }
            return ServerSocket(0).use { it.localPort }
        }

        private fun isFree(port: Int): Boolean = try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }
}
