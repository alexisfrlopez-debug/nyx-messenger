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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.ACCEPTED
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.DECLINED
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.INCOMING
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.PENDING
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.REMOVED
import net.nyx.messenger.desktop.ServiceLocator

/**
 * Carnet de contacts desktop — miroir de l'onglet Contacts Android : ajout
 * par adresse onion OU par alias (résolution réseau), invitations entrantes
 * à accepter/refuser, suppression, blocage.
 */
@Composable
fun ContactsPanel(tick: Long) {
    val contacts = remember(tick) { ServiceLocator.store.contacts() }
    var mode by remember { mutableStateOf("list") }
    var onionInput by remember { mutableStateOf("") }
    var aliasInput by remember { mutableStateOf("") }
    var labelInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Contacts", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            Button(onClick = { mode = if (mode == "add") "list" else "add"; error = null }) {
                Text(if (mode == "add") "Annuler" else "Ajouter un contact")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (mode == "add") {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)
            ) {
                OutlinedTextField(
                    value = onionInput,
                    onValueChange = { onionInput = it.trim() },
                    label = { Text("Adresse onion du pair (56 caractères .onion)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Text("— ou —", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = { aliasInput = AliasFilter(it) },
                    label = { Text("Pseudonyme (@alias)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    label = { Text("Étiquette locale (optionnel)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            val res = if (onionInput.length >= 56) {
                                ServiceLocator.repository.addContact(onionInput, labelInput)
                            } else if (aliasInput.length >= 3) {
                                ServiceLocator.repository.addContactByAlias(aliasInput)
                            } else {
                                busy = false; error = "renseignez une adresse onion ou un alias"; return@launch
                            }
                            res.fold(
                                onSuccess = {
                                    onionInput = ""; aliasInput = ""; labelInput = ""
                                    mode = "list"; ServiceLocator.pollNow()
                                },
                                onFailure = { error = it.message }
                            )
                            busy = false
                        }
                    },
                    enabled = !busy
                ) { Text("Envoyer l'invitation") }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            items(contacts, key = { it.onion }) { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
            Avatar(c.alias.ifBlank { c.onion }, sizeDp = 36)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.alias.ifBlank { c.onion.take(14) + "…" },
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            statusLabel(c.status) + if (c.blockedAt > 0) " — bloqué" else "",
                            fontSize = 11.sp, color = statusColor(c.status)
                        )
                    }
                    when (c.status) {
                        INCOMING -> {
                            TextButton(onClick = {
                                scope.launch {
                                    ServiceLocator.repository.acceptContact(c.onion)
                                    ServiceLocator.pollNow()
                                }
                            }) { Text("Accepter") }
                            TextButton(onClick = {
                                scope.launch { ServiceLocator.repository.declineContact(c.onion) }
                            }) { Text("Refuser") }
                        }
                        PENDING -> {
                            Text(
                                "invitation en attente",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        ACCEPTED -> {
                            TextButton(onClick = {
                                scope.launch {
                                    ServiceLocator.repository.setBlocked(c.onion, c.blockedAt <= 0)
                                }
                            }) { Text(if (c.blockedAt > 0) "Débloquer" else "Bloquer") }
                            TextButton(onClick = {
                                scope.launch { ServiceLocator.repository.removeContact(c.onion) }
                            }) { Text("Supprimer") }
                        }
                        else -> {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { ServiceLocator.repository.addContact(c.onion, c.alias) }
                                }
                            }) { Text("Réinviter") }
                        }
                    }
                }
            }
        }
    }
}

private fun AliasFilter(raw: String): String =
    raw.trim().removePrefix("@").lowercase().replace(Regex("[^a-z0-9_]"), "_").take(24)

private fun statusLabel(s: net.nyx.messenger.desktop.DesktopStore.ContactStatus): String = when (s) {
    PENDING -> "invitation envoyée"
    INCOMING -> "invitation reçue"
    ACCEPTED -> "contact"
    DECLINED -> "décliné"
    REMOVED -> "supprimé"
}

@Composable
private fun statusColor(s: net.nyx.messenger.desktop.DesktopStore.ContactStatus) = when (s) {
    ACCEPTED -> MaterialTheme.colorScheme.tertiary
    INCOMING -> MaterialTheme.colorScheme.primary
    PENDING -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.error
}
