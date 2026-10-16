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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.nyx.messenger.BuildConfig
import net.nyx.messenger.R
import net.nyx.messenger.crypto.AliasValidator
import net.nyx.messenger.data.db.ContactStatus
import net.nyx.messenger.data.db.MessageEntity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.tor.TorManager
import net.nyx.messenger.ui.components.AvatarView
import net.nyx.messenger.ui.components.ReplicaProbeEffect
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
 *
 * v0.2.15 (demande opérateur) :
 *  - EN-TÊTE : l'alias du compte + SA photo de profil (clic → mon profil) ;
 *  - chaque contact montre SA photo si publique (cache peer_profiles — TTL 1 h),
 *    sinon l'icône générique, AVEC le dernier message de la conversation
 *    (envoyé ou reçu) ;
 *  - indicateurs à côté des alias : côté expéditeur ✓ Envoyé / ✓✓ Reçu / ✓✓ Lu
 *    sur le dernier message sortant ; côté destinataire point + compteur de
 *    messages reçus non lus (effacé à l'ouverture du chat) ;
 *  - imePadding : la saisie d'ajout de contact n'est plus cachée par le clavier.
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

    // v0.2.15 — aperçu du dernier message PAR PAIR + compteurs de non-lus
    // (flows Room : re-émission automatique à chaque écriture).
    val lastByPeer by db.messageDao().lastByPeer().collectAsState(initial = emptyList())
    val lastMap = remember(lastByPeer) { lastByPeer.associateBy { it.peerOnion } }
    val unreadByPeer by db.messageDao().unreadByPeer().collectAsState(initial = emptyList())
    val unreadMap = remember(unreadByPeer) { unreadByPeer.associate { it.peer to it.cnt } }

    // v0.2.15 — données de l'EN-TÊTE : alias du compte + photo de profil
    // (sienne, privée ou publique — ne part jamais d'ici). Rechargée au
    // retour de l'édition de profil (la destination quitte la composition).
    var ownProfile by remember { mutableStateOf<net.nyx.messenger.profile.OwnProfile?>(null) }

    var showAdd by remember { mutableStateOf(pendingAdd != null) }
    var input by remember { mutableStateOf(pendingAdd ?: "") }
    var aliasInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var addInfo by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val repo = remember { ServiceLocator.repository }
    val context = LocalContext.current

    // v0.2.15 : charge le profil PROPRE (avatar de l'en-tête) — après la
    // déclaration de context (l'écran recharge au retour de l'édition).
    LaunchedEffect(Unit) {
        ownProfile = net.nyx.messenger.profile.ProfileStore.get(context).load()
    }

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

    // v0.2.15 — cache des profils des pairs (avatars publics) : rafraîchi à
    // l'entrée sur la liste et quand les contacts changent (TTL 1 h par pair
    // en interne — échec silencieux, l'icône générique reste affichée).
    LaunchedEffect(contacts.map { it.onion }) {
        if (contacts.isNotEmpty()) repo.refreshPeerProfiles(contacts.map { it.onion })
    }

    Scaffold(
        modifier = Modifier.imePadding(), // v0.2.15 : saisie d'ajout au-dessus du clavier
        topBar = {
            TopAppBar(
                // v0.2.15 — EN-TÊTE COMPTE (demande opérateur : « en haut de la
                // fenêtre conversation apparaisse l'alias du compte et la photo
                // de profil ») — clic = ouvrir MON profil (édition).
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.combinedClickable(onClick = onOpenMyProfile)
                    ) {
                        AvatarView(
                            ownProfile?.valueOf(net.nyx.messenger.profile.ProfileKeys.AVATAR)
                                ?.ifBlank { null },
                            size = 34.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ServiceLocator.identity.alias
                                ?: stringResource(R.string.conversations),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
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
            // v0.2.16 — état du RÉPLICA sous la bannière Tor (retour terrain :
            // « après fermeture/réouverture, le client ne se connecte plus au
            // réplica » — sans indicateur, impossible de distinguer une reprise
            // en cours d’une panne réelle). La sonde tourne ici (20 s) dès que
            // Tor est prêt ; le résultat détaillé est observé via lastProbe.
            val torReady = torState is TorManager.TorState.Ready
            ReplicaProbeEffect(torReady = torReady, intervalMs = 20_000L)
            ReplicaStatusLine(repo, torReady)
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
                        last = lastMap[c.onion],
                        unread = unreadMap[c.onion] ?: 0,
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
                            last = lastMap[c.onion],
                            unread = 0, // un lien archivé ne reçoit plus — pas d'indicateur
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
 *
 * v0.2.15 (demande opérateur) :
 *  - AVATAR à gauche : la photo du contact SI publique (cache peer_profiles,
 *    rafraîchi TTL 1 h à l'entrée sur la liste), sinon l'icône générique ;
 *  - DERNIER MESSAGE de la conversation sous l'alias (envoyé ou reçu —
 *    préfixé « Moi : » côté expéditeur) ;
 *  - INDICATEURS à côté de l'alias : point + compteur de messages reçus non
 *    lus (destinataire) OU état du dernier message sortant ✓/✓✓/Lu
 *    (expéditeur — [outgoingStatusLabel]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactCard(
    c: net.nyx.messenger.data.db.ContactEntity,
    archived: Boolean,
    last: MessageEntity?,
    unread: Int,
    onOpenConversation: (String) -> Unit,
    onOpenContactProfile: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val repo = remember { ServiceLocator.repository }
    var askDelete by remember { mutableStateOf(false) }
    val incoming = c.status == ContactStatus.INCOMING
    // v0.2.15 : profil public en cache du pair (observable — l'avatar apparaît
    // dès que le fetch de rafraîchissement aboutit).
    val peerProfile by repo.peerProfileFlow(c.onion).collectAsState(initial = null)
    val showPreview = !archived && c.status == ContactStatus.ACCEPTED

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .combinedClickable(
                    onClick = { onOpenConversation(c.onion) },
                    onLongClick = { askDelete = true }
                )
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
        ) {
            // — PHOTO du contact (publique) ou icône générique (privée/absente) —
            AvatarView(peerProfile?.avatar?.ifBlank { null }, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.alias,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // — INDICATEUR DESTINATAIRE : messages reçus NON LUS —
                    // (effacé à l'ouverture du chat — accusés « read » en route)
                    if (unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "●",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            stringResource(
                                R.string.list_unread_badge,
                                unread, if (unread > 1) "s" else ""
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                if (showPreview && last != null) {
                    // — DERNIER MESSAGE (envoyé ou reçu) + ÉTAT EXPÉDITEUR —
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (last.outgoing) {
                                stringResource(R.string.list_me_prefix, last.text)
                            } else last.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        outgoingStatusLabel(last)?.let { label ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (last.readAt > 0L) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    InvitationStatusLine(c)
                }
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
 * v0.2.16 — ligne d'état du réplica rattaché, sous la bannière Tor :
 *  - Tor pas prêt : « Réplica : connexion en cours… » (tertiaire) ;
 *  - sonde OK : « Réplica connecté (X ms) » (primaire) ;
 *  - sonde en échec : « Réplica injoignable — <cause> (essai à HH:MM) »
 *    (erreur) — la CAUSE (timeout circuit, connexion refusée, HTTP…) vient
 *    du détail de la sonde ; c'est l'élément qui manquait au terrain pour
 *    distinguer une reprise lente d'une panne.
 * Discret (bodySmall) — aucune interaction.
 */
@Composable
private fun ReplicaStatusLine(
    repo: net.nyx.messenger.repo.NyxRepository,
    torReady: Boolean
) {
    val probe by repo.lastProbe.collectAsState()
    val text: String
    val color: androidx.compose.ui.graphics.Color
    if (!torReady) {
        text = stringResource(R.string.replica_status_waiting)
        color = MaterialTheme.colorScheme.tertiary
    } else {
        val p = probe
        when {
            p == null -> {
                text = stringResource(R.string.replica_status_waiting)
                color = MaterialTheme.colorScheme.tertiary
            }
            p.ok -> {
                text = stringResource(R.string.replica_status_ok, p.ms)
                color = MaterialTheme.colorScheme.primary
            }
            else -> {
                val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(p.at))
                text = stringResource(
                    R.string.replica_status_fail,
                    p.detail ?: "erreur réseau",
                    at
                )
                color = MaterialTheme.colorScheme.error
            }
        }
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
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
