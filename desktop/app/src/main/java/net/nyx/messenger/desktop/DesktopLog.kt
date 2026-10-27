package net.nyx.messenger.desktop

/**
 * Journalisation desktop — équivalent DiagLog Android : tampon mémoire borné
 * (diagnostic/export) + sortie stderr. Aucune dépendance (conteneur sans
 * logcat) ; les marqueurs terrain ("nyx-send", "nyx-recv", "nyx-poll"…)
 * sont conservés à l'identique pour que les habitudes de lecture des exports
 * gardées par l'opérateur restent valables.
 */
object DesktopLog {

    private val buffer = ArrayDeque<String>()
    private const val MAX_LINES = 4_000

    fun i(tag: String, msg: String) = line("I", tag, msg)
    fun w(tag: String, msg: String) = line("W", tag, msg)

    private fun line(level: String, tag: String, msg: String) {
        val entry = "${java.time.LocalDateTime.now().format(TS)} $level/$tag: $msg"
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        System.err.println(entry)
    }

    private val TS = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** Export complet du tampon (équivalent LogCollector Android). */
    fun dump(): String = synchronized(buffer) { buffer.joinToString("\n") }
}
