package net.nyx.messenger.notify

import net.nyx.messenger.desktop.DesktopLog
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * v0.3.8 — SONNERIE D'APPEL BUREAU (session 78 : « pouvoir décrocher ou
 * rejeter un appel quand l'ordinateur est verrouillé sans devoir tout
 * déverrouiller »).
 *
 * CAUSE TERRAIN 28/10 07:17 : l'appel visio ENTRANT du téléphone n'a JAMAIS
 * été remarqué sur le PC — aucune sonnerie n'existait au bureau (une
 * info-bulle plateau, invisible écran verrouillé/fenêtre cachée). 54 s de
 * sonnerie muette, 0 acceptation.
 *
 * Miroir de CallRinger Android (v0.2.52) : son possédé en boucle, stop
 * DÉTERMINISTE au décroché/refus/fin (le thread écrit par petits morceaux
 * de 100 ms et vérifie le drapeau entre chaque — l'arrêt est < 150 ms).
 * Génération du signal en mémoire (aucune ressource embarquée) : sonnerie
 * classique bi-ton 440+480 Hz (1,2 s) suivie d'une pause (1,8 s).
 * javax.sound indisponible (pas de carte son, tête serveur) → journal
 * honnête, la fenêtre reste le seul signal (jamais un crash pour une
 * sonnerie — miroir Android).
 *
 * v0.3.10 — DURCIE + MODE RETOUR D'APPEL (terrain 28/10 14:00 : « pas de
 * son coté pc » + « lors d'une tentative d'appel, je veux une sonnerie côté
 * appelant pour indiquer que le tel ou pc appelle ») :
 *  - volume relevé (0,55) et REPLI DE FORMAT : si la ligne 16 kHz est
 *    refusée par le mixeur, 44,1 kHz est tentée avant d'abandonner (certaines
 *    cartes Windows refusent les taux exotiques — jamais une sonnerie muette
 *    pour une question de format) ;
 *  - [startRingback] : TONALITÉ DE RETOUR D'APPEL chez l'APPELANT pendant
 *    que le pair sonne (miroir CallRingback Android v0.2.58 — bi-ton
 *    425+480 Hz, 1,5 s son / 3,5 s pause, volume plus doux que la sonnerie) ;
 *    stop déterministe au décroché/refus/timeout.
 */
object DesktopRinger {

    /** Mode de tonalité. */
    enum class Mode { INCOMING, RINGBACK }

    private val lock = Any()
    private var thread: Thread? = null
    private var mode = Mode.INCOMING

    @Volatile private var stopped = false

    /** Démarre la sonnerie entrante (idempotent — déjà active = no-op). */
    fun start() = startMode(Mode.INCOMING)

    /** v0.3.10 — Démarre la tonalité de retour d'appel chez l'APPELANT. */
    fun startRingback() = startMode(Mode.RINGBACK)

    private fun startMode(m: Mode) {
        synchronized(lock) {
            if (thread != null) return
            stopped = false
            mode = m
            val t = Thread({ play() }, "nyx-ring-desktop")
            t.isDaemon = true
            thread = t
            t.start()
            DesktopLog.i(
                "nyx-ring",
                if (m == Mode.INCOMING)
                    "sonnerie bureau DÉMARRÉE (bi-ton 440+480 Hz — stop déterministe au décroché/refus/fin)"
                else
                    "retour d'appel bureau DÉMARRÉ (bi-ton 425+480 Hz, 1,5 s/3,5 s — stop au décroché/refus/timeout)"
            )
        }
    }

    /** Stoppe la tonalité courante, quelle qu'elle soit (idempotent). */
    fun stop() {
        synchronized(lock) {
            val t = thread ?: return
            thread = null
            stopped = true
            // Le thread s'arrête de lui-même au morceau suivant (≤ 150 ms) —
            // pas d'interrupt : SourceDataLine.write n'y répond pas proprement.
            DesktopLog.i("nyx-ring", "sonnerie bureau STOPPÉE")
        }
    }

    private const val RATE = 16_000
    private const val RATE_FALLBACK = 44_100
    private const val CHUNK_MS = 100

    private fun play() {
        try {
            val ringback = mode == Mode.RINGBACK
            val fmt16 = AudioFormat(RATE.toFloat(), 16, 1, true, false)
            val fmt44 = AudioFormat(RATE_FALLBACK.toFloat(), 16, 1, true, false)
            var line: javax.sound.sampled.SourceDataLine? = null
            var rate = RATE
            for (fmt in listOf(fmt16, fmt44)) {
                try {
                    line = AudioSystem.getSourceDataLine(fmt).also { it.open(fmt, 8_192) }
                    rate = fmt.sampleRate.toInt()
                    break
                } catch (e: Exception) {
                    DesktopLog.w(
                        "nyx-ring",
                        "ligne ${fmt.sampleRate.toInt()} Hz refusée (${e::class.simpleName}) — repli"
                    )
                }
            }
            if (line == null) {
                DesktopLog.w("nyx-ring", "sonnerie IMPOSSIBLE (aucun format accepté) — fenêtre seule")
                return
            }
            line.start()
            try {
                // Sonnerie entrante : 1,2 s son / 1,8 s pause (v0.3.8).
                // Retour d'appel : 1,5 s son / 3,5 s pause (v0.3.10, miroir
                // cadence française — plus doux, c'est l'APPELANT qui l'entend).
                val toneMs = if (ringback) 1_500 else 1_200
                val pauseMs = if (ringback) 3_500 else 1_800
                val ring = if (ringback) genDualTone(rate, toneMs, 425.0, 480.0, 0.30)
                          else genDualTone(rate, toneMs, 440.0, 480.0, 0.55)
                val pause = ByteArray(rate * 2 * pauseMs / 1000)
                val chunk = rate / 10 * 2       // 100 ms
                while (!stopped) {
                    writeChunks(line, ring, chunk)
                    if (stopped) break
                    writeChunks(line, pause, chunk)
                }
            } finally {
                runCatching { line.stop() }
                runCatching { line.close() }
            }
        } catch (e: Exception) {
            DesktopLog.w("nyx-ring", "sonnerie interrompue : ${e::class.simpleName} ${e.message?.take(60)}")
        }
    }

    private fun writeChunks(line: javax.sound.sampled.SourceDataLine, data: ByteArray, chunk: Int) {
        var off = 0
        while (off < data.size && !stopped) {
            val len = minOf(chunk, data.size - off)
            line.write(data, off, len)
            off += len
        }
    }

    /**
     * Bi-ton classique, enveloppe douce 10 ms (pas de clic aux bords).
     * v0.3.10 — [rate]/[f1]/[f2]/[volume] paramétrables (sonnerie vs retour
     * d'appel — le retour est nettement plus doux : l'appelant l'entend
     * pendant des dizaines de secondes contre son gré sinon).
     */
    private fun genDualTone(rate: Int, ms: Int, f1: Double, f2: Double, volume: Double): ByteArray {
        val n = rate * ms / 1000
        val out = ByteArray(n * 2)
        var i = 0
        while (i < n) {
            val t = i.toDouble() / rate
            val edge = minOf(i, n - 1 - i, rate / 100).toDouble()
            val env = if (edge >= rate / 100.0) 1.0 else edge / (rate / 100.0)
            val v = env * volume * (
                kotlin.math.sin(2.0 * Math.PI * f1 * t) +
                    kotlin.math.sin(2.0 * Math.PI * f2 * t)
                )
            val s = (v * 32_767.0).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            i++
        }
        return out
    }
}
