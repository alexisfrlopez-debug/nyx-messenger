package net.nyx.messenger.ui.connexions

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.nyx.messenger.R
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.tor.TorManager
import net.nyx.messenger.ui.components.ScreenColumn
import net.nyx.messenger.ui.diag.TestLogExportCard
import net.nyx.messenger.ui.components.MoonHeader

/**
 * ÉCRAN CONNEXIONS — PREMIÈRE PAGE affichée au lancement (v0.2.5, demande
 * opérateur) : rien d'autre n'est accessible tant que les deux étapes ne sont
 * pas validées.
 *
 * Séquence, avec barre de progression :
 *   Étape 1 — Tor embarqué : bootstrap du démon (0 → 100 %) ;
 *   Étape 2 — Réplica d'amorçage : sonde GET /health via Tor (répétée toutes
 *             les 8 s jusqu'à joignabilité, détail d'erreur affiché).
 *
 * Une fois les DEUX validées, le reste de l'app s'affiche automatiquement
 * (onboarding si aucune identité, conversations sinon) — [onConnected].
 *
 * Build « test » : le gros bouton rouge d'export de logs est présent SUR CETTE
 * PAGE — c'est précisément quand une étape bloque (Tor à 0 %, réplica muet…)
 * qu'il faut pouvoir déposer les journaux dans le dossier Drive de travail.
 *
 * Diagnostic v0.2.4 (logs device) : la sonde de l'onboarding était relancée à
 * chaque tick de pourcentage Tor (LaunchedEffect(torState)) et la requête en
 * vol était annulée avant tout résultat — ici la sonde ne dépend QUE du seuil
 * Ready/pas-Ready (clé [torReady]).
 */
@Composable
fun ConnexionsScreen(onConnected: () -> Unit) {
    val context = LocalContext.current
    val torMgr = TorManager.get(context)
    val torState by torMgr.state.collectAsState()
    val repo = ServiceLocator.repository
    val probe by repo.lastProbe.collectAsState()

    // Horodatage du changement d'état Tor (durée affichée « depuis X s ») +
    // horloge 1 s pour rafraîchir les durées à l'écran.
    var phaseSince by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(torState) { phaseSince = SystemClock.elapsedRealtime() }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }

    // Étape 1 : Tor démarre dès l'arrivée sur l'écran (idempotent côté service).
    LaunchedEffect(Unit) { torMgr.start() }
    val torReady = torState is TorManager.TorState.Ready

    // Étape 2 : sonde réplica — boucle partagée (ui/components), relancée par
    // [probeAttempt] via le bouton « Réessayer » ; résultat détaillé = repo.lastProbe.
    var probeAttempt by remember { mutableStateOf(0) }
    net.nyx.messenger.ui.components.ReplicaProbeEffect(
        torReady = torReady,
        attempt = probeAttempt
    )

    // Auto-avance : les DEUX étapes validées → reste de l'app (petit délai
    // pour que l'utilisateur voie le vert avant la transition).
    LaunchedEffect(torReady, probe?.ok) {
        if (torReady && probe?.ok == true) {
            delay(900)
            onConnected()
        }
    }

    // ---- Progression globale 0..1 : Tor = moitié gauche, réplica = moitié droite.
    val torPct = when (val st = torState) {
        is TorManager.TorState.Ready -> 50
        is TorManager.TorState.Starting -> (st.percent / 2).coerceIn(0, 49)
        else -> 0
    }
    val repPct = when {
        probe?.ok == true -> 50
        torReady -> 10
        else -> 0
    }
    val overall = (torPct + repPct) / 100f

    val elapsedS = ((SystemClock.elapsedRealtime() - phaseSince) / 1000).coerceAtLeast(0)
    @Suppress("UNUSED_EXPRESSION")
    tick // recompute on tick

    ScreenColumn {
        MoonHeader()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.connexions_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.connexions_intro), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))

        LinearProgressIndicator(
            progress = { overall },
            modifier = Modifier.fillMaxWidth().height(10.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                probe?.ok == true -> stringResource(R.string.connexions_step_done)
                torReady -> stringResource(R.string.connexions_step_replica)
                else -> stringResource(R.string.connexions_step_tor)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        // ---------- Étape 1 — Tor ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.connexions_tor_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    when (val st = torState) {
                        is TorManager.TorState.Ready -> Text(
                            stringResource(R.string.connexions_ok),
                            color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold
                        )
                        is TorManager.TorState.Starting -> Text(
                            stringResource(R.string.connexions_percent, st.percent),
                            fontWeight = FontWeight.Bold
                        )
                        is TorManager.TorState.Checking -> Text(
                            stringResource(R.string.connexions_wait)
                        )
                        is TorManager.TorState.Failed -> Text(
                            stringResource(R.string.connexions_ko),
                            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (val st = torState) {
                    is TorManager.TorState.Starting -> {
                        LinearProgressIndicator(
                            progress = { st.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.connexions_tor_starting, elapsedS),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Blocage visible : 0 % pendant plus d'une minute → piste réseau.
                        if (st.percent == 0 && elapsedS > 60) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.connexions_tor_stuck),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    is TorManager.TorState.Failed -> {
                        Text(
                            st.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { torMgr.retry() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.connexions_retry))
                        }
                    }
                    is TorManager.TorState.Ready -> Text(
                        stringResource(R.string.connexions_tor_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TorManager.TorState.Checking -> {}
                }
            }
        }

        // ---------- Étape 2 — Réplica ----------
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.connexions_replica_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    when {
                        probe?.ok == true -> Text(
                            stringResource(R.string.connexions_ok),
                            color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold
                        )
                        torReady -> Text(stringResource(R.string.connexions_wait))
                        else -> Text(
                            stringResource(R.string.connexions_pending_tor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    !torReady -> Text(
                        stringResource(R.string.connexions_replica_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    repo.replicas.isEmpty() -> Text(
                        stringResource(R.string.replica_none_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    probe?.ok == true -> {
                        LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.connexions_replica_ok, repo.replicas.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.connexions_replica_probing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        probe?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { probeAttempt++ },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.connexions_retry)) }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.connexions_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Build test : le bouton rouge est présent SUR CETTE PAGE (v0.2.5) —
        // les emplacements existants (onboarding, conversations) sont conservés.
        if (net.nyx.messenger.BuildConfig.TEST_BUILD) {
            Spacer(Modifier.height(16.dp))
            TestLogExportCard()
        }
    }
}
