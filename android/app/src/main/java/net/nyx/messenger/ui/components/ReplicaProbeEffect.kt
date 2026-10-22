package net.nyx.messenger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import net.nyx.messenger.di.ServiceLocator

/**
 * Boucle de sonde réplica — UN SEUL emplacement du code (v0.2.5 ; l'audit
 * RED-DUP bloque toute duplication entre écrans, et la sonde existait en deux
 * copies divergentes : onboarding v0.2.3 + connexions v0.2.5).
 *
 * Comportement :
 *  - démarre quand [torReady] passe à vrai, et UNIQUEMENT sur ce seuil —
 *    clé sur l'état Tor complet = relance à chaque tick de pourcentage pendant
 *    le bootstrap, annulant la requête en vol avant tout résultat (bug des
 *    logs device v0.2.4 : sonde muette, gate jamais franchi) ;
 *  - sonde GET /health via Tor toutes les [intervalMs] jusqu'à joignabilité ;
 *  - le résultat détaillé (durée + cause d'échec) est observé par l'appelant
 *    via NyxRepository.lastProbe — ce composable ne garde AUCUN état ;
 *  - [attempt] : incrémenté par l'UI (« Réessayer ») pour court-circuiter
 *    l'attente courante.
 */
@Composable
fun ReplicaProbeEffect(
    torReady: Boolean,
    intervalMs: Long = 8_000L,
    attempt: Int = 0,
) {
    val repo = ServiceLocator.repository
    LaunchedEffect(torReady, attempt) {
        if (!torReady) return@LaunchedEffect
        if (repo.replicas.isEmpty()) return@LaunchedEffect
        while (true) {
            val ok = repo.replicaHealth()
            if (ok) break
            delay(intervalMs)
        }
    }
}
