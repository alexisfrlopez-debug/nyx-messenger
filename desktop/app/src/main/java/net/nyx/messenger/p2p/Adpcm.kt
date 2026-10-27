package net.nyx.messenger.p2p

/**
 * v0.3.6 — CODEC AUDIO ADPCM IMA 4-BIT (objet pur, testé JVM — le MÊME code
 * est porté en miroir dans l'APK Android v0.2.54 : p2p/Adpcm.kt).
 *
 * POURQUOI : l'Android encode l'audio d'appel en AAC via MediaCodec (matériel
 * — ADR-015) ; la JVM du bureau n'a NI encodeur NI décodeur AAC sans binaire
 * natif. Les deux clients portent donc un codec logiciel commun :
 *  - 16 kHz mono 16 bits (PCM d'entrée identique à la chaîne Android) ;
 *  - IMA ADPCM 4 bit/sample : 640 octets PCM (20 ms) → 320 octets (64 kbps) ;
 *  - pure Java ~40 lignes par sens — zéro dépendance, testable roundtrip ;
 *  - budget Tor ≈ 8 Ko/s par sens (AAC = 3 Ko/s — tous deux très en dessous
 *    de la capacité d'un circuit onion ; la latence reste le prix de
 *    l'anonymat, ADR-015).
 *
 * FILAIRE : chaque trame F_AUDIO (codec "adpcm") emporte des BLOCS de 20 ms
 * codés [prédicteur s16 LE][index u8][réservé u8][4 nibbles par octet]. Un
 * état (prédicteur+index) FIRME CHAQUE BLOC → aucune dépendance entre trames
 * (une trame perdue ne corrompt jamais les suivantes — résilience Tor).
 *
 * Tables IMA officielles (RFC 1896 / spéc. IMA/DVI — valeurs publiques).
 */
object Adpcm {

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
        34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598,
        658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749,
        3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7847, 8632, 9493, 10442, 11487, 12635,
        13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8,
    )

    /** Taille d'un bloc PCM d'entrée (20 ms @ 16 kHz mono 16 bits — miroir PCM_FRAME Android). */
    const val PCM_BLOCK = 640

    /** Taille d'un bloc codé : en-tête 4 o + 640/2 nibbles = 324 octets. */
    const val ADPCM_BLOCK = 4 + PCM_BLOCK / 2

    /** v0.3.6 — INDEX INITIAL PAR BLOC (pas 598) : blocs autonomes résilients, format filaire miroir APK. */
    const val INITIAL_INDEX = 40

    /** État de codage FIRME par bloc (résilience : aucune dépendance inter-trames). */
    private fun clampIndex(i: Int) = i.coerceIn(0, STEP_TABLE.size - 1)

    /**
     * Encode UN bloc PCM (640 octets = 320 échantillons 16 bits mono LE) en
     * ADPCM IMA (324 octets). [pcm] de taille différente → IllegalArgumentException.
     */
    fun encodeBlock(pcm: ByteArray): ByteArray {
        require(pcm.size == PCM_BLOCK) { "bloc PCM attendu : $PCM_BLOCK octets (reçu ${pcm.size})" }
        val out = ByteArray(ADPCM_BLOCK)
        // Prédicteur initial = premier échantillon (l'erreur du 1er nibble est nulle).
        val predictor = ((pcm[1].toInt() and 0xFF) shl 8) or (pcm[0].toInt() and 0xFF)
        var pred = if (predictor >= 0x8000) predictor - 0x10000 else predictor
        // INDEX INITIAL = 40 (pas 598 — FORMAT FILAIRE, miroir APK) : les blocs
        // sont AUTONOMES (résilience Tor — une trame perdue ne corrompt rien),
        // donc l'état ne peut pas être hérité du bloc précédent. Démarrer à 0
        // (pas 7) quantifiait médiocrement les transitoires vocaux en tête de
        // bloc ; 40 couvre les écarts vocaux typiques (±1100) en un pas et
        // s'adapte en 1-2 échantillons au reste.
        var index = INITIAL_INDEX
        out[0] = (pred and 0xFF).toByte()
        out[1] = ((pred shr 8) and 0xFF).toByte()
        out[2] = index.toByte()
        out[3] = 0
        var outPos = 4
        var nibbleHigh = true
        for (i in 2 until pcm.size step 2) {
            val raw = ((pcm[i + 1].toInt() and 0xFF) shl 8) or (pcm[i].toInt() and 0xFF)
            val sample = if (raw >= 0x8000) raw - 0x10000 else raw
            // Diff → nibble (signe + magnitude via recherche du pas).
            var diff = sample - pred
            var nibble = 0
            if (diff < 0) { nibble = 8; diff = -diff }
            var step = STEP_TABLE[index]
            if (diff >= step) { nibble = nibble or 4; diff -= step }
            step = step shr 1
            if (diff >= step) { nibble = nibble or 2; diff -= step }
            step = step shr 1
            if (diff >= step) { nibble = nibble or 1 }
            // Reconstruction locale (le décodeur fait exactement ceci).
            pred += decodeStep(nibble, index)
            pred = pred.coerceIn(-32768, 32767)
            index = clampIndex(index + INDEX_TABLE[nibble])
            // Paire de nibbles par octet de sortie.
            if (nibbleHigh) {
                out[outPos] = ((nibble shl 4)).toByte(); nibbleHigh = false
            } else {
                out[outPos] = (out[outPos].toInt() or nibble).toByte(); outPos++; nibbleHigh = true
            }
        }
        return out
    }

    /** Pas de reconstruction d'un nibble (miroir du décodeur). */
    private fun decodeStep(nibble: Int, index: Int): Int {
        var diff = 0
        val step = STEP_TABLE[index]
        if (nibble and 4 != 0) diff += step
        if (nibble and 2 != 0) diff += step shr 1
        if (nibble and 1 != 0) diff += step shr 2
        diff += step shr 3
        return if (nibble and 8 != 0) -diff else diff
    }

    /**
     * Décode UN bloc ADPCM (324 octets) en PCM (640 octets LE).
     * Bloc de taille inattendue → IllegalArgumentException (la trame est
     * ABANDONNÉE par l'appelant — jamais de dérive d'état).
     */
    fun decodeBlock(block: ByteArray): ByteArray {
        require(block.size == ADPCM_BLOCK) { "bloc ADPCM attendu : $ADPCM_BLOCK octets (reçu ${block.size})" }
        val out = ByteArray(PCM_BLOCK)
        var pred = ((block[1].toInt() and 0xFF) shl 8) or (block[0].toInt() and 0xFF)
        if (pred >= 0x8000) pred -= 0x10000
        var index = (block[2].toInt() and 0xFF).coerceAtMost(STEP_TABLE.size - 1)
        out[0] = (pred and 0xFF).toByte()
        out[1] = ((pred shr 8) and 0xFF).toByte()
        var outPos = 2
        for (i in 4 until block.size) {
            val b = block[i].toInt() and 0xFF
            // Nibble haut puis bas.
            for (shift in intArrayOf(4, 0)) {
                val nibble = (b shr shift) and 0x0F
                pred += decodeStep(nibble, index)
                pred = pred.coerceIn(-32768, 32767)
                index = clampIndex(index + INDEX_TABLE[nibble])
                out[outPos] = (pred and 0xFF).toByte()
                out[outPos + 1] = ((pred shr 8) and 0xFF).toByte()
                outPos += 2
                if (outPos >= PCM_BLOCK) return out
            }
        }
        return out
    }

    /** Flux PCM complet → liste de blocs ADPCM (taille multiple de 640 ; la fin non multiple est IGNORÉE). */
    fun encodeStream(pcm: ByteArray): List<ByteArray> {
        val blocks = ArrayList<ByteArray>(pcm.size / PCM_BLOCK)
        var i = 0
        while (i + PCM_BLOCK <= pcm.size) {
            blocks.add(encodeBlock(pcm.copyOfRange(i, i + PCM_BLOCK)))
            i += PCM_BLOCK
        }
        return blocks
    }

    /** Flux de blocs ADPCM → PCM concaténé (l'appelant tolère les blocs cassés). */
    fun decodeStream(blocks: List<ByteArray>): ByteArray {
        val out = ByteArray(blocks.size * PCM_BLOCK)
        var pos = 0
        for (b in blocks) {
            if (b.size != ADPCM_BLOCK) continue
            val pcm = decodeBlock(b)
            System.arraycopy(pcm, 0, out, pos, pcm.size)
            pos += pcm.size
        }
        return out.copyOf(pos)
    }
}
