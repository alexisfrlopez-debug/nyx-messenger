package net.nyx.messenger.p2p

/**
 * v0.2.53 — ROTATION VIDÉO CAPTEUR → ÉCRAN — objet PUR (JVM-testable).
 *
 * Terrain 25/10 08:41 (export v0.2.52-test, Xiaomi) : « l'image video a une
 * orientation d'écran figée » — l'encodeur tournait à taille FIXE (320×240)
 * sans JAMAIS lire [android.hardware.camera2.CameraCharacteristics.
 * SENSOR_ORIENTATION] ni la rotation de l'écran : l'image partait en
 * orientation CAPTEUR (paysage « couché » sur un téléphone tenu en portrait)
 * et ne suivait PAS l'orientation du téléphone.
 *
 * RÈGLE (formule standard Camera2/CameraX — la rotation est une métadonnée de
 * PRÉSENTATION : le flux H.264 ne change pas, seule l'image affichée tourne,
 * d'où zéro coupure au changement d'orientation) :
 *  - caméra ARRIÈRE : rotation = (orientationCapteur − rotationÉcran + 360) % 360
 *  - caméra FRONT   : rotation = (orientationCapteur + rotationÉcran) % 360
 *
 * [Surface] et [CameraCharacteristics] sont interdits ici (constantes de
 * framework, non disponibles en JVM) : les entiers 0/1/2/3 de
 * Surface.ROTATION_* et 0/90/180/270 de SENSOR_ORIENTATION passent en clair.
 */
object VideoRot {

    /** android.view.Surface.ROTATION_* (0=portrait naturel, 1=90°, 2=180°, 3=270°). */
    const val SURFACE_ROTATION_0 = 0
    const val SURFACE_ROTATION_90 = 1
    const val SURFACE_ROTATION_180 = 2
    const val SURFACE_ROTATION_270 = 3

    /** Index Surface.ROTATION_* → degrés horaires (0/90/180/270). */
    fun displayRotationToDeg(rotation: Int): Int = when (rotation) {
        SURFACE_ROTATION_0 -> 0
        SURFACE_ROTATION_90 -> 90
        SURFACE_ROTATION_180 -> 180
        SURFACE_ROTATION_270 -> 270
        else -> throw IllegalArgumentException("rotation d'écran invalide : $rotation")
    }

    /**
     * Rotation horaire (0/90/180/270) qui rend l'image du capteur DROITE pour
     * un observateur de l'écran, sachant l'orientation du capteur et la
     * rotation COURANTE de l'écran.
     *
     * @param sensorOrientationDeg orientation du capteur (CameraCharacteristics.
     *        SENSOR_ORIENTATION : 0/90/180/270 — typiquement 90 derrière, 270 devant).
     * @param displayRotationDeg rotation de l'écran en degrés (0/90/180/270 —
     *        [displayRotationToDeg]).
     * @param front true = caméra frontale (le capteur front est « inversé » :
     *        la compensation s'AJOUTE au lieu de se soustraire).
     */
    fun uprightDegrees(sensorOrientationDeg: Int, displayRotationDeg: Int, front: Boolean): Int {
        require(sensorOrientationDeg % 90 == 0 && sensorOrientationDeg in 0..359) {
            "orientation capteur invalide : $sensorOrientationDeg"
        }
        require(displayRotationDeg % 90 == 0 && displayRotationDeg in 0..359) {
            "rotation écran invalide : $displayRotationDeg"
        }
        val deg = if (front) sensorOrientationDeg + displayRotationDeg
                  else sensorOrientationDeg - displayRotationDeg
        return ((deg % 360) + 360) % 360
    }

    /** Payload (ASCII) de la trame F_VIDEO_ROT — « 0 »/« 90 »/« 180 »/« 270 ». */
    fun encodeFrame(deg: Int): ByteArray = deg.toString().toByteArray(Charsets.US_ASCII)

    /** Décodage du payload F_VIDEO_ROT — null = illisible (ignoré sans erreur). */
    fun decodeFrame(payload: ByteArray): Int? {
        val s = runCatching { String(payload, Charsets.US_ASCII).trim() }.getOrNull() ?: return null
        return when (s) {
            "0", "90", "180", "270" -> s.toInt()
            else -> null
        }
    }
}
