package net.nyx.messenger.p2p

/**
 * v0.2.51 — CSD-0 AAC (AudioSpecificConfig) DÉTERMINISTE — cause racine du
 * « ça décroche mais l'appel coupe immédiatement » (terrain 25/10 04:58 :
 * 3 appels × 2 téléphones, 6/6 effondrés à 300-700 ms du « actif »).
 *
 * LA PANNE : CallAudio.startSending() lisait enc.outputFormat juste après
 * enc.start() pour en tirer le csd-0 à annoncer au pair (HELLO appelant /
 * F_CALL_ACCEPT appelé). Or les codecs C2 (Android 12+ — les deux téléphones
 * du terrain sont Android 15/16) n'y exposent le csd-0 qu'après le PREMIER
 * travail : la lecture immédiate rend null (preuve log Xiaomi 04:58:19.160 —
 * « start: outputFormat: AMessage » SANS AUCUN champ) et le repli
 * `ByteArray(2)` expédiait un « csd factice » {0x00,0x00} (profile 0 =
 * réservé, indécodable). Le décodeur du pair mourrait sur place
 * (« The csd Sample queue » → « work failed to complete: 14 » →
 * UNINITIALIZED) ; sa première trame F_AUDIO levait alors
 * IllegalStateException dans la boucle de lecture → onFatal → end() :
 * l'appel s'effondrait, « 0 frames delivered », SIX fois sur six — sans
 * que l'app meure (le raccroché v0.2.50 tient : aucun FATAL).
 *
 * LE FIX : l'AudioSpecificConfig est une fonction PURE du format configuré
 * (profile AAC-LC, fréquence, canaux) — on la CALCULE au lieu de la lire
 * dans une fenêtre de course. Vecteur de référence : AAC-LC 44100 stéréo =
 * {0x12,0x10} ; notre appel (16 kHz mono) = {0x14,0x08}. [sanitize] traite
 * en plus le csd factice des pairs encore en v0.2.50 (vide ou octets nuls →
 * csd local calculé) : la configuration audio étant FIXE des deux côtés
 * (AAC-LC 16 kHz mono — aucune négociation n'existe dans le protocole), le
 * csd calculé est TOUJOURS le bon — un appel v0.2.51 ↔ v0.2.50 tient aussi.
 *
 * Objet PUR (zéro dépendance Android) — testable JVM (AacCsdTest).
 */
object AacCsd {

    /** Index de fréquence ISO 14496-3 table 4.66 — 96 kHz → 7 350 Hz. */
    private val FREQS_HZ = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000,
        24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350
    )

    /** AAC Low Complexity — le profile configuré par [CallAudio]. */
    private const val PROFILE_AAC_LC = 2

    /**
     * csd-0 (AudioSpecificConfig, 2 octets) pour AAC-LC.
     * [sampleRate] doit être dans la table ISO, [channels] dans 1..6
     * (0 = « défini par PCE » : refusé — indécodable sans l'élément PCE).
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

    /**
     * Vrai si [csd] est manifestement indécodable : vide, trop court, ou
     * tout-nul (le « csd factice » v0.2.50 {0,0} — profile 0 n'existe pas ;
     * un ASC valide commence toujours par un profile ≥ 1, donc jamais nul).
     */
    fun looksBroken(csd: ByteArray): Boolean =
        csd.size < 2 || csd.all { it.toInt() == 0 }

    /**
     * csd à configurer côté DÉCODEUR : celui du pair s'il tient debout,
     * sinon le csd local calculé (interop pairs v0.2.50 — leur HELLO/ACCEPT
     * transporte le csd factice qui tuait notre décodeur).
     */
    fun sanitize(peerCsd: ByteArray, sampleRate: Int, channels: Int): ByteArray =
        if (looksBroken(peerCsd)) aacLc(sampleRate, channels) else peerCsd
}
