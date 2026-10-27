package net.nyx.messenger.p2p

/**
 * v0.3.6 — PORTAGE MIRROIR de AacCsd Android v0.2.51 (objet pur, testé JVM).
 * Le bureau n'encode PAS en AAC, mais le csd-0 calculé part quand même dans
 * son HELLO : un appelé Android ≤ v0.2.53 (qui ignore le champ "ac") configure
 * ainsi son décodeur sur une valeur VALIDE au lieu d'un ASC cassé — l'appel
 * est refusé AVANT toute trame par le bureau (message de mise à jour), mais
 * jamais un crash du décodeur du téléphone.
 */
object AacCsd {

    /** Index de fréquence ISO 14496-3 table 4.66 — 96 kHz → 7 350 Hz. */
    private val FREQS_HZ = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000,
        24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350
    )

    /** AAC Low Complexity — le profile configuré par [CallAudio] Android. */
    private const val PROFILE_AAC_LC = 2

    /**
     * csd-0 (AudioSpecificConfig, 2 octets) pour AAC-LC.
     * [sampleRate] doit être dans la table ISO, [channels] dans 1..6.
     */
    fun aacLc(sampleRate: Int, channels: Int): ByteArray {
        val freqIdx = FREQS_HZ.indexOf(sampleRate)
        require(freqIdx >= 0) { "fréquence hors table ISO 14496-3 : $sampleRate Hz" }
        require(channels in 1..6) { "configuration de canaux hors bornes : $channels" }
        var bits = 0
        bits = (bits shl 5) or PROFILE_AAC_LC   // audioObjectType (5 bits)
        bits = (bits shl 4) or freqIdx          // samplingFrequencyIndex (4 bits)
        bits = (bits shl 4) or channels          // channelConfiguration (4 bits)
        bits = bits shl 3                        // bourrage → 16 bits exactement
        return byteArrayOf((bits shr 8).toByte(), bits.toByte())
    }

    /** Vrai si [csd] est manifestement indécodable (vide/tout-nul — le « csd factice » v0.2.50). */
    fun looksBroken(csd: ByteArray): Boolean =
        csd.size < 2 || csd.all { it.toInt() == 0 }

    /** csd à configurer côté DÉCODEUR : celui du pair s'il tient debout, sinon le csd local calculé. */
    fun sanitize(peerCsd: ByteArray, sampleRate: Int, channels: Int): ByteArray =
        if (looksBroken(peerCsd)) aacLc(sampleRate, channels) else peerCsd
}
