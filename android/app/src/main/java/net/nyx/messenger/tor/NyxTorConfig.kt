package net.nyx.messenger.tor

import android.content.Context
import java.io.File
import java.net.ServerSocket

/**
 * Configuration du Tor embarqué (ADR-007) : répertoires privés, choix de ports
 * libres, génération du torrc. Rien n'est codé en dur côté ports — on sonde les
 * ports disponibles pour cohabiter avec un éventuel Orbot (9150 de repli).
 *
 * Fichiers (dans filesDir/tor/, jamais dans le stockage externe) :
 *   torrc            — configuration générée (SocksPort, ControlPort, cookie, logs)
 *   tor.log          — logs de notice tor (diagnostic)
 *   data/            — DataDirectory tor (cache, clé de média… pas de clé d'identité :
 *                      l'identité vit scellée dans le Keystore, cf. IdentityManager)
 *   data/control.authcookie — cookie du control port (lu en mémoire uniquement)
 */
class NyxTorConfig private constructor(
    val dataDir: File,
    val torrcFile: File,
    val logFile: File,
    val cookieFile: File,
    val socksPort: Int,
    val controlPort: Int,
    /** Port local cible du service onion de l'utilisateur (v0.2 : P2P direct). */
    val onionLocalPort: Int,
) {
    val socksProxyHost: String get() = "127.0.0.1"

    /** Écrit le torrc (0600). `appPid` permet à tor de mourir si l'app meurt. */
    fun writeTorrc(appPid: Int) {
        dataDir.mkdirs()
        val torrc = buildString {
            appendLine("# Nyx — torrc généré (ADR-007). Ne pas éditer : régénéré à chaque démarrage.")
            appendLine("SocksPort 127.0.0.1:$socksPort IsolateDestAddr")
            appendLine("ControlPort 127.0.0.1:$controlPort")
            appendLine("CookieAuthentication 1")
            appendLine("CookieAuthFile ${cookieFile.absolutePath}")
            appendLine("DataDirectory ${dataDir.absolutePath}")
            appendLine("Log notice file ${logFile.absolutePath}")
            appendLine("__OwningControllerProcess $appPid")
            appendLine("SafeLogging 1")
            // Réseau restreint (censure) : ponts à ajouter plus tard via IPtProxy — ADR-007 §3.
        }
        torrcFile.writeText(torrc)
        torrcFile.setReadable(false, false)
        torrcFile.setReadable(true, true)
        torrcFile.setWritable(false, false)
        torrcFile.setWritable(true, true)
    }

    companion object {
        private const val PREFERRED_SOCKS = 9050
        private const val PREFERRED_CONTROL = 9051
        private const val PREFERRED_ONION_LOCAL = 8090

        /** Crée la config : sonde les ports libres (préférés d'abord), prépare les fichiers. */
        fun create(context: Context): NyxTorConfig {
            val base = File(context.filesDir, "tor")
            val dataDir = File(base, "data").apply { mkdirs() }
            val cfg = NyxTorConfig(
                dataDir = dataDir,
                torrcFile = File(base, "torrc"),
                logFile = File(base, "tor.log"),
                cookieFile = File(dataDir, "control.authcookie"),
                socksPort = freePort(PREFERRED_SOCKS),
                controlPort = freePort(PREFERRED_CONTROL),
                onionLocalPort = freePort(PREFERRED_ONION_LOCAL),
            )
            return cfg
        }

        /** Port préféré si libre, sinon le premier port libre suivant (max +40). */
        private fun freePort(preferred: Int): Int {
            for (p in preferred until preferred + 40) {
                if (isFree(p)) return p
            }
            // Dernier recours : un port éphémère quelconque
            ServerSocket(0).use { return it.localPort }
        }

        private fun isFree(port: Int): Boolean = try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }
}
