package net.nyx.messenger.desktop

import java.io.File
import java.io.RandomAccessFile

/**
 * Journalisation desktop — équivalent DiagLog Android : tampon mémoire borné
 * (diagnostic/export) + sortie stderr. Aucune dépendance (conteneur sans
 * logcat) ; les marqueurs terrain ("nyx-send", "nyx-recv", "nyx-poll"…)
 * sont conservés à l'identique pour que les habitudes de lecture des exports
 * gardées par l'opérateur restent valables.
 *
 * v0.3.3 (session 73, miroir DiagLog v0.2.33 Android — « anneau de diagnostic
 * PERSISTANT ») : le journal est AUSSI appendu dans
 * `<dataDir>/logs/nyx-desktop.log`, borné (≈ 512 Ko gardés au-delà de 1 Mo) —
 * les événements critiques (démarrage tor, bootstrap, publication onion,
 * sondes, échecs) survivent désormais à la mort du process / à un crash :
 * indispensable pour diagnostiquer UN DÉMARRAGE qui échoue (l'exportMemory
 * seul partait VIDE puisque le process redémarrant perdait tout).
 */
object DesktopLog {

    private val buffer = ArrayDeque<String>()
    private const val MAX_LINES = 4_000

    /** v0.3.3 — fichier persistant (créé paresseusement ; rotation bornée). */
    @Volatile private var logFile: File? = null
    private const val MAX_FILE_BYTES = 1_000_000
    private const val KEEP_FILE_BYTES = 512_000

    fun i(tag: String, msg: String) = line("I", tag, msg)
    fun w(tag: String, msg: String) = line("W", tag, msg)

    private fun line(level: String, tag: String, msg: String) {
        val entry = "${java.time.LocalDateTime.now().format(TS)} $level/$tag: $msg"
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        System.err.println(entry)
        appendToFile(entry)
    }

    private val TS = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** Export complet du tampon mémoire (équivalent LogCollector Android). */
    fun dump(): String = synchronized(buffer) { buffer.joinToString("\n") }

    // ---------------- v0.3.3 : anneau persistant ----------------

    /** Fixe le fichier journal persistant (appelé par ServiceLocator.init). */
    fun initFile(file: File) {
        logFile = file
        runCatching {
            file.parentFile?.mkdirs()
            appendToFile("---- (démarrage du journal persistant) ----")
        }
    }

    private fun appendToFile(entry: String) {
        val f = logFile ?: return
        try {
            synchronized(this) {
                RandomAccessFile(f, "rw").use { raf ->
                    raf.seek(raf.length())
                    // UTF-8 EXPLICITE — RandomAccessFile.writeBytes écrirait le
                    // bas 8 bits de chaque char et MANGLE les accents (leçon
                    // v0.3.3 : « première ligne » illisible ensuite en UTF-8).
                    raf.write((entry + "\n").toByteArray(Charsets.UTF_8))
                }
                if (f.length() > MAX_FILE_BYTES) rotate(f)
            }
        } catch (_: Exception) {
            // Le journal ne doit JAMAIS faire échouer l'application.
        }
    }

    /** Coupe la tête du fichier : garde la FIN (la plus récente/informative). */
    private fun rotate(f: File) {
        runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                raf.seek(len - KEEP_FILE_BYTES)
                raf.readLine() // saute la ligne partielle en tête
                val rest = ByteArray((len - raf.filePointer).toInt())
                raf.readFully(rest)
                RandomAccessFile(f, "rw").use { out ->
                    out.setLength(0)
                    out.write(rest)
                }
            }
        }
    }

    /** Fin du fichier persistant (diagnostic de DERNIÈRE instance morte). */
    fun fileTail(maxBytes: Int = 128 * 1024): String = runCatching {
        val f = logFile ?: return ""
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            if (len <= maxBytes) {
                ByteArray(len.toInt()).also { raf.readFully(it) }.toString(Charsets.UTF_8)
            } else {
                raf.seek(len - maxBytes)
                raf.readLine() // ligne partielle en tête
                ByteArray((len - raf.filePointer).toInt()).also { raf.readFully(it) }
                    .toString(Charsets.UTF_8)
            }
        }
    }.getOrDefault("")
}
