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
            // v0.2.44 — CONFLUX DÉSACTIVÉ (session 65) : les tor.log terrain
            // (2 téléphones, 22-23/10) sont saturés de « All current guards
            // excluded by path restriction type 2 » = RST_EXCL_LIST = la
            // restriction CONFLUX (multi-chemins, active par défaut en
            // 0.4.9.x, CONFLUX_ENABLED_DEFAULT=1) qui épuise en boucle les
            // guards et perturbe la construction des circuits — dont les
            // points d'introduction des services oignon des téléphones
            // (chroniquement injoignables). Conflux ne s'applique QU'AU
            // trafic exit or la messagerie n'en émet AUCUN (tout est
            // onion↔onion, source tor : or_options_st.h « excludes onion
            // service traffic ») : le couper est SANS PERTE fonctionnelle
            // et retire le churn de guards.
            appendLine("ConfluxEnabled 0")
            // v0.2.44 — LOG INFO : le tor.log des téléphones (notice) ne
            // montre RIEN des cycles intro-points/upload-descripteur — les
            // marqueurs forensiques (« Launching a circuit to intro point »,
            // « stored successfully », échecs d'upload) sont en INFO. Le log
            // est tronqué à l'export (queue du fichier) : la taille reste
            // maîtrisée et la prochaine ronde terrain tranchera la cause
            // exacte de l'injoignabilité des onions téléphone.
            appendLine("Log info file ${logFile.absolutePath}")
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
