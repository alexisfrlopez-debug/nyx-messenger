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

    /**
     * v0.2.20 — ROUTAGE des trames vidéo : la boucle de lecture ci-dessous est
     * le SEUL lecteur du socket — les trames [P2pProtocol.F_VIDEO] y sont
     * routées vers le décodeur [CallVideo] quand une visio est active.
     */
    @Volatile var videoSink: ((ByteArray) -> Unit)? = null

    /** v0.2.53 — trames F_VIDEO_ROT (rotation de l'écran du PAIR) :
     *  métadonnée de présentation routée vers l'état UI (matrice de la vue
     *  distante) — le flux et le décodeur ne bougent pas. */
    @Volatile var rotSink: ((ByteArray) -> Unit)? = null

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
        // v0.2.48 — PERMISSION MICRO AVANT TOUT (terrain 24/10 08:49 : AudioRecord
        // « AudioFlinger could not create record track, status: -1 » / code -20
        // SANS AUCUN message applicatif — l'app ne demandait JAMAIS
        // RECORD_AUDIO à l'exécution : les appels n'avaient jamais dépassé la
        // sonnerie, le trou était invisible). Refusée → l'appel CONTINUE en
        // réception seule (le pair reste audible, notre émission est muette)
        // avec un csd factice — PAS de onFatal : l'appel ne doit pas se couper
        // pour ça. L'UI demande la permission au clic Appeler (ChatScreen).
        val ctx = runCatching {
            net.nyx.messenger.di.ServiceLocator.appContext()
        }.getOrNull()
        if (ctx != null && androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            net.nyx.messenger.diag.DiagLog.w(
                "nyx-call",
                "micro REFUSÉ : permission RECORD_AUDIO non accordée — appel en RÉCEPTION " +
                    "SEULE (le pair est audible, vous n'êtes pas entendu) ; accordez le " +
                    "microphone (Paramètres → Applications → Nyx → Microphone) et rappelez"
            )
            // v0.2.51 — le « csd factice » {0,0} TUAIT le décodeur du pair
            // (profile 0 indécodable — terrain 25/10 : 6/6 appels coupés
            // 300-700 ms après « actif »). Sans émission le pair ne reçoit
            // AUCUNE trame : il décode du VRAI silence avec le csd CALCULÉ.
            return AacCsd.aacLc(SAMPLE_RATE, 1)
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc
        // v0.2.51 — CSD-0 DÉTERMINISTE (terrain 25/10 04:58 : « ça décroche
        // mais l'appel coupe immédiatement », 6/6) : les codecs C2
        // (Android 12+ — téléphones du terrain en 15/16) n'exposent le
        // csd-0 dans outputFormat qu'après le PREMIER travail ; lu
        // immédiatement il rendait null (preuve log : « start:
        // outputFormat: AMessage » VIDE) → repli « csd factice » {0,0} →
        // le DÉCODEUR DU PAIR mourrait au démarrage (« The csd Sample
        // queue » → « work failed to complete: 14 » → UNINITIALIZED) → sa
        // 1ʳᵉ trame F_AUDIO levait IllegalStateException → onFatal →
        // appel effondré, « 0 frames delivered ». L'AudioSpecificConfig
        // est une fonction PURE du format configuré : on la CALCULE — la
        // lecture du codec n'est plus qu'un témoin de traçabilité.
        val exposed = enc.outputFormat.getByteBuffer("csd-0")?.let { bb ->
            val b = ByteArray(bb.remaining()); bb.get(b); b
        }
        val csd = if (exposed != null && !AacCsd.looksBroken(exposed)) exposed
        else AacCsd.aacLc(SAMPLE_RATE, 1).also {
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-call",
                "csd-0 AAC CALCULÉ (" + it.joinToString("") { b ->
                    "%02x".format(b.toInt() and 0xFF) } + ") — le codec ne " +
                    "l'expose pas encore au démarrage (C2 : " +
                    (if (exposed == null) "null" else "invalide") + ") : " +
                    "plus de csd factice qui tue le décodeur du pair"
            )
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 8192) * 2
        )
        // v0.2.48 — L'ÉCHEC D'INITIALISATION EST EXPLICITE (terrain : le
        // constructeur ne lève PAS — il rend un objet UNINITIALIZED, mort à
        // startRecording ; sans ça, aucune trace applicative du micro HS).
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            net.nyx.messenger.diag.DiagLog.w(
                "nyx-call",
                "micro INUTILISABLE : AudioRecord non initialisé (state=${rec.state}) — " +
                    "micro occupé par une autre application, ou permission — appel en réception seule"
            )
            return AacCsd.aacLc(SAMPLE_RATE, 1)   // réception seule, pas de onFatal
        }
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
        // v0.2.51 — csd du pair ASSAINI : un pair v0.2.50 expédie le « csd
        // factice » {0,0} qui tuait NOTRE décodeur au démarrage (terrain
        // 25/10 : « work failed to complete: 14 » → UNINITIALIZED → appel
        // effondré à la 1ʳᵉ trame, 6/6). La config audio est FIXE des deux
        // côtés (AAC-LC 16 kHz mono — aucune négociation dans le protocole)
        // : le csd local calculé est toujours le bon → v0.2.51 ↔ v0.2.50.
        val csd = if (AacCsd.looksBroken(peerCsd)) {
            AacCsd.aacLc(SAMPLE_RATE, 1).also {
                net.nyx.messenger.diag.DiagLog.w(
                    "nyx-call",
                    "csd-0 pair INVALIDE (" + peerCsd.size + " o) — remplacé par " +
                        "le calcul local (" + it.joinToString("") { b ->
                            "%02x".format(b.toInt() and 0xFF) } + ") : pair " +
                        "v0.2.50 ? décodeur sauvé, appel maintenu"
                )
            }
        } else peerCsd
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
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
                        P2pProtocol.F_VIDEO -> videoSink?.invoke(frame.payload)
                        P2pProtocol.F_VIDEO_ROT -> rotSink?.invoke(frame.payload)
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
