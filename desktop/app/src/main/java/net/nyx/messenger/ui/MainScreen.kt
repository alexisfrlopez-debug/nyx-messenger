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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import net.nyx.messenger.desktop.DesktopStore
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.desktop.ServiceLocator.TorPhase
import net.nyx.messenger.update.DesktopUpdater

/**
 * v0.3.4 « parité Android » — ÉCRAN PRINCIPAL, miroir de
 * ConversationListScreen Android : TROIS onglets (Conversations / Contacts /
 * Blocages) + zone fixe (bannière Tor + ligne de statut réplica + bandeau de
 * mise à jour).
 *
 * Conversations :
 *  - « Nouvelle conversation » (sélecteur de contacts acceptés) +
 *    « Nouveau groupe » (dialogue honnête — même texte que l'Android) ;
 *  - cartes triées par DERNIER ÉCHANGE : avatar (fiche pair), alias,
 *    « ● N non lus », aperçu « Moi : … » + état d'envoi ✓/✓✓/Lu, lignes
 *    d'invitation, bouton Ⓘ → fiche, clic droit → Fiche / Bloquer /
 *    Supprimer le contact / Supprimer la conversation ;
 *  - INCOMING → boutons Accepter / Refuser en ligne ;
 *  - section « Anciennes conversations » (liens archivés).
 */
@Composable
fun MainScreen(
    tick: Long,
    selectedPeer: String?,
    onSelectPeer: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenMyProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMyOnion: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        // ---------- Zone fixe (miroir Android) ----------
        TorBanner()
        ReplicaStatusLine(tick)
        UpdateBanner()
        Spacer(Modifier.height(6.dp))

        // ---------- Onglets ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            MainTab("Conversations", tab == 0) { tab = 0 }
            MainTab("Contacts", tab == 1) { tab = 1 }
            MainTab("Blocages", tab == 2) { tab = 2 }
        }
        Spacer(Modifier.height(4.dp))

        when (tab) {
            0 -> ConversationsTab(tick, selectedPeer, onSelectPeer, onOpenProfile)
            1 -> ContactsTab(tick, onSelectPeer, onOpenProfile)
            2 -> BlocagesTab(tick)
        }
    }
}

// ---------------- onglets & éléments partagés ----------------

@Composable
private fun MainTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
    }
}

/** Bannière Tor (miroir labels Android tor_*). */
@Composable
fun TorBanner() {
    val torState by ServiceLocator.torState.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(8.dp).height(8.dp)
                .background(
                    when (torState.phase) {
                        TorPhase.READY -> MaterialTheme.colorScheme.tertiary
                        TorPhase.FAILED -> MaterialTheme.colorScheme.error
                        TorPhase.STARTING -> MaterialTheme.colorScheme.primary
                        TorPhase.OFF -> MaterialTheme.colorScheme.outline
                    }, RoundedCornerShape(4.dp)
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (torState.phase) {
                TorPhase.STARTING -> "Réveil du réseau Tor… ${torState.percent ?: 0}%"
                TorPhase.READY -> "Tor actif — vos échanges voyagent anonymisés"
                TorPhase.FAILED -> "Impossible de joindre le réseau Tor — ${torState.message?.take(90) ?: "échec inconnu"}"
                TorPhase.OFF -> "Tor hors ligne"
            },
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (torState.phase == TorPhase.FAILED || torState.phase == TorPhase.OFF) {
            TextButton(onClick = { ServiceLocator.retryBackend() }) { Text("Réessayer", fontSize = 11.sp) }
        }
    }
}

/** Ligne de statut réplica (miroir ReplicaStatusLine Android v0.2.26). */
@Composable
fun ReplicaStatusLine(tick: Long) {
    val probe by ServiceLocator.replicaProbe.collectAsState()
    val label = when {
        !ServiceLocator.tor.bootstrapped -> "Réplica : connexion en cours…"
        probe.ok == true -> "Réplica connecté (${probe.okCount}/${probe.total})"
        probe.total > 0 -> "Réplique injoignable — ${probe.detail?.take(60) ?: "aucune réponse"} (nouvelle sonde ≤ 8 s)"
        else -> "Réplica : aucun connu — les contacts acceptés servent de relais dès qu'ils sont en ligne"
    }
    Text(
        label,
        Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        fontSize = 10.sp,
        color = if (probe.ok == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    )
}

/** Bandeau de mise à jour (miroir UpdateBanner Android v0.2.40 — discret). */
@Composable
fun UpdateBanner() {
    val state = DesktopUpdater.state
    when (state) {
        is DesktopUpdater.State.Ready -> Button(
            onClick = { DesktopUpdater.install() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) { Text("Nouvelle version ${state.version} prête — installer") }
        is DesktopUpdater.State.Downloading -> Text(
            "Téléchargement de la mise à jour ${state.version}… (${state.bytes / 1048576} Mo)",
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp),
            fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary
        )
        else -> {}
    }
}

// ---------------- onglet Conversations ----------------

@Composable
private fun ConversationsTab(
    tick: Long,
    selectedPeer: String?,
    onSelectPeer: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = ServiceLocator.store
    val contacts = remember(tick) { store.contacts() }
    val unread = remember(tick) { store.unreadByPeer() }
    val archived = contacts.filter { DesktopStore.archivedStatus(it.status) }
    val active = contacts.filter { !DesktopStore.archivedStatus(it.status) }
        .sortedByDescending { c -> store.lastMessageOf(c.onion)?.ts ?: c.addedAt / 1000 }

    var picker by remember { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf(false) }
    var cardMenu by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) } // (action, peer)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            OutlinedButton(onClick = { picker = true }, modifier = Modifier.weight(1f)) {
                Text("Nouvelle conversation", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { groupDialog = true }, modifier = Modifier.weight(1f)) {
                Text("Nouveau groupe", fontSize = 12.sp)
            }
        }

        // v0.3.4 — carte PLAN DE TESTS (portage miroir Android v0.2.20 : tête
        // de la liste des conversations du build test, repliée par défaut).
        TestPlanCard()
        Spacer(Modifier.height(6.dp))

        if (active.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Aucune conversation pour l'instant — ajoutez un contact (onglet Contacts) pour démarrer.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
            )
        }

        for (c in active) {
            ConversationCard(
                contact = c,
                unread = unread[c.onion] ?: 0,
                selected = selectedPeer == c.onion,
                onClick = { onSelectPeer(c.onion) },
                onInfo = { onOpenProfile(c.onion) },
                onRightClick = { cardMenu = c.onion },
                onAccept = {
                    scope.launch { ServiceLocator.repository.acceptContact(c.onion); ServiceLocator.pollNow() }
                },
                onDecline = {
                    scope.launch { ServiceLocator.repository.declineContact(c.onion); ServiceLocator.pollNow() }
                },
            )
            Spacer(Modifier.height(6.dp))
        }

        if (archived.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Anciennes conversations", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            for (c in archived) {
                ConversationCard(
                    contact = c,
                    unread = 0,
                    selected = false,
                    onClick = { onSelectPeer(c.onion) },
                    onInfo = { onOpenProfile(c.onion) },
                    onRightClick = { cardMenu = c.onion },
                    onAccept = {},
                    onDecline = {},
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    // Sélecteur « Nouvelle conversation » (miroir picker Android).
    if (picker) {
        val accepted = contacts.filter { it.status == DesktopStore.ContactStatus.ACCEPTED }
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("Nouvelle conversation") },
            text = {
                if (accepted.isEmpty()) Text("Aucun contact accepté — ajoutez d'abord un contact (onglet Contacts).")
                else Column {
                    accepted.sortedBy { it.alias.lowercase() }.forEach { c ->
                        TextButton(onClick = { picker = false; onSelectPeer(c.onion) }, modifier = Modifier.fillMaxWidth()) {
                            Text(c.alias.ifBlank { c.onion.take(10) + "…" }, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picker = false }) { Text("Annuler") } }
        )
    }

    // « Nouveau groupe » — dialogue honnête (TEXTE EXACT Android).
    if (groupDialog) {
        AlertDialog(
            onDismissRequest = { groupDialog = false },
            title = { Text("Groupes") },
            text = { Text("Les groupes ne sont pas encore disponibles : Nyx chiffre les échanges de bout en bout, de contact à contact. Ils arriveront dans une prochaine version.") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { groupDialog = false }) { Text("Annuler") } }
        )
    }

    // Menu clic droit d'une carte (miroir long-press Android).
    cardMenu?.let { onion ->
        val c = contacts.firstOrNull { it.onion == onion }
        AlertDialog(
            onDismissRequest = { cardMenu = null },
            title = { Text(c?.alias?.ifBlank { onion.take(10) + "…" } ?: "") },
            text = {
                Column {
                    MenuEntry("Fiche du contact") { cardMenu = null; onOpenProfile(onion) }
                    if (c != null && !DesktopStore.archivedStatus(c.status) && c.status == DesktopStore.ContactStatus.ACCEPTED) {
                        MenuEntry("Bloquer") { cardMenu = null; confirm = "block" to onion }
                    }
                    MenuEntry("Supprimer ce contact ?") { cardMenu = null; confirm = "delete_contact" to onion }
                    MenuEntry("Supprimer la conversation") { cardMenu = null; confirm = "delete_conv" to onion }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { cardMenu = null }) { Text("Fermer") } }
        )
    }

    // Confirmations (miroir libellés Android).
    confirm?.let { (action, onion) ->
        val c = contacts.firstOrNull { it.onion == onion }
        val label = c?.alias?.ifBlank { onion.take(10) + "…" } ?: onion
        when (action) {
            "block" -> AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Bloquer ce contact ?") },
                text = {
                    Text(
                        "« $label » ne pourra plus vous envoyer de messages ni vous appeler, et ne découvrira plus votre réplique temporaire. " +
                            "Le blocage reste strictement local : il n'en sera jamais informé. Vous pourrez le débloquer à tout moment (onglet Blocages) — " +
                            "son historique et son statut de contact sont conservés."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        scope.launch { ServiceLocator.repository.setBlocked(onion, true) }
                    }) { Text("Bloquer", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } }
            )
            "delete_contact" -> AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Supprimer ce contact ?") },
                text = {
                    Text(
                        "« $label » et toute sa conversation seront supprimés de cet appareil. " +
                            "Votre contact, lui, conserve sa copie — il pourra toujours vous écrire."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        scope.launch {
                            ServiceLocator.repository.removeContact(onion)
                            ServiceLocator.pollNow()
                        }
                    }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } }
            )
            "delete_conv" -> AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Supprimer la conversation") },
                text = {
                    Text(
                        "Supprimer la conversation de « $label » de cet appareil ? " +
                            "Seuls les messages et fichiers locaux disparaissent — le contact lui-même est conservé."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        scope.launch { ServiceLocator.repository.deleteConversationLocal(onion) }
                    }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } }
            )
        }
    }
}

@Composable
private fun MenuEntry(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label, fontSize = 13.sp) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    contact: DesktopStore.Contact,
    unread: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onInfo: () -> Unit,
    onRightClick: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val store = ServiceLocator.store
    val last = remember(contact.onion) { store.lastMessageOf(contact.onion) }
    val profile = remember(contact.onion) { store.peerProfile(contact.onion) }
    val label = contact.alias.ifBlank { contact.onion.take(10) + "…" }
    val displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: label

    Column(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(10.dp)
            )
            .onClick(matcher = PointerMatcher.mouse(androidx.compose.ui.input.pointer.PointerButton.Primary)) { onClick() }
            .onClick(matcher = PointerMatcher.mouse(androidx.compose.ui.input.pointer.PointerButton.Secondary)) { onRightClick() }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(displayName, profile?.avatarB64.orEmpty(), 38)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    if (unread > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "● $unread non lu" + (if (unread > 1) "s" else ""),
                            color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (last != null) {
                    Row {
                        Text(
                            StatusLabels.previewLabel(last),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false), maxLines = 1
                        )
                        if (last.outgoing) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                ChatFormat.outgoingStatusLabel(last),
                                fontSize = 10.sp,
                                color = if (last.rcptRead > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
                StatusLabels.invitationStatus(contact.status)?.let { line ->
                    Text(
                        line,
                        fontSize = 10.sp,
                        color = when (StatusLabels.invitationStatusTone(contact.status)) {
                            0 -> MaterialTheme.colorScheme.tertiary
                            1 -> MaterialTheme.colorScheme.primary
                            2 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
                }
            }
            TextButton(onClick = onInfo) { Text("Ⓘ", fontSize = 14.sp) }
        }
        if (contact.status == DesktopStore.ContactStatus.INCOMING) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onAccept, modifier = Modifier.weight(1f)) {
                    Text("Accepter", color = MaterialTheme.colorScheme.tertiary)
                }
                TextButton(onClick = onDecline, modifier = Modifier.weight(1f)) {
                    Text("Refuser", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---------------- onglet Contacts (raccourcis miroir Android) ----------------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContactsTab(tick: Long, onSelectPeer: (String) -> Unit, onOpenProfile: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val store = ServiceLocator.store
    val contacts = remember(tick) { store.contacts() }
    val accepted = contacts.filter { it.status == DesktopStore.ContactStatus.ACCEPTED }
        .sortedBy { it.alias.lowercase() }

    var addOpen by remember { mutableStateOf(false) }
    var onionInput by remember { mutableStateOf("") }
    var aliasInput by remember { mutableStateOf("") }
    var labelInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ajouter un contact", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { addOpen = !addOpen }) { Text(if (addOpen) "Fermer" else "Ajouter") }
        }
        if (addOpen) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(12.dp)
            ) {
                OutlinedTextField(
                    value = onionInput, onValueChange = { onionInput = it },
                    placeholder = { Text("Adresse onion du contact… ou son alias (@pseudo)", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = labelInput, onValueChange = { labelInput = it },
                    placeholder = { Text("Nom local (optionnel)", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    scope.launch {
                        val r = if (onionInput.trim().length >= 56)
                            ServiceLocator.repository.addContact(onionInput, labelInput)
                        else
                            ServiceLocator.repository.addContactByAlias(onionInput)
                        r.fold(
                            onSuccess = {
                                onionInput = ""; aliasInput = ""; labelInput = ""; addError = null
                                ServiceLocator.pollNow()
                            },
                            onFailure = { e -> addError = e.message }
                        )
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Ajouter") }
                addError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (accepted.isEmpty()) {
            Text(
                "Aucun contact accepté pour l'instant — ajoutez-en un avec « Ajouter un contact ».",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
            )
        }
        for (c in accepted) {
            val profile = remember(c.onion) { store.peerProfile(c.onion) }
            val label = c.alias.ifBlank { c.onion.take(10) + "…" }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                    .onClick(matcher = PointerMatcher.mouse(androidx.compose.ui.input.pointer.PointerButton.Primary)) { onSelectPeer(c.onion) }
                    .onClick(matcher = PointerMatcher.mouse(androidx.compose.ui.input.pointer.PointerButton.Secondary)) { menu = c.onion }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(label, profile?.avatarB64.orEmpty(), 34)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile?.displayName?.takeIf { it.isNotBlank() } ?: label, fontSize = 13.sp)
                    if (c.blockedAt > 0) {
                        Text(StatusLabels.blockedSince(c.blockedAt), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
                // Raccourcis miroir ContactShortcutRow Android (Message/Appel/Visio).
                TextButton(onClick = { onSelectPeer(c.onion) }) { Text("💬") }
                TextButton(onClick = { menu = c.onion }) { Text("📞") }
                TextButton(onClick = { menu = c.onion }) { Text("📹") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    menu?.let { onion ->
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(contacts.firstOrNull { it.onion == onion }?.alias ?: "") },
            text = {
                Column {
                    MenuEntry("Fiche du contact") { menu = null; onOpenProfile(onion) }
                    MenuEntry("Bloquer") {
                        menu = null
                        scope.launch { ServiceLocator.repository.setBlocked(onion, true) }
                    }
                    MenuEntry("Appeler") { menu = null }   // phase 2 — honnête via fermeture
                    MenuEntry("Appel vidéo") { menu = null }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menu = null }) { Text("Fermer") } }
        )
    }
}

// ---------------- onglet Blocages (miroir BlockedTab Android) ----------------

@Composable
private fun BlocagesTab(tick: Long) {
    val scope = rememberCoroutineScope()
    val contacts = remember(tick) { ServiceLocator.store.contacts() }
    val blocked = contacts.filter { it.blockedAt > 0 }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Un contact bloqué ne peut ni vous écrire ni vous appeler (ses messages et appels sont détruits à l'arrivée, sans qu'il en soit informé). " +
                "Débloquer rétablit l'échange tel qu'avant le blocage.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        if (blocked.isEmpty()) {
            Text("Aucun contact bloqué.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        for (c in blocked) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(c.alias.ifBlank { c.onion.take(10) + "…" }, "", 34)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.alias.ifBlank { c.onion.take(10) + "…" }, fontSize = 13.sp)
                    Text(StatusLabels.blockedSince(c.blockedAt), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                }
                TextButton(onClick = {
                    scope.launch { ServiceLocator.repository.setBlocked(c.onion, false) }
                }) { Text("Débloquer") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
