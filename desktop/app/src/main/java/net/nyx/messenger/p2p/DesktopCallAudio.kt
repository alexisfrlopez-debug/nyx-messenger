package net.nyx.messenger.p2p

import net.nyx.messenger.desktop.DesktopLog
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * v0.3.6 — MOTEUR AUDIO D'APPEL DESKTOP (portage de CallAudio Android v0.2.18,
 * ADR-015 : audio direct onion-to-onion).
 *
 * Chaîne : micro javax.sound (16 kHz mono PCM LE) → ADPCM IMA 4-bit (pur
 * Java — le bureau n'a pas de MediaCodec) → trames F_AUDIO sur le socket P2P
 * → ADPCM décode → haut-parleurs javax.sound. Un thread par sens ; le mute
 * coupe l'ÉMISSION (plus aucune trame partie).
 *
 * Budget Tor : ~8 Ko/s par sens (64 kbps ADPCM) — très en dessous de la bande
 * passante d'un circuit onion ; la latence (0,5-3 s) reste le prix de
 * l'anonymat (ADR-015).
 *
 * Le csd-0 AAC calculé ([AacCsd]) est TOUJOURS annoncé au pair (champ csd du
 * HELLO/ACCEPT) : un téléphone ≤ v0.2.53 configure un décodeur valide (jamais
 * de crash) — le champ "ac":"adpcm" fait basculer un téléphone ≥ v0.2.54 sur
 * le codec logiciel ADPCM miroir (p2p/Adpcm.kt de l'APK).
 */
class DesktopCallAudio(
    private val input: InputStream,
    private val write: (type: Int, payload: ByteArray) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    @Volatile var muted = false

    /** v0.2.18 miroir : l'audio ne part QU'après acceptation (pas d'écoute à l'insu). */
    @Volatile var transmit = false

    @Volatile var stopped = false
        private set

    private var mic: TargetDataLine? = null
    private var speaker: SourceDataLine? = null
    private val threads = mutableListOf<Thread>()

    /**
     * Démarre l'ÉMISSION (micro → ADPCM → F_AUDIO). Renvoie le csd-0 AAC
     * CALCULÉ à transmettre au pair dans l'ACCEPT/HELLO (compat vieux
     * téléphones — le champ ac="adpcm" négocie le vrai codec).
     * Micro indisponible (aucun périphérique/occupé) → RÉCEPTION SEULE
     * (le pair reste audible, notre émission est muette) — PAS de onFatal :
     * l'appel ne doit pas se couper pour ça (miroir Android v0.2.48).
     */
    fun startSending(): ByteArray {
        val format = AudioFormat(
            SAMPLE_RATE.toFloat(), 16, 1, true, false   // 16 bits, mono, signé, little-endian
        )
        val micLine = try {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                DesktopLog.w("nyx-call", "micro non supporté (aucune ligne d'entrée 16 kHz) — appel en RÉCEPTION SEULE")
                return AacCsd.aacLc(SAMPLE_RATE, 1)
            }
            val line = AudioSystem.getLine(info) as TargetDataLine
            line.open(format, maxOf(Adpcm.PCM_BLOCK * 4, line.bufferSize))
            line
        } catch (e: Exception) {
            DesktopLog.w("nyx-call", "micro indisponible : ${e.message?.take(80)} — appel en RÉCEPTION SEULE")
            return AacCsd.aacLc(SAMPLE_RATE, 1)
        }
        mic = micLine
        micLine.start()
        val t = Thread({
            try {
                val pcm = ByteArray(Adpcm.PCM_BLOCK)
                while (!stopped) {
                    var n = 0
                    while (n < pcm.size && !stopped) {
                        val r = micLine.read(pcm, n, pcm.size - n)
                        if (r <= 0) break
                        n += r
                    }
                    if (n < pcm.size) continue        // ligne fermée/vidée
                    if (muted || !transmit) continue   // pas de trame émise = silence chez le pair
                    write(P2pProtocol.F_AUDIO, Adpcm.encodeBlock(pcm))
                }
            } catch (e: Exception) {
                if (!stopped) onFatal("micro : ${e.message}")
            }
        }, "nyx-call-enc")
        t.isDaemon = true
        t.start()
        threads.add(t)
        return AacCsd.aacLc(SAMPLE_RATE, 1)
    }

    /**
     * Démarre la RÉCEPTION (F_AUDIO → ADPCM décode → haut-parleurs).
     * v0.3.6 : [peerAc] — codec annoncé par le pair ("adpcm" = bureau ;
     * ""/"aac" = téléphone ≤ v0.2.53 → l'appel a déjà été refusé en amont,
     * cette méthode n'est appelée que pour "adpcm").
     */
    fun startReceiving(peerAc: String) {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val speakerLine = try {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val line = AudioSystem.getLine(info) as SourceDataLine
            line.open(format, maxOf(Adpcm.PCM_BLOCK * 8, 8192))
            line.start()
            line
        } catch (e: Exception) {
            DesktopLog.w("nyx-call", "haut-parleurs indisponibles : ${e.message?.take(80)} — réception muette")
            null
        }
        speaker = speakerLine
        val t = Thread({
            try {
                while (!stopped) {
                    val frame = P2pProtocol.read(input) ?: break
                    when (frame.type) {
                        P2pProtocol.F_AUDIO -> {
                            // Blocs ADPCM fermes — une trame cassée est
                            // ignorée (aucune dérive d'état, miroir résilience).
                            if (frame.payload.size == Adpcm.ADPCM_BLOCK) {
                                speakerLine?.write(Adpcm.decodeBlock(frame.payload), 0, Adpcm.PCM_BLOCK)
                            } else {
                                DesktopLog.w(
                                    "nyx-call",
                                    "trame audio ${frame.payload.size} o ≠ bloc ${Adpcm.ADPCM_BLOCK} — ignorée (pair AAC ? codec non négocié ?)"
                                )
                            }
                        }
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

    /** Arrêt complet (raccroché, erreur, écran détruit). */
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { mic?.stop() }
        runCatching { mic?.close() }
        runCatching { mic?.flush() }
        runCatching { speaker?.stop() }
        runCatching { speaker?.close() }
        mic = null
        speaker = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000   // miroir Android CallAudio.SAMPLE_RATE
    }
}
