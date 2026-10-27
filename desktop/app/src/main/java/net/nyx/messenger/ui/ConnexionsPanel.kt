package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.net.RelayClient

/**
 * Écran Connexions desktop — miroir de ConnexionsScreen Android : état de la
 * liste fusionnée des réplicas (découverts + rendezvous + personnalisé) avec
 * sondes /health vivantes.
 */
@Composable
fun ConnexionsPanel(tick: Long) {
    val scope = rememberCoroutineScope()
    // Liste fusionnée courante (vue repository).
    val replicas = remember(tick) {
        buildList {
            val custom = ServiceLocator.settings.get().customReplica.trim().lowercase()
            if (custom.isNotBlank()) add(custom)
            addAll(net.nyx.messenger.net.ReplicaRegistry.list())
            // rendezvous embarqués
            addAll(ServiceLocator.repository.replicas)
        }.distinct()
    }
    val probes = remember(tick) { mutableStateOf<Map<String, RelayClient.HealthReport>>(emptyMap()) }

    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connexions", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                scope.launch {
                    val socks = if (ServiceLocator.tor.bootstrapped) ServiceLocator.tor.socksPort else 0
                    if (socks <= 0) return@launch
                    val results = HashMap<String, RelayClient.HealthReport>()
                    for (addr in replicas) {
                        results[addr] = RelayClient.of(addr, socks).healthDetailed()
                    }
                    probes.value = results
                }
            }) { Text("Sonder la flotte") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Réplique embarquée : " + if (net.nyx.messenger.replica.EmbeddedReplica.alive())
                "EN LIGNE (port 80 de votre onion)" else "inactive",
            fontSize = 12.sp,
            color = if (net.nyx.messenger.replica.EmbeddedReplica.alive()) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))

        replicas.forEach { addr ->
            val p = probes.value[addr]
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(9.dp).clip(CircleShape).background(
                        when {
                            p == null -> MaterialTheme.colorScheme.outline
                            p.ok -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        addr.take(24) + "…" + addr.substringAfterLast(".onion", ""),
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        p?.let { if (it.ok) "joignable (${it.ms} ms)" else "injoignable : ${it.detail}" }
                            ?: "non sondé",
                        fontSize = 10.sp,
                        color = if (p?.ok == true) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        if (replicas.isEmpty()) {
            Text(
                "Aucun réplica connu — renseignez-en un dans Réglages (le réseau grandit par " +
                    "les annonces de vos contacts).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
