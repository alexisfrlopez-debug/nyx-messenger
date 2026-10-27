package net.nyx.messenger.desktop

import net.nyx.messenger.tor.TorControlClient
import java.io.File
import java.net.ServerSocket
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
     */
    fun startAndWait(identityPriv64: ByteArray, backendLocalPort: Int): Ports {
        val binFile = bin ?: error(
            "binaire tor introuvable — installez via le bundle officiel " +
                "(tor/tor.exe à côté de l'application) ou installez Tor système"
        )
        stopping.set(false)
        bootstrapped = false

        // 1. Ports libres (préférés d'abord — cohabitation avec un Tor système).
        val p = Ports(freePort(9050), freePort(9051), freePort(8090))
        ports = p

        // 2. torrc + démon
        val torrc = writeTorrc(ProcessHandle.current().pid())
        val pb = ProcessBuilder(binFile.absolutePath, "-f", torrc.absolutePath)
            .redirectErrorStream(true)
        process = pb.start()
        val stdout = process!!.inputStream
        Thread({
            // Drain de la sortie du démon (sinon le tampon plein le bloque).
            val buf = ByteArray(4096)
            try { while (stdout.read(buf) >= 0) { } } catch (_: Exception) { }
        }, "nyx-tor-drain").apply { isDaemon = true }.start()

        // 3. Attente du cookie du control port (≤ 60 s).
        val cookie = File(DesktopPaths.torDir, "data/control.authcookie")
        val deadline = System.currentTimeMillis() + 60_000
        while (!cookie.isFile && System.currentTimeMillis() < deadline) {
            if (process!!.isAlive) Thread.sleep(200) else error("le démon tor est mort au démarrage (voir tor/tor.log)")
        }
        check(cookie.isFile) { "control port tor indisponible (cookie absent après 60 s)" }

        // 4. Bootstrap → publication.
        TorControlClient(p.control).use { ctl ->
            ctl.authenticate(cookie)
            val bootDeadline = System.currentTimeMillis() + 180_000
            while (true) {
                val phase = ctl.bootstrapPhase()
                val pct = TorControlClient.parsePercent(phase)
                onBootstrap?.invoke(pct)
                if (TorControlClient.isDone(phase)) break
                if (System.currentTimeMillis() > bootDeadline) error("bootstrap tor expiré (3 min) — réseau injoignable ?")
                Thread.sleep(500)
            }
            bootstrapped = true
            onBootstrap?.invoke(100)

            // Publication de l'onion d'identité : port 80 → réplique embarquée.
            // (v0.2.34+) Flags=Detach : le service survit à la fermeture de CETTE
            // connexion contrôle ; DEL_ONION reste possible depuis une nouvelle
            // connexion authentifiée à l'arrêt.
            val sid = ctl.publishOnionEd25519V3(identityPriv64, backendLocalPort, 0)
            serviceId = sid
            DesktopLog.i("nyx-tor", "onion d'identité publié : $sid.onion (port 80 → local $backendLocalPort)")
        }
        return p
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

    companion object {
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
