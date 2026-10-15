package net.nyx.messenger.diag

import android.content.Context
import android.os.Build
import net.nyx.messenger.BuildConfig
import net.nyx.messenger.tor.TorManager
import java.io.BufferedReader
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
 *    état courant du Tor embarqué ;
 *  - la capture `logcat -d -v threadtime` (anneaux du système/app).
 *
 * La base de données (messages, contacts) n'est JAMAIS lue ici : le fichier
 * produit ne contient que des journaux système. Il est déposé par [DriveUploader]
 * dans le dossier de travail Drive dédié au débogage.
 */
object LogCollector {

    /** Taille max du logcat embarqué (dernier octet gardé) — upload léger. */
    private const val MAX_LOGCAT_BYTES = 1_500_000

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
}
