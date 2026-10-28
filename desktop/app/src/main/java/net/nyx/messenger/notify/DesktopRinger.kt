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
 * de 100 ms et vérique le drapeau entre chaque — l'arrêt est < 150 ms).
 * Génération du signal en mémoire (aucune ressource embarquée) : sonnerie
 * classique bi-ton 440+480 Hz (1,2 s) suivie d'une pause (1,8 s).
 * javax.sound indisponible (pas de carte son, tête serveur) → journal
 * honnête, la fenêtre reste le seul signal (jamais un crash pour une
 * sonnerie — miroir Android).
 */
object DesktopRinger {

    private val lock = Any()
    private var thread: Thread? = null

    @Volatile private var stopped = false

    /** Démarre la sonnerie (idempotent — déjà active = no-op). */
    fun start() {
        synchronized(lock) {
            if (thread != null) return
            stopped = false
            val t = Thread({ play() }, "nyx-ring-desktop")
            t.isDaemon = true
            thread = t
            t.start()
            DesktopLog.i("nyx-ring", "sonnerie bureau DÉMARRÉE (bi-ton 440+480 Hz — stop déterministe au décroché/refus/fin)")
        }
    }

    /** Stoppe la sonnerie (idempotent — déjà silencieuse = no-op). */
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
    private const val CHUNK_MS = 100

    private fun play() {
        try {
            val fmt = AudioFormat(RATE.toFloat(), 16, 1, true, false)
            val line = try {
                AudioSystem.getSourceDataLine(fmt).also { it.open(fmt, 8_192) }
            } catch (e: Exception) {
                DesktopLog.w("nyx-ring", "sonnerie IMPOSSIBLE (${e::class.simpleName} : ${e.message?.take(60)}) — fenêtre seule")
                return
            }
            line.start()
            try {
                val ring = genDualTone(1_200)   // 1,2 s de sonnerie
                val pause = ByteArray(RATE * 2 * 18 / 10)  // 1,8 s de silence (16 kHz mono 16 bits)
                val chunk = RATE / 10 * 2       // 100 ms = 3 200 o
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

    /** Bi-ton classique 440+480 Hz, volume modéré (0,35). */
    private fun genDualTone(ms: Int): ByteArray {
        val n = RATE * ms / 1000
        val out = ByteArray(n * 2)
        var i = 0
        while (i < n) {
            val t = i.toDouble() / RATE
            // Enveloppe douce (10 ms) pour éviter les clics aux bords.
            val edge = minOf(i, n - 1 - i, RATE / 100).toDouble()
            val env = if (edge >= RATE / 100.0) 1.0 else edge / (RATE / 100.0)
            val v = env * 0.35 * (
                kotlin.math.sin(2.0 * Math.PI * 440.0 * t) +
                    kotlin.math.sin(2.0 * Math.PI * 480.0 * t)
                )
            val s = (v * 32_767.0).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            i++
        }
        return out
    }
}
