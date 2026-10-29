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
 *
 * v0.3.7 — ROUTAGE VIDÉO (miroir CallAudio Android v0.2.20) : la boucle de
 * lecture est le SEUL lecteur du socket — les trames [P2pProtocol.F_VIDEO]
 * (JPEG complets du téléphone, MJPEG interop) y sont routées vers
 * [videoSink], les [P2pProtocol.F_VIDEO_ROT] (rotation de l'écran du pair)
 * vers [rotSink].
 */
class DesktopCallAudio(
    private val input: InputStream,
    private val write: (type: Int, payload: ByteArray) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    @Volatile var muted = false

    /** v0.3.7 — trames F_VIDEO (JPEG complet MJPEG) → moteur vidéo. */
    @Volatile var videoSink: ((ByteArray) -> Unit)? = null

    /** v0.3.7 — trames F_VIDEO_ROT (rotation de l'écran du PAIR) → moteur vidéo. */
    @Volatile var rotSink: ((ByteArray) -> Unit)? = null

    /** v0.3.12 — trames de PARTAGE D'ÉCRAN VALIDÉ (F_SCREEN_REQ/ACCEPT/STOP)
     * → gestionnaire d'appel (dialogue ✓/✕, bascule, révocation). */
    @Volatile var screenSink: ((Int, ByteArray) -> Unit)? = null

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
                // v0.3.9 — MICRO HS ≠ APPEL MORT (miroir Android v0.2.48/0.2.56).
                // Terrain 28/10 11:46:57 (export v0.3.8) : « micro : Une
                // connexion établie a été abandonnée par un logiciel de votre
                // ordinateur hôte » TUAIT un appel sain après 11 s. Désormais
                // l'ÉMISSION est coupée proprement, la RÉCEPTION continue —
                // l'appel vit jusqu'au raccroché.
                if (!stopped) {
                    DesktopLog.w(
                        "nyx-call",
                        "micro perdu en cours d'appel : ${e.message?.take(140)} — " +
                            "émission coupée, réception maintenue (miroir micro HS Android)"
                    )
                    runCatching { micLine.stop() }
                    runCatching { micLine.close() }
                    mic = null
                }
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
        // v0.3.9 — var locale : haut-parleurs perdus en cours d'appel →
        // ligne fermée + référence annulée (écrits suivants = no-op).
        var speakerLine = try {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val line = AudioSystem.getLine(info) as SourceDataLine
            // v0.3.8 — LATENCE (session 78 : « les appels audio tel-pc ont un
            // delay trop long ») : 4 Ko (128 ms) au lieu de 8 Ko (256 ms) — le
            // chemin interop ADPCM est un appel TÉLÉPHONIQUE, pas une lecture.
            line.open(format, maxOf(Adpcm.PCM_BLOCK * 2, 4_096))
            line.start()
            line
        } catch (e: Exception) {
            DesktopLog.w("nyx-call", "haut-parleurs indisponibles : ${e.message?.take(80)} — réception muette")
            null
        }
        speaker = speakerLine
        // v0.3.8 — ANTI-ARRIÉRÉ (miroir Android v0.2.56) : la mise en service
        // du canal peut laisser DES SECONDES d'audio en file (TCP + tampon).
        // Chaque bloc écrit est comparé à l'HORLOGE RÉELLE : plus de 150 ms
        // d'avance sur le temps écoulé = arriéré → bloc SAUTÉ (20 ms de
        // rattrapage par bloc — on rejoint le direct, les rafales Tor en cours
        // d'appel sont résorbées pareil, sans trou audible).
        val startNs = System.nanoTime()
        var bytesWritten = 0L
        var skippedBlocks = 0
        var lastSkipLog = 0L
        val t = Thread({
            try {
                while (!stopped) {
                    val frame = P2pProtocol.read(input) ?: break
                    when (frame.type) {
                        P2pProtocol.F_AUDIO -> {
                            // Blocs ADPCM fermes — une trame cassée est
                            // ignorée (aucune dérive d'état, miroir résilience).
                            if (frame.payload.size == Adpcm.ADPCM_BLOCK) {
                                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                                val expectedBytes = elapsedMs * SAMPLE_RATE * 2 / 1000L
                                if (bytesWritten > expectedBytes + 4_800) {  // 150 ms d'avance = arriéré
                                    skippedBlocks++
                                    val now = System.currentTimeMillis()
                                    if (now - lastSkipLog > 5_000) {
                                        lastSkipLog = now
                                        DesktopLog.i(
                                            "nyx-call",
                                            "anti-arriéré : $skippedBlocks bloc(s) sauté(s) pour rejoindre le direct (latence)"
                                        )
                                    }
                                    continue
                                }
                                val pcm = Adpcm.decodeBlock(frame.payload)
                                // v0.3.9 — haut-parleurs perdus ≠ appel mort
                                // (miroir micro HS) : l'écriture peut échouer
                                // (périphérique débranché, autre appli audio)
                                // — réception muette, appel maintenu.
                                try {
                                    speakerLine?.write(pcm, 0, pcm.size)
                                } catch (sp: Exception) {
                                    val dead = speakerLine
                                    if (dead != null) {
                                        DesktopLog.w(
                                            "nyx-call",
                                            "haut-parleurs perdus : ${sp.message?.take(100)} — " +
                                                "réception muette, appel maintenu"
                                        )
                                        runCatching { dead.stop() }
                                        runCatching { dead.close() }
                                        speakerLine = null
                                        speaker = null
                                    }
                                }
                                bytesWritten += pcm.size
                            } else {
                                DesktopLog.w(
                                    "nyx-call",
                                    "trame audio ${frame.payload.size} o ≠ bloc ${Adpcm.ADPCM_BLOCK} — ignorée (pair AAC ? codec non négocié ?)"
                                )
                            }
                        }
                        P2pProtocol.F_BYE -> { onFatal("bye"); return@Thread }
                        P2pProtocol.F_VIDEO -> {
                            if (videoSink == null) {
                                // v0.3.14 — PLUS AUCUN SILENCE (défaut détecté
                                // 29/10 : une trame routée vers un sink absent
                                // disparaissait sans UNE LIGNE).
                                DesktopLog.w("nyx-call", "trame vidéo reçue SANS moteur vidéo — ignorée")
                            }
                            videoSink?.invoke(frame.payload)
                        }
                        P2pProtocol.F_VIDEO_ROT -> {
                            if (rotSink == null) DesktopLog.w("nyx-call", "trame rotation reçue SANS moteur — ignorée")
                            rotSink?.invoke(frame.payload)
                        }
                        P2pProtocol.F_SCREEN_REQ, P2pProtocol.F_SCREEN_ACCEPT,
                        P2pProtocol.F_SCREEN_STOP -> {
                            if (screenSink == null) {
                                // v0.3.14 — LE point de diagnostic du 29/10 : le
                                // F_SCREEN_REQ du tel a disparu ICI sans trace.
                                DesktopLog.w(
                                    "nyx-call",
                                    "trame partage 0x${frame.type.toString(16)} reçue SANS gestionnaire — ignorée (${frame.payload.size} o)"
                                )
                            }
                            screenSink?.invoke(frame.type, frame.payload)
                        }
                        else -> {
                            // v0.3.14 — TOUTE trame inconnue est journalisée (le
                            // when muet rendait une évolution de protocole
                            // INDIAGNOSTIQUABLE — jamais plus).
                            DesktopLog.w(
                                "nyx-call",
                                "trame P2P inconnue 0x${frame.type.toString(16)} (${frame.payload.size} o) — ignorée"
                            )
                        }
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
