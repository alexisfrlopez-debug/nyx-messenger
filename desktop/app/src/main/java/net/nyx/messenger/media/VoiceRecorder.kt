package net.nyx.messenger.media

import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * v0.3.4 « parité Android » — messages VOCaux bureau (miroir ChatViewModel
 * .startVoice/stopAndSendVoice Android v0.2.17) :
 *
 * Android enregistre en AAC/.m4a (MediaRecorder) ; le JVM n'a pas d'encodeur
 * AAC — le bureau enregistre en **WAV PCM 16 bits mono 44,1 kHz** (javax.sound,
 * zéro dépendance). INTEROPÉRABILITÉ : le message vocal part par le MÊME
 * pipeline de pièce jointe (ChunkCodec, mime audio), et le lecteur
 * multimédia Android lit nativement le WAV — l'équivalence fonctionnelle est
 * donc complète (icône 🎤, lecture, accusés). Les vocaux .m4a reçus DE
 * l'Android sont joués côté bureau par le lecteur système (« Ouvrir ») quand
 * javax.sound ne sait pas décoder le conteneur.
 *
 * Fil de capture dédié (jamais le thread UI) ; [Handle.stop] arrête la ligne,
 * attend la fin du fil et rend les octets WAV complets (en-tête RIFF inclus) ;
 * [Handle.durationMs] pour l'UI.
 */
object VoiceRecorder {

    private const val SAMPLE_RATE = 44_100
    private const val BITS = 16
    private const val CHANNELS = 1

    class Handle internal constructor(
        private val line: TargetDataLine,
        private val thread: Thread,
        private val buffer: ByteArrayOutputStream,
    ) {
        private var stopped = false

        /** Durée déjà capturée (mise à jour par la ligne audio elle-même). */
        val durationMs: Long get() = line.microsecondPosition / 1000

        /** Termine la capture et rend les octets WAV complets. */
        fun stop(): ByteArray {
            if (stopped) return ByteArray(0)
            stopped = true
            runCatching { line.stop() }
            runCatching { line.close() }   // fait sortir line.read() du fil
            runCatching { thread.join(2_000) }
            return wav(buffer.toByteArray(), SAMPLE_RATE, CHANNELS, BITS / 8)
        }
    }

    /** Démarre la capture (retourne null si aucun micro / ligne occupée). */
    fun start(): Handle? = runCatching {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), BITS, CHANNELS, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) return null
        val line = AudioSystem.getLine(info) as TargetDataLine
        line.open(format)
        line.start()
        val buffer = ByteArrayOutputStream()
        val thread = Thread({
            runCatching {
                val chunk = ByteArray(8_192)
                while (line.isOpen) {
                    val n = line.read(chunk, 0, chunk.size)
                    if (n > 0) buffer.write(chunk, 0, n) else break
                }
            }
        }, "nyx-voice-rec")
        thread.isDaemon = true
        thread.start()
        Handle(line, thread, buffer)
    }.getOrNull()

    /** Construit un fichier WAV complet (en-tête RIFF) à partir de PCM bruts. */
    fun wav(pcm: ByteArray, sampleRate: Int, channels: Int, bytesPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        le32(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        le32(16)
        le16(1) // PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(bytesPerSample * 8)
        out.write("data".toByteArray(Charsets.US_ASCII))
        le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
