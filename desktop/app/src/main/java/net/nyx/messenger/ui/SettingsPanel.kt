package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.DesktopSettings
import net.nyx.messenger.desktop.ServiceLocator
import java.io.File

/**
 * Réglages desktop — miroir de SettingsScreen Android (sous-ensemble v1) :
 * thème nuit/aube, réplique embarquée on/off, profil public, alias, réplica
 * personnalisé, export de diagnostic, effacement complet.
 */
@Composable
fun SettingsPanel() {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(ServiceLocator.settings.get()) }
    val profile = remember { ServiceLocator.store.myProfile() }
    var displayName by remember { mutableStateOf(profile.displayName) }
    var bio by remember { mutableStateOf(profile.bio) }
    var customReplica by remember { mutableStateOf(s.customReplica) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Réglages", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        SettingCard("Apparence") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Thème « Nuit de Nyx »", Modifier.weight(1f), fontSize = 13.sp)
                Switch(checked = s.theme == "nuit", onCheckedChange = { dark ->
                    s = s.copy(theme = if (dark) "nuit" else "aube")
                    ServiceLocator.settings.update { it.copy(theme = s.theme) }
                    message = "thème appliqué au PROCHAIN démarrage"
                })
            }
        }

        SettingCard("Réplique embarquée") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Servir un réplica temporaire quand l'app tourne", fontSize = 13.sp)
                    Text(
                        "Vos contacts peuvent déposer chez vous directement (port 80 de votre onion). " +
                            "Quotas identiques à l'Android : 48 h de rétention, stockage borné.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = s.embeddedReplicaEnabled, onCheckedChange = { on ->
                    s = s.copy(embeddedReplicaEnabled = on)
                    ServiceLocator.settings.update { it.copy(embeddedReplicaEnabled = on) }
                    net.nyx.messenger.replica.EmbeddedReplica.serving = on
                })
            }
        }

        SettingCard("Profil public") {
            OutlinedTextField(value = displayName, onValueChange = { displayName = it },
                label = { Text("Nom affiché") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = bio, onValueChange = { bio = it },
                label = { Text("Bio") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    ServiceLocator.store.saveMyProfile(
                        ServiceLocator.store.myProfile().let {
                            it.copy(displayName = displayName, bio = bio)
                        }
                    )
                    ServiceLocator.repository.publishProfile().fold(
                        onSuccess = { message = "profil publié (événement signé diffusé)" },
                        onFailure = { message = "échec : ${it.message}" }
                    )
                }
            }) { Text("Publier mon profil") }
        }

        SettingCard("Réplica personnalisé") {
            OutlinedTextField(value = customReplica, onValueChange = { customReplica = it.trim() },
                label = { Text("Adresse onion du réplica (optionnel)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("S'ajoute devant la liste signée (rendezvous)") })
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                ServiceLocator.settings.update { it.copy(customReplica = customReplica) }
                ServiceLocator.setCustomReplica(customReplica)
                message = "liste des réplicas mise à jour"
            }) { Text("Appliquer") }
        }

        SettingCard("Diagnostic") {
            Text(
                "Le journal applicatif complet (nyx-send/recv/poll/tor/replica) s'exporte " +
                    "pour analyse terrain — même format que l'export Android.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val fc = javax.swing.JFileChooser()
                fc.selectedFile = File("nyx-desktop-logs.txt")
                if (fc.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    fc.selectedFile.writeText(
                        "Nyx desktop ${ServiceLocator.VERSION} — export ${java.time.Instant.now()}\n\n" +
                            DesktopLog.dump()
                    )
                    message = "journal exporté : ${fc.selectedFile.absolutePath}"
                }
            }) { Text("Exporter le journal") }
        }

        SettingCard("Zone dangereuse") {
            Text(
                "Tout effacer sur CET appareil (identité, messages, fichiers, contacts). " +
                    "Le compte redevient récupérable uniquement par clé de récupération.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                ServiceLocator.wipeAll()
                message = "données effacées — redémarrez l'application"
            }) { Text("Effacer toutes les données", color = MaterialTheme.colorScheme.error) }
        }

        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(6.dp))
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(16.dp)
    ) { content() }
}
