package net.nyx.messenger.p2p

/**
 * v0.2.52 — CSD H.264 (SPS+PPS) : extraction et validation — objet PUR.
 *
 * Terrain 25/10 : « visio annoncée dans le HELLO (csd 0 o) » — l'encodeur à
 * surface d'entrée n'expose le csd qu'APRÈS la première trame caméra (même
 * classe de bug que le csd-0 AAC v0.2.51, côté vidéo) : la lecture de
 * outputFormat avant toute trame rendait un csd VIDE, l'appelé voyait
 * vcsd vide et dégradait l'appel en audio-only.
 *
 * [FLAG_CODEC_CONFIG] reflète android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG
 * (= 2) : constantes framework interdites ici pour rester testable JVM.
 */
object VideoCsd {

    /** android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG (= 2). */
    const val FLAG_CODEC_CONFIG = 2

    /** SPS+PPS concaténés depuis les csd-0/csd-1 d'un outputFormat (null = absent). */
    fun fromFormat(sps: ByteArray?, pps: ByteArray?): ByteArray =
        (sps ?: ByteArray(0)) + (pps ?: ByteArray(0))

    /**
     * Un csd H.264 est-il exploitable ? Un SPS+PPS commence TOUJOURS par un
     * start code (00 00 01 ou 00 00 00 01) et fait au moins ~8 octets
     * (SPS ≥ 4 o + PPS ≥ 3 o + start codes). Le « csd 0 o » du terrain 25/10
     * — comme un {0,0} — est invalide ici.
     */
    fun looksValid(csd: ByteArray): Boolean {
        if (csd.size < 8) return false
        val s3 = csd.size >= 3 && csd[0] == 0.toByte() && csd[1] == 0.toByte() && csd[2] == 1.toByte()
        val s4 = csd.size >= 4 && csd[0] == 0.toByte() && csd[1] == 0.toByte() &&
            csd[2] == 0.toByte() && csd[3] == 1.toByte()
        return s3 || s4
    }

    /** Ce buffer de sortie est-il la CONFIG (SPS/PPS) et non une trame ? */
    fun isCodecConfig(flags: Int): Boolean = (flags and FLAG_CODEC_CONFIG) != 0
}
