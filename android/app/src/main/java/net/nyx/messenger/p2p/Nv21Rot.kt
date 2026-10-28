package net.nyx.messenger.p2p

/**
 * v0.2.58 — ROTATION D'UN TAMPON NV21 À L'ENCODE (objet PUR, JVM-testable —
 * miroir desktop : la pré-rotation remplace la métadonnée de présentation).
 *
 * TERRAIN 28/10 (session 80) : « Pour les appels visio, toujours un problème
 * d'orientation de la video et de resolution qui devrait s'adapter à
 * l'orientation » — le téléphone captait TOUJOURS en paysage capteur
 * (320x240) et expédiait une MÉTADONNÉE de rotation (F_VIDEO_ROT) que la
 * VUE du pair devait appliquer : boîte d'aspect approximative, image
 * écrasée/letterboxée selon les pairs, et la RÉSOLUTION ne suivait jamais
 * l'orientation.
 *
 * CORRECTIF (négocié par ACCEPT « vpr=1 » — bureau ≥ v0.3.10 uniquement) :
 * en MJPEG, la trame est PRÉ-TOURNÉE à l'encodage —
 *   portrait  : 240x320 (vraie résolution portrait)
 *   paysage   : 320x240
 * et le JPEG porte son aspect LUI-MÊME (auto-descriptif) : plus aucune
 * rotation à la vue, plus de boîte approximative. Le H.264
 * téléphone↔téléphone reste OCTET POUR OCTET inchangé (métadonnée de
 * présentation historique).
 *
 * NV21 : plan Y (w*h) puis VU entrelacés (w/2 * h/2 paires de 2 octets).
 * Rotation HORAIRE de 90/180/270 — la chrominance suit la même formule sur
 * ses dimensions moitiées, octets V/U gardés dans l'ordre de leur paire.
 */
object Nv21Rot {

    /**
     * Rotation horaire d'un tampon NV21.
     *
     * @param nv21 tampon source (w*h octets Y + (w*h)/2 octets VU)
     * @param w largeur source (paire)
     * @param h hauteur source (paire)
     * @param deg rotation horaire : 90, 180 ou 270 (0 → copie)
     * @return NOUVEAU tampon NV21 tourné (dimensions permutées si 90/270)
     */
    fun rotate(nv21: ByteArray, w: Int, h: Int, deg: Int): Pair<ByteArray, IntArray> {
        require(w > 0 && h > 0 && w % 2 == 0 && h % 2 == 0) { "dimensions NV21 invalides : $w x $h" }
        val ySize = w * h
        require(nv21.size >= ySize + ySize / 2) { "tampon NV21 trop court : ${nv21.size} < $ySize + ${ySize / 2}" }
        val (nw, nh) = when (deg) {
            90, 270 -> h to w
            else -> w to h
        }
        val out = ByteArray(nv21.size)
        when (deg) {
            0 -> {
                System.arraycopy(nv21, 0, out, 0, nv21.size)
                return out to intArrayOf(w, h)
            }
            180 -> {
                // dst[y][x] = src[h-1-y][w-1-x] — Y puis VU (mêmes formules,
                // dimensions moitiées pour la chrominance).
                for (y in 0 until h) {
                    val srcRow = y * w
                    val dstRow = (h - 1 - y) * w
                    for (x in 0 until w) {
                        out[dstRow + (w - 1 - x)] = nv21[srcRow + x]
                    }
                }
                val cw = w / 2; val ch = h / 2
                val cOff = ySize
                for (y in 0 until ch) {
                    val srcRow = y * cw * 2
                    val dstRow = (ch - 1 - y) * cw * 2
                    for (x in 0 until cw) {
                        val s = srcRow + x * 2
                        val d = dstRow + (cw - 1 - x) * 2
                        out[cOff + d] = nv21[cOff + s]
                        out[cOff + d + 1] = nv21[cOff + s + 1]
                    }
                }
            }
            90 -> {
                // Horaire 90 : dst(x', y') = src(x, y) avec x' = h-1-y, y' = x
                // → dst[x * h + (h - 1 - y)] = src[y * w + x]
                for (y in 0 until h) {
                    val srcRow = y * w
                    for (x in 0 until w) {
                        out[x * h + (h - 1 - y)] = nv21[srcRow + x]
                    }
                }
                val cw = w / 2; val ch = h / 2
                val cOff = ySize
                for (y in 0 until ch) {
                    val srcRow = y * cw * 2
                    for (x in 0 until cw) {
                        val s = srcRow + x * 2
                        val d = x * ch + (ch - 1 - y)
                        out[cOff + d * 2] = nv21[cOff + s]
                        out[cOff + d * 2 + 1] = nv21[cOff + s + 1]
                    }
                }
            }
            270 -> {
                // Horaire 270 : x' = y, y' = w-1-x
                // → dst[(w - 1 - x) * h + y] = src[y * w + x]
                for (y in 0 until h) {
                    val srcRow = y * w
                    for (x in 0 until w) {
                        out[(w - 1 - x) * h + y] = nv21[srcRow + x]
                    }
                }
                val cw = w / 2; val ch = h / 2
                val cOff = ySize
                for (y in 0 until ch) {
                    val srcRow = y * cw * 2
                    for (x in 0 until cw) {
                        val s = srcRow + x * 2
                        val d = (cw - 1 - x) * ch + y
                        out[cOff + d * 2] = nv21[cOff + s]
                        out[cOff + d * 2 + 1] = nv21[cOff + s + 1]
                    }
                }
            }
            else -> throw IllegalArgumentException("rotation non supportée : $deg (0/90/180/270)")
        }
        return out to intArrayOf(nw, nh)
    }

    /**
     * Rotation qui rend l'image DROITE (celle de [VideoRot.uprightDegrees])
     * appliquée à un tampon : 0 = rien à faire (économie de copie).
     */
    fun rotateIfNeeded(nv21: ByteArray, w: Int, h: Int, uprightDeg: Int): Pair<ByteArray, IntArray> =
        if (uprightDeg % 360 == 0) nv21 to intArrayOf(w, h) else rotate(nv21, w, h, uprightDeg % 360)
}
