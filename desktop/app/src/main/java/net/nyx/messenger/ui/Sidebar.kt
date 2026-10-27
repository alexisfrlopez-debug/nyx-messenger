package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.nyx.messenger.desktop.ServiceLocator

/**
 * Barre latérale — v0.3.4 « parité Android » : MIROIR de l'en-tête de compte
 * Android (avatar + pseudo + menu ⋮ « Mon adresse Nyx / Mon profil /
 * Paramètres ») + navigation écran (Conversations / Connexions / Mon adresse /
 * Mon profil / Réglages). La LISTE des conversations vit désormais dans
 * l'écran principal (MainScreen — 3 onglets miroir Android).
 */
enum class SidebarTab { CONVERSATIONS, CONNEXIONS, MON_ADRESSE, MON_PROFIL, REGLAGES }

private val TAB_LABELS = listOf(
    "Conversations" to SidebarTab.CONVERSATIONS,
    "Connexions" to SidebarTab.CONNEXIONS,
    "Mon adresse" to SidebarTab.MON_ADRESSE,
    "Mon profil" to SidebarTab.MON_PROFIL,
    "Réglages" to SidebarTab.REGLAGES,
)

@Composable
fun Sidebar(
    tab: SidebarTab,
    onTab: (SidebarTab) -> Unit,
    selectedPeer: String?,
    tick: Long,
    onOpenMyProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMyOnion: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val profile = remember(tick) { ServiceLocator.store.myProfile() }
    val alias = ServiceLocator.identity.alias ?: ""
    val displayName = profile.displayName.takeIf { it.isNotBlank() } ?: alias.ifBlank { "Nyx" }

    Column(Modifier.fillMaxWidth()) {
        // ---------- En-tête de compte (miroir TopAppBar Android) ----------
        Row(
            Modifier.fillMaxWidth().clickable { onOpenMyProfile() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("☾", color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Nyx", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        ServiceLocator.VERSION,
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.Bottom).padding(bottom = 2.dp)
                    )
                }
            }
            // ⋮ menu compte (miroir Android).
            TextButton(onClick = { menuOpen = true }) { Text("⋮") }
        }

        // ---------- Navigation ----------
        TAB_LABELS.forEach { (label, t) ->
            val selected = tab == t && !(t == SidebarTab.CONVERSATIONS && selectedPeer != null)
            Row(
                Modifier.fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
                    .clickable { onTab(t) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // v0.3.3 — carte d'envoi de logs (miroir Android, tête des conversations).
        TestLogExportCard()
        Spacer(Modifier.height(8.dp))

        // État bref (Tor + réplica) en bas de barre — la version COMPLÈTE vit
        // dans l'écran principal (zone fixe miroir Android).
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val st = ServiceLocator.torState.value
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(
                    when (st.phase) {
                        ServiceLocator.TorPhase.READY -> MaterialTheme.colorScheme.tertiary
                        ServiceLocator.TorPhase.FAILED -> MaterialTheme.colorScheme.error
                        ServiceLocator.TorPhase.STARTING -> MaterialTheme.colorScheme.primary
                        ServiceLocator.TorPhase.OFF -> MaterialTheme.colorScheme.outline
                    }
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (st.phase) {
                    ServiceLocator.TorPhase.READY -> "Tor actif"
                    ServiceLocator.TorPhase.STARTING -> "Tor ${st.percent ?: 0}%"
                    ServiceLocator.TorPhase.FAILED -> "Tor en échec"
                    ServiceLocator.TorPhase.OFF -> "Tor arrêté"
                },
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }

    // ⋮ compte (miroir menu_my_onion/menu_my_profile/menu_settings Android).
    if (menuOpen) {
        AlertDialog(
            onDismissRequest = { menuOpen = false },
            title = { Text(displayName) },
            text = {
                Column {
                    TextButton(onClick = { menuOpen = false; onOpenMyOnion() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Mon adresse Nyx", fontSize = 13.sp)
                    }
                    TextButton(onClick = { menuOpen = false; onOpenMyProfile() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Mon profil", fontSize = 13.sp)
                    }
                    TextButton(onClick = { menuOpen = false; onOpenSettings() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Paramètres", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuOpen = false }) { Text("Fermer") } }
        )
    }
}
