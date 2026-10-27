package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.ACCEPTED
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.INCOMING
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.PENDING
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.desktop.ServiceLocator.TorPhase

/** Bannière d'état Tor (miroir de la bannière Android). */
@Composable
fun TorBanner() {
    val st = ServiceLocator.torState.value
    val (label, color) = when (st.phase) {
        TorPhase.OFF -> "Tor arrêté" to MaterialTheme.colorScheme.outline
        TorPhase.STARTING -> "Tor démarre… ${st.percent ?: 0}%" +
            (st.message?.let { " ($it)" } ?: "") to MaterialTheme.colorScheme.tertiary
        TorPhase.READY -> "Tor prêt — onion publié" to MaterialTheme.colorScheme.primary
        TorPhase.FAILED -> "Tor en échec" to MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // v0.3.3 — l'erreur COMPLÈTE (queue tor.log/console incluse depuis
            // DesktopTor) est lisible sous la ligne d'état : « voir tor/tor.log »
            // était inutilisable pour un opérateur sans accès console.
            if (st.phase == TorPhase.FAILED) {
                Text(
                    st.message?.take(160) ?: "échec inconnu",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // v0.3.3 — bouton « Réessayer » (miroir TorManager.retry Android) : un
        // échec n'est plus une impasse jusqu'au redémarrage complet de l'app.
        if (st.phase == TorPhase.FAILED || st.phase == TorPhase.OFF) {
            TextButton(onClick = { ServiceLocator.retryBackend() }) {
                Text("Réessayer", fontSize = 12.sp)
            }
        }
        if (st.onion != null) {
            Text(
                "identité : ${st.onion!!.take(10)}…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val TAB_LABELS = listOf(
    "Conversations" to SidebarTab.CONVERSATIONS,
    "Contacts" to SidebarTab.CONTACTS,
    "Connexions" to SidebarTab.CONNEXIONS,
    "Mon adresse" to SidebarTab.MON_ADRESSE,
    "Réglages" to SidebarTab.REGLAGES,
)

enum class SidebarTab { CONVERSATIONS, CONTACTS, CONNEXIONS, MON_ADRESSE, REGLAGES }

@Composable
fun Sidebar(
    tab: SidebarTab,
    onTab: (SidebarTab) -> Unit,
    selectedPeer: String?,
    onSelectPeer: (String) -> Unit,
    tick: Long,
) {
    Column(Modifier.fillMaxWidth()) {
        // Titre « Nyx » (lune).
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("☾", color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text("Nyx", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(6.dp))
            Text(
                ServiceLocator.VERSION,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Bottom).padding(bottom = 2.dp)
            )
        }

        // Navigation.
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

        // Liste des conversations (contacts acceptés avec messages).
        val contacts = ServiceLocator.store.contacts()
            .filter { it.status == ACCEPTED }
        val msgs = ServiceLocator.store.messages()
        Spacer(Modifier.height(8.dp))
        // v0.3.3 — carte d'envoi de logs (miroir Android, tête de l'écran
        // conversations — demande opérateur session 73).
        TestLogExportCard()
        Spacer(Modifier.height(8.dp))
        if (contacts.isEmpty()) {
            Text(
                "Aucun contact encore.\nAjoutez-en un dans l'onglet Contacts.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        contacts.forEach { c ->
            val last = msgs.filter { it.peer == c.onion }.maxByOrNull { it.ts }
            val unread = ServiceLocator.store.unreadCount(c.onion)
            val sel = selectedPeer == c.onion
            Row(
                Modifier.fillMaxWidth()
                    .background(if (sel) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
                    .clickable { onSelectPeer(c.onion) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(c.alias.ifBlank { c.onion })
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        c.alias.ifBlank { c.onion.take(12) + "…" },
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        last?.text?.take(40) ?: "—",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (unread > 0) {
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text("$unread", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}
