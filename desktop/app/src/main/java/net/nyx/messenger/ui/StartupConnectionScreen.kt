package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.desktop.ServiceLocator.TorPhase

/**
 * v0.3.4 (session 74 — demande opérateur : « Je voudrais au lancement, comme
 * pour la version Android, une barre de progression qui indique les états de
 * connexion à Tor et aux replicas ») — écran de connexion AU DÉMARRAGE,
 * MIROIR de ConnexionsScreen Android (mêmes libellés, même dynamique) :
 *
 *  - barre de progression GLOBALE : Tor = moitié gauche (percent/2), réplicas
 *    = moitié droite (sonde /health) ;
 *  - ÉTAPE 1 « Tor embarqué » : pourcentage vivant + barre + durée écoulée +
 *    indice de blocage (0 % > 60 s — réseau filtré ?) + « Réessayer » ;
 *  - ÉTAPE 2 « Réplica » : sonde GET /health via Tor (boucle ServiceLocator
 *    8 s, miroir ReplicaProbeEffect) — « en attente de Tor » puis sondage ;
 *  - AUTO-AVANCE quand les deux étapes sont vertes (petit délai pour voir le
 *    vert, miroir Android 900 ms) ; « Continuer sans attendre » : l'app reste
 *    utilisable derrière (bannière Tor permanente, conversations hors ligne) ;
 *  - carte ENVOYER LES LOGS en bas : c'est exactement AU MOMENT d'un blocage
 *    ici que l'export sert (leçons v0.2.38/v0.2.42 Android).
 */
@Composable
fun StartupConnectionScreen(onConnected: () -> Unit, onSkip: () -> Unit) {
    val torState by ServiceLocator.torState.collectAsState()
    val probe by ServiceLocator.replicaProbe.collectAsState()

    // Horodatage du changement d'état Tor (durée affichée « depuis X s ») +
    // horloge 1 s pour rafraîchir les durées à l'écran (miroir Android).
    var phaseSince by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var clock by remember { mutableIntStateOf(0) }
    LaunchedEffect(torState.phase) { phaseSince = System.currentTimeMillis() }
    LaunchedEffect(Unit) { while (true) { delay(1_000); clock++ } }
    val elapsedS = ((System.currentTimeMillis() - phaseSince) / 1000).coerceAtLeast(0)

    val torReady = torState.phase == TorPhase.READY
    val replicasKnown = ServiceLocator.repository.replicas.isNotEmpty()
    val probeOk = probe.ok == true

    // Sonde manuelle (« Réessayer » de l'étape 2 — court-circuite les 8 s).
    var probeAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(probeAttempt) { if (probeAttempt > 0) ServiceLocator.probeReplicasNow() }

    // Auto-avance : les DEUX étapes validées → application (petit délai pour
    // que l'utilisateur voie le vert avant la transition — miroir Android).
    LaunchedEffect(torReady, probeOk, replicasKnown) {
        if (torReady && (probeOk || !replicasKnown)) {
            delay(900)
            onConnected()
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        MoonSigil()
        Spacer(Modifier.height(10.dp))
        Text("Connexion", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Nyx s'éveille : elle rejoint d'abord le réseau Tor, puis le réplica qui garde vos messages. " +
                "Ces deux pas franchis, l'application s'ouvre.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))

        // ---- Progression globale 0..1 : Tor = moitié gauche, réplicas = moitié droite.
        val overall = startupProgress(torState.phase, torState.percent, probe.ok, replicasKnown)
        LinearProgressIndicator(
            progress = { overall },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                torReady && probeOk -> "Connecté — ouverture de l'application…"
                torReady && !replicasKnown -> "Tor prêt — aucun réplica à sonder (boîtes des contacts : aucun contact accepté)"
                torReady -> "Étape 2 sur 2 — réplicas…"
                else -> "Étape 1 sur 2 — réveil du réseau Tor…"
            },
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))

        // ---------- Étape 1 — Tor ----------
        StepCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tor embarqué", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                when (torState.phase) {
                    TorPhase.READY -> StatusOk("Prêt")
                    TorPhase.STARTING -> Text("${torState.percent ?: 0} %", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    TorPhase.FAILED -> StatusKo("Échec")
                    TorPhase.OFF -> Text("arrêté", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(8.dp))
            when (torState.phase) {
                TorPhase.STARTING -> {
                    LinearProgressIndicator(
                        progress = { (torState.percent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Mise en place du réseau Tor… ($elapsedS s)",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Blocage visible (miroir Android) : 0 % pendant plus d'une minute → piste réseau.
                    if ((torState.percent ?: 0) == 0 && elapsedS > 60) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tor n'avance pas depuis plus d'une minute : réseau filtré ou bloqué ? " +
                                "Essayez une autre connexion puis « Réessayer ».",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TorPhase.FAILED -> {
                    Text(
                        torState.message?.take(300) ?: "échec inconnu",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { ServiceLocator.retryBackend() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Réessayer")
                    }
                }
                TorPhase.READY -> Text(
                    "Tor est prêt — le trafic circule anonymisé.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TorPhase.OFF -> {}
            }
        }

        // ---------- Étape 2 — Réplicas ----------
        Spacer(Modifier.height(12.dp))
        StepCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Réplicas", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                when {
                    probeOk -> StatusOk("Prêt")
                    torReady -> Text("En cours…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text("en attente de Tor", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                !torReady -> Text(
                    "Cette étape démarre dès que Tor est prêt.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                !replicasKnown -> Text(
                    "Aucun réplica connu : vos contacts acceptés servent de relais dès qu'ils sont en ligne " +
                        "(leur boîte = leur adresse). Vous pouvez aussi en renseigner un dans Réglages.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                probeOk -> {
                    LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Réplicas joignables (${probe.okCount}/${probe.total}) — connexion vérifiée.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Vérification des réplicas via Tor… (répétée automatiquement toutes les 8 s)",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    probe.detail?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it.take(160), fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { probeAttempt++ }, modifier = Modifier.fillMaxWidth()) {
                        Text("Réessayer")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Continuer sans attendre")
        }
        Text(
            "L'application reste utilisable pendant la connexion — l'état de Tor reste visible dans la bannière.",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(12.dp))
        TestLogExportCard()
        Spacer(Modifier.height(24.dp))
    }
}

/** Carte d'étape (style des panneaux desktop : surface variante arrondie). */
@Composable
private fun StepCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun StatusOk(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
        Spacer(Modifier.width(6.dp))
        Text(label, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun StatusKo(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Spacer(Modifier.width(6.dp))
        Text(label, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/** Lune stylisée (équivalent desktop du MoonHeader Android). */
@Composable
private fun MoonSigil() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        ) {
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .align(Alignment.TopEnd)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("Nyx — messagerie de la nuit", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

/**
 * Progression globale 0..1 — MIROIR EXACT de la formule Android
 * (ConnexionsScreen) : Tor = moitié gauche, réplica = moitié droite.
 * Pure + internal : testée JVM (StartupProgressTest).
 */
internal fun startupProgress(
    phase: TorPhase,
    percent: Int?,
    probeOk: Boolean?,
    replicasKnown: Boolean,
): Float {
    val torPct = when (phase) {
        TorPhase.READY -> 50
        TorPhase.STARTING -> ((percent ?: 0) / 2).coerceIn(0, 49)
        else -> 0
    }
    val repPct = when {
        probeOk == true -> 50
        phase == TorPhase.READY && replicasKnown -> 10
        else -> 0
    }
    return ((torPct + repPct) / 100f).coerceIn(0f, 1f)
}
