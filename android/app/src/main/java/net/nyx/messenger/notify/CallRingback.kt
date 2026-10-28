package net.nyx.messenger.notify

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * v0.2.58 — TONALITÉ DE RETOUR D'APPEL chez l'APPELANT (demande opérateur
 * session 80, verbatim : « lors d'une tentative d'appel, je veux une
 * sonnerie côté appelant pour indiquer que le tel ou pc appelle »).
 *
 * Miroir de CallRinger (v0.2.52) et DesktopRinger (v0.3.10) : son POSSÉDÉ en
 * boucle, stop DÉTERMINISTE au décroché/refus/timeout (le thread écrit par
 * petits morceaux de 100 ms et vérifie le drapeau entre chaque — l'arrêt
 * est < 150 ms). Génération du signal EN MÉMOIRE (aucune ressource
 * embarquée) : bi-ton 425+480 Hz (cadence française 1,5 s son / 3,5 s
 * pause), volume volontairement DOUX (0,30) — c'est l'appelant qui
 * l'entend, souvent longtemps.
 *
 * Sécurité de repli : AudioTrack indisponible (pas de carte son, échec
 * d'ouverture) → journal honnête, l'écran d'appel reste le seul signal
 * (jamais un crash pour une tonalité — miroir CallRinger).
 */
object CallRingback {

    private val lock = Any()
    private var thread: Thread? = null

    @Volatile private var stopped = false

    /** Démarre la tonalité (idempotent — déjà active = no-op). */
    fun start() {
        synchronized(lock) {
            if (thread != null) return
            val ctx = NotifyCore.appContext ?: return
            try {
                val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
                    // Volume média à zéro : honnête, pas de fausse promesse.
                    net.nyx.messenger.diag.DiagLog.w(
                        "nyx-ring", "retour d'appel inutile (volume média à 0) — écran d'appel seul"
                    )
                    return
                }
            } catch (_: Exception) {
                // Consultation best-effort : on tente quand même la lecture.
            }
            stopped = false
            val t = Thread({ play() }, "nyx-ringback")
            t.isDaemon = true
            thread = t
            t.start()
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-ring",
                "retour d'appel DÉMARRÉ (bi-ton 425+480 Hz, 1,5 s/3,5 s — stop déterministe au décroché/refus/timeout)"
            )
        }
    }

    /** Stoppe la tonalité (idempotent — déjà silencieuse = no-op). */
    fun stop() {
        synchronized(lock) {
            val t = thread ?: return
            thread = null
            stopped = true
            // Le thread s'arrête au morceau suivant (≤ 150 ms) — pas d'interrupt :
            // AudioTrack.write n'y répond pas proprement.
            net.nyx.messenger.diag.DiagLog.i("nyx-ring", "retour d'appel STOPPÉ")
        }
    }

    private const val RATE = 16_000
    private const val CHUNK_MS = 100

    private fun play() {
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                net.nyx.messenger.diag.DiagLog.w("nyx-ring", "retour d'appel IMPOSSIBLE (tampon invalide)")
                return
            }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, RATE / 10 * 2 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val tone = genDualTone(1_500)   // 1,5 s de tonalité
            val pause = ByteArray(RATE * 2 * 35 / 10) // 3,5 s de silence (16 kHz mono 16 bits)
            val chunk = RATE / 10 * 2       // 100 ms = 3 200 o
            while (!stopped) {
                writeChunks(track, tone, chunk)
                if (stopped) break
                writeChunks(track, pause, chunk)
            }
        } catch (e: Exception) {
            net.nyx.messenger.diag.DiagLog.w(
                "nyx-ring", "retour d'appel interrompu : ${e::class.simpleName} ${e.message?.take(60)}"
            )
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    private fun writeChunks(track: AudioTrack, data: ByteArray, chunk: Int) {
        var off = 0
        while (off < data.size && !stopped) {
            val len = minOf(chunk, data.size - off)
            track.write(data, off, len)
            off += len
        }
    }

    /** Bi-ton 425+480 Hz, volume doux (0,30), enveloppe 10 ms (pas de clic). */
    private fun genDualTone(ms: Int): ByteArray {
        val n = RATE * ms / 1000
        val out = ByteArray(n * 2)
        var i = 0
        while (i < n) {
            val t = i.toDouble() / RATE
            val edge = minOf(i, n - 1 - i, RATE / 100).toDouble()
            val env = if (edge >= RATE / 100.0) 1.0 else edge / (RATE / 100.0)
            val v = env * 0.30 * (
                kotlin.math.sin(2.0 * Math.PI * 425.0 * t) +
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
