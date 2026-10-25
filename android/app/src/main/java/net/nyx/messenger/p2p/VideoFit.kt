package net.nyx.messenger.p2p

/**
 * v0.2.53 — MATRICE D'AJUSTEMENT VIDÉO (rotation + fit centré + miroir) —
 * objet PUR (JVM-testable).
 *
 * Terrain 25/10 08:41 (v0.2.52) : l'image distante ET l'aperçu local
 * s'affichaient en orientation CAPTEUR (couchés à 90°) — aucune rotation
 * n'était appliquée nulle part. Le décodeur ne doit PAS porter la rotation
 * (support de KEY_ROTATION inégal selon les codecs, et un changement
 * d'orientation en cours d'appel exigerait un redémarrage du décodeur + une
 * resynchronisation IDR) : la rotation est portée par la VUE (TextureView),
 * qui change de matrice SANS AUCUNE COUPURE.
 *
 * [values] rend les 9 floats d'une matrice android.graphics.Matrix (ordre
 * [mscalex, mskewx, mtransx, mskewy, mscaley, mtransy, mpersp0, mpersp1,
 * mpersp2] — convention : x' = m[0]·x + m[1]·y + m[2] ; y' = m[3]·x + m[4]·y
 * + m[5]). L'application android (setTransform d'un TextureView) n'est
 * qu'un mince adaptateur — TOUTE la géométrie est ici, vérifiable en JVM.
 *
 * Sémantique : le buffer (bufW×bufH) est tourné de [rotationDeg] degrés
 * horaires PUIS ajusté (fit) centré dans la vue (viewW×viewH) en préservant
 * les proportions (letterbox implicite : les zones hors contenu restent
 * transparentes, le fond noir de l'overlay les remplit). [mirror] retoure
 * horizontalement le RÉSULTAT (aperçu front : l'utilisateur se voit comme
 * dans un miroir — le flux ENVOYÉ reste non miroir, standard des messageries).
 */
object VideoFit {

    /** Matrice 9 floats — voir l'en-tête d'objet pour la convention. */
    fun values(viewW: Int, viewH: Int, bufW: Int, bufH: Int, rotationDeg: Int, mirror: Boolean = false): FloatArray {
        require(viewW > 0 && viewH > 0) { "taille de vue invalide : ${viewW}x${viewH}" }
        require(bufW > 0 && bufH > 0) { "taille de buffer invalide : ${bufW}x${bufH}" }
        val r = when (rotationDeg) {
            0, 90, 180, 270 -> rotationDeg
            else -> throw IllegalArgumentException("rotation invalide : $rotationDeg")
        }
        // Contenu après rotation : (cw, ch) — 90/270 échangent les côtés.
        val cw = if (r % 180 == 90) bufH else bufW
        val ch = if (r % 180 == 90) bufW else bufH
        val s = minOf(viewW.toFloat() / cw, viewH.toFloat() / ch)
        val tx = (viewW - cw * s) / 2f
        val ty = (viewH - ch * s) / 2f
        // Rotation horaire autour de l'origine PUIS recadrage dans (0..cw, 0..ch)
        // PUIS mise à l'échelle + centrage — composée en une seule matrice.
        val m = when (r) {
            0   -> floatArrayOf(s, 0f, tx, 0f, s, ty, 0f, 0f, 1f)
            // 90° horaires : (x, y) → (bufH − y, x)
            90  -> floatArrayOf(0f, -s, bufH * s + tx, s, 0f, ty, 0f, 0f, 1f)
            // 180° : (x, y) → (bufW − x, bufH − y)
            180 -> floatArrayOf(-s, 0f, bufW * s + tx, 0f, -s, bufH * s + ty, 0f, 0f, 1f)
            // 270° horaires : (x, y) → (y, bufW − x)
            else -> floatArrayOf(0f, s, tx, -s, 0f, bufW * s + ty, 0f, 0f, 1f)
        }
        if (mirror) {
            // x'' = viewW − x' : symétrie verticale d'axe central de la VUE.
            m[0] = -m[0]; m[1] = -m[1]; m[2] = viewW.toFloat() - m[2]
        }
        return m
    }

    /** Applique la matrice à un point (testabilité JVM : coins du buffer). */
    fun map(m: FloatArray, x: Float, y: Float): Pair<Float, Float> = Pair(
        m[0] * x + m[1] * y + m[2],
        m[3] * x + m[4] * y + m[5]
    )

    /** Boîte englobante du contenu transformé (minX, minY, maxX, maxY) — tests. */
    fun bounds(m: FloatArray, bufW: Int, bufH: Int): FloatArray {
        val pts = listOf(
            map(m, 0f, 0f), map(m, bufW.toFloat(), 0f),
            map(m, 0f, bufH.toFloat()), map(m, bufW.toFloat(), bufH.toFloat())
        )
        val xs = pts.map { it.first }; val ys = pts.map { it.second }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }
}
