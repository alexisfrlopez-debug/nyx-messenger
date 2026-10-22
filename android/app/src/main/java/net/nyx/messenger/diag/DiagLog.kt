package net.nyx.messenger.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v0.2.33 — JOURNAL DE DIAGNOSTIC PERSISTANT (build test).
 *
 * PROBLÈME TERRAIN (export v0.2.31-test2, 19/10 16:24) : le logcat d'une app
 * Android ne contient que les lignes de SON PROPRE process — à la mort du
 * process (système qui tue l'app en arrière-plan, redémarrage, crash), TOUTES
 * les traces disparaissent. L'export montrait un process de 58 s (aucune
 * trace des appels tentés dans l'heure précédente) : le diagnostic
 * « appels qui ne décrochent pas » est INDÉMONTRABLE depuis un export pris
 * après coup.
 *
 * SOLUTION : les événements de diagnostic CRITIQUES (appels, sondes onion,
 * publication Tor, annonces réplica) sont AUSSI appending dans un petit
 * anneau SUR DISQUE (`files/diag-ring.log`) qui SURVIT au redémarrage du
 * process ; [LogCollector] l'embarque dans chaque export (section
 * « DIAG PERSISTANT »). L'anneau est borné ([MAX_BYTES]) : les lignes les
 * plus anciennes sont évacuées par la tête.
 *
 * RÈGLES :
 *  - AUCUNE donnée de conversation, aucune clé, aucun contenu de message —
 *    uniquement des événements techniques (mêmes libellés que logcat) ;
 *  - échecs d'écriture SILENCIEUX (le diagnostic ne doit jamais faire
 *    planter l'app) ;
 *  - appelé depuis le build test ET standard (inoffensif si le fichier
 *    n'est jamais exporté — le bouton d'export n'existe qu'en build test).
 */
object DiagLog {

    /** Borne haute de l'anneau (octets) — au-delà, on évacue par la tête. */
    private const val MAX_BYTES = 64 * 1024

    /** Marge évacuée d'un coup quand la borne est dépassée (octets). */
    private const val TRIM_CHUNK = 16 * 1024

    @Volatile
    private var file: File? = null

    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** À appeler une fois au démarrage (NyxApplication) — idempotent. */
    fun init(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            runCatching {
                val f = File(context.filesDir, "diag-ring.log")
                // Rétro-compat : un anneau trop gros d'une ancienne version
                // est raboté dès l'init.
                if (f.exists() && f.length() > MAX_BYTES) trim(f)
                file = f
            }
        }
    }

    /** Info — logcat + anneau persistant. */
    fun i(tag: String, msg: String) {
        android.util.Log.i(tag, msg)
        append("I", tag, msg)
    }

    /** Warning — logcat + anneau persistant. */
    fun w(tag: String, msg: String) {
        android.util.Log.w(tag, msg)
        append("W", tag, msg)
    }

    private fun append(level: String, tag: String, msg: String) {
        val f = file ?: return // non initialisé (process de test JVM) : no-op
        runCatching {
            synchronized(this) {
                val line = "${ts.format(Date())} $level $tag: $msg\n"
                if (f.length() + line.toByteArray(Charsets.UTF_8).size > MAX_BYTES) trim(f)
                f.appendText(line)
            }
        }
    }

    /** Évacue la tête du fichier (appelée sous verrou). */
    private fun trim(f: File) {
        val bytes = f.readBytes()
        if (bytes.size <= TRIM_CHUNK) { f.writeBytes(ByteArray(0)); return }
        val window = bytes.copyOfRange(bytes.size - TRIM_CHUNK, bytes.size)
        // coupe à la première fin de ligne complète
        val start = window.indexOf('\n'.code.toByte()).let { if (it < 0) 0 else it + 1 }
        f.writeBytes(window.copyOfRange(start, window.size))
    }

    /** Fin de l'anneau pour [LogCollector] (chaîne vide si jamais initialisé). */
    fun tail(maxBytes: Int): String = runCatching {
        val f = file ?: return ""
        if (!f.exists()) return ""
        val bytes = f.readBytes()
        if (bytes.size <= maxBytes) String(bytes, Charsets.UTF_8)
        else {
            val window = bytes.copyOfRange(bytes.size - maxBytes, bytes.size)
            val start = window.indexOf('\n'.code.toByte()).let { if (it < 0) 0 else it + 1 }
            String(window.copyOfRange(start, window.size), Charsets.UTF_8)
        }
    }.getOrDefault("")
}
