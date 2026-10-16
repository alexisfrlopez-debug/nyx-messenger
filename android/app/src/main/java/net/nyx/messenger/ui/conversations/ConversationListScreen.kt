package net.nyx.messenger.ui.conversations

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.nyx.messenger.BuildConfig
import net.nyx.messenger.R
import net.nyx.messenger.crypto.AliasValidator
import net.nyx.messenger.data.db.ContactStatus
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.tor.TorManager
import net.nyx.messenger.ui.diag.TestLogExportCard

/**
 * Liste des conversations + ajout de contact (v0.2.0) :
 *  - par ALIAS UNIQUE (invitations « @pseudo » — résolution GET /client/alias) ;
 *  - par adresse onion collée (56 caractères) ou lien nyx://add.
 * Une bannière reflète l'état du Tor embarqué (ADR-007) — jamais de trafic hors Tor.
 * Le menu (⋮) ouvre : Mon adresse Nyx · Mon profil · Paramètres.
 *
 * v0.2.14 (demande opérateur) :
 *  - la liste n'affiche QUE l'ALIAS — l'adresse onion n'est visible QUE dans la
 *    fiche du contact (bouton ⓘ par carte) ;
 *  - deux sections : contacts actifs, puis « Anciennes conversations »
 *    (contacts refusés/supprimés — l'historique reste accessible, re-invitation
 *    possible en ré-ajoutant l'alias/l'adresse) ;
 *  - pression longue = choix : Supprimer le contact (propage l'avis au pair)
 *    ou Supprimer la conversation (strictement local) ;
 *  - demande runtime POST_NOTIFICATIONS (Android 13+ : sans elle, les
 *    notifications d'invitation — avec actions Accepter/Refuser — sont muettes).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    pendingAdd: String? = null,
    onOpenConversation: (String) -> Unit,
    onOpenContactProfile: (String) -> Unit = {},
    onOpenMyOnion: () -> Unit = {},
    onOpenMyProfile: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val db = ServiceLocator.database
    val scope = rememberCoroutineScope()
    val contacts by db.contactDao().allActive().collectAsState(initial = emptyList())
    val archived by db.contactDao().allArchived().collectAsState(initial = emptyList())

    var showAdd by remember { mutableStateOf(pendingAdd != null) }
    var input by remember { mutableStateOf(pendingAdd ?: "") }
    var aliasInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var addInfo by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val repo = remember { ServiceLocator.repository }
    val context = LocalContext.current

    val torState by TorManager.get(LocalContext.current).state.collectAsState()

    // v0.2.14 : permission des notifications (Android 13+) — demandée une fois
    // à l'entrée sur la liste ; refusée = notifications muettes (jamais bloquant).
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("nyx-notify", "POST_NOTIFICATIONS accordée : $granted")
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Claim d'alias différé : si l'alias choisi à l'onboarding n'a pas pu être
    // réservé (aucun réplica joignable), il part dès que le Tor embarqué est prêt.
    // v0.2.10 : les annonces de contact restées en attente (réplica injoignable à
    // l'ajout) repartent au même moment — et un premier poll est déclenché.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2_000) // laisse le bootstrap Tor démarrer
        repo.ensureAliasClaimed()
        repo.ensureContactNotified()
        runCatching { repo.pollInbox() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversations)) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_my_onion)) },
                            onClick = { menuOpen = false; onOpenMyOnion() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_my_profile)) },
                            onClick = { menuOpen = false; onOpenMyProfile() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_settings)) },
                            onClick = { menuOpen = false; onOpenSettings() }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            TorBanner(torState)
            // Build test : gros bouton rouge d'export de logs vers le Drive (v0.2.2).
            if (BuildConfig.TEST_BUILD) {
                Spacer(Modifier.height(8.dp))
                TestLogExportCard()
            }
            if (showAdd) {
                // Un seul champ : alias (@pseudo) OU adresse onion — détection auto.
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text(stringResource(R.string.add_contact_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = aliasInput, onValueChange = { aliasInput = it },
                    label = { Text(stringResource(R.string.add_contact_local_alias)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Button(onClick = {
                        addError = null
                        addInfo = null
                        val value = input.trim()
                        if (value.isEmpty()) return@Button
                        scope.launch {
                            if (value.contains(".onion")) {
                                // Adresse directe — Result<Boolean> : annonce
                                // transmise (true) ou différée (false, réplica
                                // injoignable — repartira via ensureContactNotified).
                                repo.addContact(value, aliasInput)
                                    .onSuccess { transmise ->
                                        showAdd = false; input = ""; aliasInput = ""
                                        addInfo = context.getString(
                                            if (transmise) R.string.add_contact_notified
                                            else R.string.add_contact_deferred
                                        )
                                    }
                                    .onFailure { addError = it.message }
                            } else {
                                // Alias unique : résolution puis ajout
                                repo.addContactByAlias(value).fold(
                                    onSuccess = { res ->
                                        when (res) {
                                            is net.nyx.messenger.repo.NyxRepository.AliasAddResult.Added -> {
                                                showAdd = false; input = ""; aliasInput = ""
                                                addInfo = context.getString(R.string.add_contact_added_alias, res.alias)
                                                addInfo = addInfo + " — " + context.getString(
                                                    if (res.notified) R.string.add_contact_notified
                                                    else R.string.add_contact_deferred
                                                )
                                            }
                                            is net.nyx.messenger.repo.NyxRepository.AliasAddResult.Free ->
                                                addError = context.getString(R.string.add_contact_alias_free, AliasValidator.normalize(value))
                                        }
                                    },
                                    onFailure = { addError = it.message }
                                )
                            }
                        }
                    }) { Text(stringResource(R.string.add)) }
                    addError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    addInfo?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            } else {
                Button(onClick = { showAdd = true }) { Text(stringResource(R.string.add_contact)) }
                addInfo?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }

            LazyColumn(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                // ── Contacts actifs ──
                items(contacts, key = { it.onion }) { c ->
                    ContactCard(
                        c = c,
                        archived = false,
                        onOpenConversation = onOpenConversation,
                        onOpenContactProfile = onOpenContactProfile
                    )
                }
                // ── Anciennes conversations (refusés/supprimés) ──
                if (archived.isNotEmpty()) {
                    item(key = "archived_header") {
                        Text(
                            stringResource(R.string.list_archived_header),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }
                    items(archived, key = { "a_" + it.onion }) { c ->
                        ContactCard(
                            c = c,
                            archived = true,
                            onOpenConversation = onOpenConversation,
                            onOpenContactProfile = onOpenContactProfile
                        )
                    }
                }
            }
        }
    }
}

/**
 * v0.2.14 — carte d'un contact (actif ou archivé) : ALIAS seul (l'adresse
 * onion n'est visible QUE dans la fiche — bouton ⓘ), statut d'invitation,
 * boutons Accepter/Refuser sur les invitations reçues, et pression longue
 * proposant Supprimer le contact / Supprimer la conversation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactCard(
    c: net.nyx.messenger.data.db.ContactEntity,
    archived: Boolean,
    onOpenConversation: (String) -> Unit,
    onOpenContactProfile: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val repo = remember { ServiceLocator.repository }
    var askDelete by remember { mutableStateOf(false) }
    val incoming = c.status == ContactStatus.INCOMING

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .combinedClickable(
                    onClick = { onOpenConversation(c.onion) },
                    onLongClick = { askDelete = true }
                )
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.alias, style = MaterialTheme.typography.titleMedium)
                // v0.2.14 : PLUS d'aperçu d'adresse onion ici — visible uniquement
                // dans la fiche du contact (bouton ⓘ), demande opérateur.
                InvitationStatusLine(c)
            }
            IconButton(onClick = { onOpenContactProfile(c.onion) }) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.contact_profile_open)
                )
            }
        }
        if (incoming) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            ) {
                TextButton(onClick = {
                    net.nyx.messenger.notify.ContactNotifier.cancelInvitation(c.onion)
                    scope.launch { repo.acceptContact(c.onion) }
                }) { Text(stringResource(R.string.invitation_accept)) }
                TextButton(onClick = {
                    net.nyx.messenger.notify.ContactNotifier.cancelInvitation(c.onion)
                    scope.launch { repo.declineContact(c.onion) }
                }) {
                    Text(
                        stringResource(R.string.invitation_decline),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = { Text(stringResource(R.string.delete_contact_text, c.alias)) },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    scope.launch { repo.deleteContact(c.onion) }
                }) { Text(stringResource(R.string.delete_contact_confirm)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        askDelete = false
                        scope.launch { repo.deleteConversation(c.onion) }
                    }) { Text(stringResource(R.string.delete_conversation_confirm)) }
                    TextButton(onClick = { askDelete = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }
}

/** Bannière d'état du Tor embarqué (ADR-007) — visible en haut de la liste. */
@Composable
private fun TorBanner(state: TorManager.TorState) {
    val (text, color) = when (state) {
        is TorManager.TorState.Starting ->
            stringResource(R.string.tor_starting, state.percent) to MaterialTheme.colorScheme.tertiary
        TorManager.TorState.Ready ->
            stringResource(R.string.tor_ready) to MaterialTheme.colorScheme.primary
        is TorManager.TorState.Failed ->
            stringResource(R.string.tor_failed) to MaterialTheme.colorScheme.error
        TorManager.TorState.Checking ->
            stringResource(R.string.tor_offline) to MaterialTheme.colorScheme.secondary
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * v0.2.12 — libellé du statut d'invitation sous l'adresse (demande opérateur).
 * « accepted » n'affiche rien : la conversation est normale.
 * v0.2.14 : les états archivés affichent un libellé discret (refusé/supprimé).
 */
@Composable
private fun InvitationStatusLine(c: net.nyx.messenger.data.db.ContactEntity) {
    val (resId, color) = when {
        c.status == ContactStatus.PENDING ->
            R.string.invitation_status_pending to MaterialTheme.colorScheme.tertiary
        c.status == ContactStatus.INCOMING ->
            R.string.invitation_status_incoming to MaterialTheme.colorScheme.primary
        c.status == ContactStatus.DECLINED ->
            R.string.invitation_status_declined to MaterialTheme.colorScheme.error
        c.status == ContactStatus.REMOVED ->
            R.string.invitation_status_removed to MaterialTheme.colorScheme.secondary
        c.status == ContactStatus.REMOVED_BY_ME ->
            R.string.invitation_status_removed_by_me to MaterialTheme.colorScheme.secondary
        c.status == ContactStatus.NONE &&
            (c.notifyPending == 1 || c.notifiedAt == 0L) ->
            R.string.invitation_status_unsent to MaterialTheme.colorScheme.secondary
        else -> return // accepted (ou cas ambigu) : pas d'étiquette
    }
    Text(
        stringResource(resId),
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp)
    )
}
