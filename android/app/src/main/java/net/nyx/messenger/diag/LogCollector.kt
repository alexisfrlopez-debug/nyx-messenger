package net.nyx.messenger.diag

import android.content.Context
import android.os.Build
import net.nyx.messenger.BuildConfig
import net.nyx.messenger.tor.TorManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collecte de diagnostic — UNIQUEMENT utilisée dans le build « test »
 * (l'appel UI est gardé par BuildConfig.TEST_BUILD).
 *
 * Contenu collecté (aucune donnée de conversation, aucune clé) :
 *  - en-tête : version app, flavor, appareil, version Android, horodatage UTC,
 *    état courant du Tor embarqué, réplicas d'amorçage embarqués ;
 *  - la capture `logcat -d -v threadtime` (anneaux du système/app) ;
 *  - la FIN de tor.log (v0.2.5) — CRUCIAL : le torrc (« Log notice file »)
 *    envoie la progression réelle du bootstrap (lignes « Bootstrapped X% »)
 *    dans CE fichier et PAS dans logcat ; sans lui, un Tor bloqué à 0 % est
 *    indémontrable depuis un export (cas Blackview v0.2.4).
 *
 * La base de données (messages, contacts) n'est JAMAIS lue ici : le fichier
 * produit ne contient que des journaux système. Il est déposé par [DriveUploader]
 * dans le dossier de travail Drive dédié au débogage.
 */
object LogCollector {

    /** Taille max du logcat embarqué (dernier octet gardé) — upload léger. */
    private const val MAX_LOGCAT_BYTES = 1_500_000

    /** Taille max de tor.log embarqué (fin gardée). */
    private const val MAX_TORLOG_BYTES = 262_144

    fun collect(context: Context): String {
        val sb = StringBuilder(64 * 1024)
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        sb.append("=== NYX EXPORT LOGS (build test) ===\n")
        sb.append("version  : ").append(BuildConfig.VERSION_NAME)
            .append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n")
        sb.append("flavor   : ").append(if (BuildConfig.TEST_BUILD) "test" else "standard").append("\n")
        sb.append("appareil : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" — Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("date     : ").append(utc.format(Date())).append("\n")
        try {
            sb.append("tor      : ").append(TorManager.get(context).state.value).append("\n")
        } catch (_: Exception) {
            sb.append("tor      : (état indisponible)\n")
        }
        // v0.2.7 : la VÉRITÉ sur les réplicas — l'entête v0.2.6 affichait la
        // chaîne brute embarquée (toujours renseignée) alors que la liste
        // ACTIVE du dépôt était vide (bug du filtre « .onion:80 ») → l'export
        // laissait croire à un réplica configuré, sonde pourtant jamais lancée.
        val activeReplicas = try {
            net.nyx.messenger.di.ServiceLocator.repository.replicas
        } catch (_: Exception) {
            emptyList<String>()
        }
        sb.append("réplicas actifs    : ")
            .append(if (activeReplicas.isEmpty()) "(AUCUN — sonde impossible)" else activeReplicas.joinToString(", "))
            .append("\n")
        sb.append("réplicas embarqués : ").append(BuildConfig.BOOTSTRAP_REPLICAS.ifBlank { "(aucun)" }).append("\n")
        sb.append("\n===== TOR.LOG (fin — progression réelle du bootstrap) =====\n")
        sb.append(torLogTail(context))
        sb.append("\n===== LOGCAT (fin de tampon) =====\n")

        val logcat = dumpLogcat()
        // Garde la FIN du tampon (les événements les plus récents sont les utiles).
        val bytes = logcat.toByteArray(Charsets.UTF_8)
        val kept = if (bytes.size > MAX_LOGCAT_BYTES) {
            String(bytes, bytes.size - MAX_LOGCAT_BYTES, MAX_LOGCAT_BYTES, Charsets.UTF_8)
                .substringAfter('\n', "") // coupe au milieu d'une ligne -> jette la 1re
        } else logcat

        sb.append(kept)
        sb.append("\n===== FIN =====\n")
        return sb.toString()
    }

    /** Exécute `logcat -d -v threadtime` et retourne la sortie (tolérante aux erreurs). */
    private fun dumpLogcat(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
        val out = StringBuilder(256 * 1024)
        BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8), 64 * 1024).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                out.append(line).append('\n')
            }
        }
        p.waitFor()
        p.destroy()
        out.toString()
    } catch (e: Exception) {
        "(logcat indisponible : ${e.javaClass.simpleName}: ${e.message})\n"
    }

    /**
     * FIN de files/tor/tor.log (cf. NyxTorConfig — « Log notice file ») :
     * lignes « Bootstrapped X% », avertissements réseau du démon, date/heure
     * réelles — la seule source de vérité sur un bootstrap qui stagne.
     */
    private fun torLogTail(context: Context): String = try {
        val f = File(context.filesDir, "tor/tor.log")
        when {
            !f.exists() -> "(tor.log absent — le démon n'a jamais démarré)\n"
            f.length() == 0L -> "(tor.log vide)\n"
            else -> {
                val bytes = f.readBytes()
                val text = if (bytes.size > MAX_TORLOG_BYTES) {
                    String(bytes, bytes.size - MAX_TORLOG_BYTES, MAX_TORLOG_BYTES, Charsets.UTF_8)
                        .substringAfter('\n', "") // coupe au milieu d'une ligne -> jette la 1re
                } else String(bytes, Charsets.UTF_8)
                text + if (!text.endsWith("\n")) "\n" else ""
            }
        }
    } catch (e: Exception) {
        "(tor.log illisible : ${e.javaClass.simpleName}: ${e.message})\n"
    }
}
