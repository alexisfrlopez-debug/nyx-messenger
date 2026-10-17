package net.nyx.messenger.p2p

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * v0.2.18 — MOTEUR AUDIO D'APPEL (ADR-015 : audio direct onion-to-onion).
 *
 * Chaîne : AudioRecord (16 kHz mono PCM) → MediaCodec AAC-LC (24 kbps) →
 * trames F_AUDIO sur le socket P2P → MediaCodec AAC décode (csd-0 du pair) →
 * AudioTrack. Un thread par sens ; le mute coupe l'ÉMISSION (plus aucune
 * trame partie) ; le haut-parleur passe par AudioManager.
 *
 * Budget Tor : ~3 Ko/s par sens — très en dessous de la bande passante d'un
 * circuit onion ; la latence (0,5-3 s) reste le prix de l'anonymat (ADR-015).
 */
class CallAudio(
    private val input: java.io.InputStream,
    private val write: (type: Int, payload: ByteArray) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    @Volatile var muted = false

    /** v0.2.18 : l'audio ne part QU'après acceptation (pas d'écoute à l'insu). */
    @Volatile var transmit = false

    @Volatile var stopped = false
        private set

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private val threads = mutableListOf<Thread>()

    /**
     * Démarre l'ÉMISSION. Renvoie le csd-0 (config AAC) à transmettre au pair
     * dans l'ACCEPT/HELLO — son décodeur en a besoin avant la 1ʳᵉ trame.
     */
    fun startSending(): ByteArray {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc
        val csd = enc.outputFormat.getByteBuffer("csd-0")?.let { bb ->
            val b = ByteArray(bb.remaining()); bb.get(b); b
        } ?: ByteArray(2)

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 8192) * 2
        )
        record = rec
        val t = Thread({
            try {
                rec.startRecording()
                val pcm = ByteArray(PCM_FRAME)
                val bufferInfo = MediaCodec.BufferInfo()
                while (!stopped) {
                    val n = rec.read(pcm, 0, pcm.size)
                    if (n <= 0) continue
                    if (muted) continue // pas de trame émise = silence chez le pair
                    var inIdx = enc.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = enc.getInputBuffer(inIdx) ?: continue
                        ib.clear()
                        ib.put(pcm, 0, n)
                        enc.queueInputBuffer(inIdx, 0, n, System.nanoTime() / 1000, 0)
                    }
                    drainEncoder(enc, bufferInfo)
                }
            } catch (e: Exception) {
                if (!stopped) onFatal("micro : ${e.message}")
            }
        }, "nyx-call-enc")
        t.isDaemon = true
        t.start()
        threads.add(t)
        return csd
    }

    private fun drainEncoder(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIdx = enc.dequeueOutputBuffer(info, 0)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (outIdx >= 0 && info.size > 0) {
                val ob = enc.getOutputBuffer(outIdx) ?: continue
                val payload = ByteArray(info.size).also { ob.position(info.offset); ob.get(it) }
                if (transmit && !muted) write(P2pProtocol.F_AUDIO, payload)
                enc.releaseOutputBuffer(outIdx, false)
            } else if (outIdx < 0 && outIdx != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return
            }
        }
    }

    /** Démarre la RÉCEPTION (csd-0 du pair obligatoire). */
    fun startReceiving(peerCsd: ByteArray) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            if (peerCsd.isNotEmpty()) setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(peerCsd))
        }
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        dec.configure(format, null, null, 0)
        dec.start()
        decoder = dec

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val at = AudioTrack(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuf, 8192) * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track = at
        at.play()
        val t = Thread({
            try {
                val info = MediaCodec.BufferInfo()
                while (!stopped) {
                    val frame = P2pProtocol.read(input) ?: break
                    when (frame.type) {
                        P2pProtocol.F_AUDIO -> feedDecoder(dec, frame.payload, info)
                        P2pProtocol.F_BYE -> { onFatal("bye"); return@Thread }
                    }
                }
            } catch (e: Exception) {
                if (!stopped) onFatal("réception : ${e.message}")
            }
        }, "nyx-call-dec")
        t.isDaemon = true
        t.start()
        threads.add(t)
    }

    private fun feedDecoder(dec: MediaCodec, aac: ByteArray, info: MediaCodec.BufferInfo) {
        var inIdx = dec.dequeueInputBuffer(10_000)
        if (inIdx < 0) return
        val ib = dec.getInputBuffer(inIdx) ?: return
        ib.clear(); ib.put(aac)
        dec.queueInputBuffer(inIdx, 0, aac.size, System.nanoTime() / 1000, 0)
        while (true) {
            val outIdx = dec.dequeueOutputBuffer(info, 0)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (outIdx >= 0) {
                val track = this.track ?: return
                if (info.size > 0) {
                    val ob = dec.getOutputBuffer(outIdx)
                    if (ob != null) {
                        val pcm = ByteArray(info.size).also { ob.position(info.offset); ob.get(it) }
                        track.write(pcm, 0, pcm.size)
                    }
                }
                dec.releaseOutputBuffer(outIdx, false)
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // (le format de sortie est stable — rien à faire)
            } else return
        }
    }

    /** Haut-parleur mains libres (VOICE_CALL route par défaut = écouteur). */
    fun setSpeaker(context: android.content.Context, on: Boolean) {
        runCatching {
            val am = audioManager
                ?: (context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager)
                    .also { audioManager = it }
            am.isSpeakerphoneOn = on
        }
    }

    /** Arrêt complet (raccroché, erreur, écran détruit). */
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { audioManager?.isSpeakerphoneOn = false }
        record = null; track = null; encoder = null; decoder = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 24_000
        /** 20 ms de PCM 16 bits mono 16 kHz. */
        const val PCM_FRAME = 640
    }
}
