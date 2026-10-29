package net.nyx.messenger.p2p

/**
 * v0.2.63 — QUALITÉ MJPEG ADAPTATIVE AU DÉBIT — objet PUR (JVM-testable,
 * convention VideoFit/Nv21Rot/VideoCsd).
 *
 * Demande opérateur 29/10, verbatim : « je veux que la qualité du flux video
 * s'adapte au débit de connexion entre les 2 messengers connectés ».
 *
 * Principe (miroir bureau v0.3.15 VideoAimd) : l'ÉMETTEUR mesure SON propre
 * write() sur le circuit Tor — c'est LUI le goulot de son sens. Un write
 * lent ou une rafale de trames jetées (busy) = circuit encombré → la
 * qualité JPEG DESCEND d'un cran ; un flux soutenu de writes rapides → elle
 * REMONTE. Hystérésis asymétrique anti-pompage : 1 seule trame lente pour
 * descendre, 24 trames saines pour monter, et JAMAIS au-dessus du sommet
 * (q55 — la base historique).
 *
 * AUCUN changement protocolaire : le JPEG reste auto-descriptif, la résolution
 * et l'orientation ne bougent pas — seul le facteur de qualité varie.
 */
object MjpegAimd {

    /** Échelle de qualité croissante — le sommet (q55) est le MAX historique. */
    val LEVELS = intArrayOf(30, 38, 46, 55)

    /** Au-delà, le write est jugé LENT (circuit encombré) → descente. */
    const val SLOW_WRITE_MS = 500L

    /** En deçà, le write est jugé RAPIDE (il nourrit la remontée). */
    const val FAST_WRITE_MS = 250L

    /** Writes rapides CONSÉCUTIFS exigés pour remonter d'un cran. */
    const val FAST_STREAK_TO_CLIMB = 24

    /** Trames jetées (busy) sur UNE trame envoyée → descente immédiate. */
    const val DROP_BURST_TO_FALL = 3

    /** État courant : indice dans [LEVELS] + série de writes rapides. */
    data class State(
        val idx: Int = LEVELS.size - 1,
        val fastStreak: Int = 0,
    ) {
        val quality: Int get() = LEVELS[idx]
        val atTop: Boolean get() = idx >= LEVELS.size - 1
        val atFloor: Boolean get() = idx <= 0
    }

    /** Transitions pures : appliquées au fil des writes (aucun IO ici). */
    fun onWrite(s: State, writeMs: Long, dropsSinceLast: Int): State {
        return when {
            writeMs > SLOW_WRITE_MS || dropsSinceLast >= DROP_BURST_TO_FALL ->
                if (s.idx > 0) State(s.idx - 1, 0) else State(0, 0)
            writeMs <= FAST_WRITE_MS -> {
                val streak = s.fastStreak + 1
                if (streak >= FAST_STREAK_TO_CLIMB && s.idx < LEVELS.size - 1) {
                    State(s.idx + 1, 0)
                } else {
                    State(s.idx, streak)
                }
            }
            else -> State(s.idx, 0)
        }
    }
}
