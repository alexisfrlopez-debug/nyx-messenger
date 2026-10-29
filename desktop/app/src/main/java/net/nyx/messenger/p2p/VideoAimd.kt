package net.nyx.messenger.p2p

/**
 * v0.3.15 — QUALITÉ VIDÉO ADAPTATIVE AU DÉBIT — objet PUR (JVM-testable,
 * convention miroir Android MjpegAimd v0.2.63).
 *
 * Demande opérateur 29/10, verbatim : « je veux que la qualité du flux video
 * s'adapte au débit de connexion entre les 2 messengers connectés ».
 *
 * L'ÉMETTEUR mesure SON propre write() sur le circuit Tor. Un write lent =
 * circuit encombré → la qualité JPEG DESCEND d'un cran ; un flux soutenu de
 * writes rapides → elle REMONTE. Hystérésis asymétrique anti-pompage : une
 * seule trame lente pour descendre, 24 trames saines pour monter, JAMAIS
 * au-dessus du sommet (q0.55 — la base historique).
 *
 * Le frame skipping v0.3.13 (CADENCE) et la recompression budget (POIDS)
 * restent en place : cadence, budget et qualité se complètent.
 * AUCUN changement protocolaire : le JPEG reste auto-descriptif.
 */
object VideoAimd {

    /** Échelle de qualité croissante — le sommet (0.55) est le MAX historique. */
    val LEVELS = floatArrayOf(0.30f, 0.40f, 0.55f)

    /** Au-delà, le write est jugé LENT (circuit encombré) → descente. */
    const val SLOW_WRITE_MS = 500L

    /** En deçà, le write est jugé RAPIDE (il nourrit la remontée). */
    const val FAST_WRITE_MS = 250L

    /** Writes rapides CONSÉCUTIFS exigés pour remonter d'un cran. */
    const val FAST_STREAK_TO_CLIMB = 24

    /** État courant : indice dans [LEVELS] + série de writes rapides. */
    data class State(
        val idx: Int = LEVELS.size - 1,
        val fastStreak: Int = 0,
    ) {
        val quality: Float get() = LEVELS[idx]
        val atTop: Boolean get() = idx >= LEVELS.size - 1
        val atFloor: Boolean get() = idx <= 0
    }

    /** Transitions pures : appliquées au fil des writes (aucun IO ici). */
    fun onWrite(s: State, writeMs: Long): State {
        return when {
            writeMs > SLOW_WRITE_MS ->
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
