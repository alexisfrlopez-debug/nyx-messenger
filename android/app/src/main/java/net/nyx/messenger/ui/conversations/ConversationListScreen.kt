package net.nyx.messenger.ui.conversations

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import net.nyx.messenger.BuildConfig
import net.nyx.messenger.R
import net.nyx.messenger.crypto.AliasValidator
import net.nyx.messenger.data.db.ContactEntity
import net.nyx.messenger.data.db.ContactStatus
import net.nyx.messenger.data.db.MessageEntity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.p2p.CallManager
import net.nyx.messenger.tor.TorManager
import net.nyx.messenger.ui.components.AvatarView
import net.nyx.messenger.ui.components.ReplicaProbeEffect
import net.nyx.messenger.ui.diag.TestLogExportCard
import net.nyx.messenger.ui.diag.TestPlanCard

/**
 * Liste principale — v0.2.29 : TROIS ONGLETS (demande opérateur).
 *
 * Onglet « Conversations » (défaut) : les conversations triées par ordre
 * CHRONOLOGIQUE du dernier échange (le plus récent d'abord), chaque carte
 * montrant l'aperçu du dernier message + indicateurs (non lus / ✓✓) ;
 * boutons « Nouvelle conversation » (sélecteur de contacts acceptés) et
 * « Nouveau groupe » (les groupes ne sont pas encore disponibles — l'info
 * est affichée honnêtement, l'architecture E2E est pairwise).
 *
 * Onglet « Contacts » : liste ALPHABÉTIQUE des contacts acceptés avec, devant
 * chacun, des raccourcis cliquables Message / Appel / Visio (demande
 * opérateur). Le bouton « Ajouter un contact » et son formulaire (alias
 * @pseudo ou adresse onion) ont MIGRÉ ici depuis la liste unique v0.2.0-28.
 * Un lien d'invitation (pendingAdd) atterrit directement sur cet onglet.
 *
 * Onglet « Blocages » (v0.2.29) : gestion de la liste de blocage locale —
 * un contact bloqué ne peut ni écrire, ni appeler, ni découvrir notre
 * réplica temporaire ; débloquer restaure l'état exact d'avant.
 *
 * Historique conservé :
 *  - bannière Tor (ADR-007) + ligne d'état du réplica au-dessus des onglets ;
 *  - en-tête compte (alias + photo → mon profil), menu (⋮) inchangé ;
 *  - pression longue sur une carte = menu : Fiche / Bloquer / Supprimer le
 *    contact (propage l'avis au pair) / Supprimer la conversation (local) ;
 *  - « Anciennes conversations » (refusés/supprimés) en bas de Conversations ;
 *  - demande runtime POST_NOTIFICATIONS (Android 13+) à l'entrée.
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
    val repo = remember { ServiceLocator.repository }
    val context = LocalContext.current

    val contacts by db.contactDao().allActive().collectAsState(initial = emptyList())
    val archived by db.contactDao().allArchived().collectAsState(initial = emptyList())
    // v0.2.29 — liste de blocage (onglet dédié).
    val blocked by db.contactDao().allBlocked().collectAsState(initial = emptyList())

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

    // v0.2.29 — onglet courant (persisté à la rotation) ; un lien
    // d'invitation (pendingAdd) ouvre l'onglet Contacts, formulaire ouvert.
    var tab by rememberSaveable {
        mutableIntStateOf(if (pendingAdd != null) TAB_CONTACTS else TAB_CONVERSATIONS)
    }
    var showAdd by remember { mutableStateOf(pendingAdd != null) }
    var input by remember { mutableStateOf(pendingAdd ?: "") }
    var aliasInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var addInfo by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // v0.2.29 — dialogues des onglets.
    var showGroupInfo by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var blockTarget by remember { mutableStateOf<ContactEntity?>(null) }

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
    // v0.2.15 : charge le profil PROPRE (avatar de l'en-tête) — après la
    // déclaration de context (l'écran recharge au retour de l'édition).
    LaunchedEffect(Unit) {
        ownProfile = net.nyx.messenger.profile.ProfileStore.get(context).load()
    }

    val torState by TorManager.get(LocalContext.current).state.collectAsState()

    // Onglet Contacts : contacts ACCEPTÉS triés alphabétiquement (allActive est
    // déjà trié par alias ; on filtre le statut pour n'offrir raccourcis
    // message/appel/visio qu'aux liens mutuels — un « pending » ne peut rien
    // recevoir tant que l'invitation n'est pas acceptée des deux côtés).
    val accepted = remember(contacts) {
        contacts.filter { it.status == ContactStatus.ACCEPTED }
            .sortedBy { it.alias.lowercase() }
    }
    // Onglet Conversations : tri CHRONOLOGIQUE du dernier échange (demande
    // opérateur : « l'onglet conversations liste les conversations par ordre
    // chronologique du dernier échange ») — sans message, après (par alias).
    val conversations = remember(contacts, lastMap) {
        contacts.sortedWith(
            compareByDescending<ContactEntity> { lastMap[it.onion]?.sentAt ?: 0L }
                .thenBy { it.alias.lowercase() }
        )
    }

    Scaffold(
        containerColor = Color.Transparent, // v0.2.35 : laisse voir le dégradé de nuit racine
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
        // v0.2.38 — DÉFILEMENT GARANTI PAR CONSTRUCTION (retour opérateur :
        // « l'écran principal de la messagerie ne défile pas, il faudrait
        // vérifier systématiquement le défilement de toutes les fenêtres »).
        // Cause racine de la récidive : la zone FIXE (bannières + cartes de
        // diagnostic du build test) vivait AVANT les onglets SANS plafond —
        // sur un appareil compact (ou grande police, bannières d'erreur
        // multi-lignes, carte plan déployée), elle pouvait occuper à elle
        // seule plus que l'écran : la zone d'onglets weight(1f) — confinée au
        // RESTANT depuis v0.2.31 — tombait alors à ~0 px (plus rien ne
        // défile, le bas est inatteignable). Désormais la zone fixe est
        // PLAFONNÉE à [ScrollPolicy.HEADER_MAX_FRACTION] de la hauteur du
        // corps (elle défile EN INTERNE si son contenu déborde — cartes de
        // diagnostic accessibles en tout état de cause) et la zone d'onglets
        // reçoit TOUT le restant : elle garde TOUJOURS au moins
        // [ScrollPolicy.LIST_MIN_FRACTION] de l'écran. Invariant verrouillé
        // par ScrollPolicyTest.
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding)
        ) {
            // Plafond de la zone fixe — capturé ICI (portée BoxWithConstraints)
            // car les lambdas imbriquées (Colonnes) masquent le récepteur.
            val headerMax = ScrollPolicy.headerMaxDp(maxHeight.value).dp
        Column(
            Modifier.fillMaxSize().padding(16.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = headerMax)
                    .verticalScroll(rememberScrollState())
            ) {
            TorBanner(torState)
            // v0.2.16 — état du RÉPLICA sous la bannière Tor (retour terrain :
            // « après fermeture/réouverture, le client ne se connecte plus au
            // réplica » — sans indicateur, impossible de distinguer une reprise
            // en cours d’une panne réelle). La sonde tourne ici (20 s) dès que
            // Tor est prêt ; le résultat détaillé est observé via lastProbe.
            val torReady = torState is TorManager.TorState.Ready
            ReplicaProbeEffect(torReady = torReady, intervalMs = 20_000L)
            ReplicaStatusLine(repo, torReady)
            // v0.2.40 — BANNIÈRE MISE À JOUR (demande opérateur : « la version
            // test se met à jour dès qu'une nouvelle version est poussée sur
            // github ») : quand une mise à jour a été téléchargée (canal test :
            // dernière release publiée, pré-releases incluses ; canal standard :
            // dernière validée), la bannière apparaît EN TÊTE de l'écran
            // principal — un appui lance l'installateur système. Visible sur
            // les DEUX builds (le standard bénéficie du même raccourci).
            UpdateBanner()
            // Build test : gros bouton rouge d'export de logs vers le Drive (v0.2.2)
            // + PLAN DE TESTS de validation (v0.2.20 — demande opérateur : listing
            // des tests à effectuer, résultats journalisés nyx-test). v0.2.38 :
            // les deux cartes sont REPLIÉES par défaut (zone fixe compacte).
            if (BuildConfig.TEST_BUILD) {
                Spacer(Modifier.height(8.dp))
                TestLogExportCard()
                Spacer(Modifier.height(8.dp))
                TestPlanCard()
            }
            }

            // ── v0.2.29 — ONGLETS ──
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == TAB_CONVERSATIONS,
                    onClick = { tab = TAB_CONVERSATIONS },
                    text = { Text(stringResource(R.string.conversations)) }
                )
                Tab(
                    selected = tab == TAB_CONTACTS,
                    onClick = { tab = TAB_CONTACTS },
                    text = { Text(stringResource(R.string.tab_contacts)) }
                )
                Tab(
                    selected = tab == TAB_BLOCKED,
                    onClick = { tab = TAB_BLOCKED },
                    text = { Text(stringResource(R.string.tab_blocked)) }
                )
            }
            Spacer(Modifier.height(8.dp))
            // v0.2.31 — onglet confiné à la hauteur RESTANTE (weight(1f)) ;
            // v0.2.38 : le restant est GARANTI non nul (zone fixe plafonnée
            // ci-dessus, voir ScrollPolicy) — le verticalScroll interne de
            // chaque onglet défile dans un espace toujours substantiel.
            Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                TAB_CONTACTS -> ContactsTab(
                    accepted = accepted,
                    showAdd = showAdd,
                    input = input,
                    aliasInput = aliasInput,
                    addError = addError,
                    addInfo = addInfo,
                    onShowAdd = { showAdd = true },
                    onInput = { input = it },
                    onAliasInput = { aliasInput = it },
                    onAdd = {
                        addError = null
                        addInfo = null
                        val value = input.trim()
                        if (value.isEmpty()) return@ContactsTab
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
                    },
                    onOpenConversation = onOpenConversation,
                    onOpenContactProfile = onOpenContactProfile,
                    onBlock = { blockTarget = it }
                )
                TAB_BLOCKED -> BlockedTab(
                    blocked = blocked,
                    onUnblock = { c ->
                        scope.launch { repo.setContactBlocked(c.onion, false) }
                    }
                )
                else -> ConversationsTab(
                    conversations = conversations,
                    archived = archived,
                    lastMap = lastMap,
                    unreadMap = unreadMap,
                    onOpenConversation = onOpenConversation,
                    onOpenContactProfile = onOpenContactProfile,
                    onNewConversation = { showPicker = true },
                    onNewGroup = { showGroupInfo = true },
                    onBlock = { blockTarget = it }
                )
            }
            }
        }
        }
    }

    // ── Dialogues (v0.2.29) ──

    // « Nouvelle conversation » : sélecteur parmi les contacts acceptés.
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.new_conversation)) },
            text = {
                if (accepted.isEmpty()) {
                    Text(stringResource(R.string.picker_empty))
                } else {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        for (c in accepted) {
                            TextButton(onClick = { showPicker = false; onOpenConversation(c.onion) }) {
                                Text(c.alias, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    // « Nouveau groupe » : pas encore disponible (architecture E2E pairwise) —
    // l'information est honnête, le bouton demandé par l'opérateur est présent.
    if (showGroupInfo) {
        AlertDialog(
            onDismissRequest = { showGroupInfo = false },
            title = { Text(stringResource(R.string.group_info_title)) },
            text = { Text(stringResource(R.string.group_info_text)) },
            confirmButton = {
                TextButton(onClick = { showGroupInfo = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    // Confirmation de blocage (demande opérateur : « bloquer/débloquer un
    // contact ») — le blocage est PUREMENT LOCAL (le pair n'est jamais informé).
    blockTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { blockTarget = null },
            title = { Text(stringResource(R.string.block_confirm_title)) },
            text = { Text(stringResource(R.string.block_confirm_text, target.alias)) },
            confirmButton = {
                TextButton(onClick = {
                    val t = target
                    blockTarget = null
                    scope.launch { repo.setContactBlocked(t.onion, true) }
                }) {
                    Text(
                        stringResource(R.string.block_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { blockTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/**
 * Onglet « Conversations » (v0.2.29) — tri CHRONOLOGIQUE du dernier échange,
 * boutons « Nouvelle conversation » (sélecteur) et « Nouveau groupe », puis
 * les cartes actives et la section « Anciennes conversations » (v0.2.14).
 */
@Composable
private fun ConversationsTab(
    conversations: List<ContactEntity>,
    archived: List<ContactEntity>,
    lastMap: Map<String, MessageEntity>,
    unreadMap: Map<String, Int>,
    onOpenConversation: (String) -> Unit,
    onOpenContactProfile: (String) -> Unit,
    onNewConversation: () -> Unit,
    onNewGroup: () -> Unit,
    onBlock: (ContactEntity) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            OutlinedButton(
                onClick = onNewConversation,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.new_conversation),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = onNewGroup,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.GroupAdd, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.new_group),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (conversations.isEmpty()) {
            Text(
                stringResource(R.string.conversations_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        for (c in conversations) {
            ContactCard(
                c = c,
                archived = false,
                last = lastMap[c.onion],
                unread = unreadMap[c.onion] ?: 0,
                onOpenConversation = onOpenConversation,
                onOpenContactProfile = onOpenContactProfile,
                onBlock = onBlock
            )
        }
        // ── Anciennes conversations (refusés/supprimés) — v0.2.14 ──
        if (archived.isNotEmpty()) {
            Text(
                stringResource(R.string.list_archived_header),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            for (c in archived) {
                ContactCard(
                    c = c,
                    archived = true,
                    last = lastMap[c.onion],
                    unread = 0, // un lien archivé ne reçoit plus — pas d'indicateur
                    onOpenConversation = onOpenConversation,
                    onOpenContactProfile = onOpenContactProfile,
                    onBlock = null // un lien archivé n'a pas à être bloqué
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Onglet « Contacts » (v0.2.29) — « Ajouter un contact » (MIGRÉ de la liste
 * unique, demande opérateur) + liste ALPHABÉTIQUE des contacts acceptés avec
 * raccourcis Message / Appel / Visio devant chacun.
 */
@Composable
private fun ContactsTab(
    accepted: List<ContactEntity>,
    showAdd: Boolean,
    input: String,
    aliasInput: String,
    addError: String?,
    addInfo: String?,
    onShowAdd: () -> Unit,
    onInput: (String) -> Unit,
    onAliasInput: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenContactProfile: (String) -> Unit,
    onBlock: (ContactEntity) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        if (showAdd) {
            // Un seul champ : alias (@pseudo) OU adresse onion — détection auto.
            OutlinedTextField(
                value = input, onValueChange = onInput,
                label = { Text(stringResource(R.string.add_contact_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = aliasInput, onValueChange = onAliasInput,
                label = { Text(stringResource(R.string.add_contact_local_alias)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Button(
                onClick = onAdd,
                modifier = Modifier.padding(top = 8.dp)
            ) { Text(stringResource(R.string.add)) }
            addError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            addInfo?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        } else {
            Button(onClick = onShowAdd) { Text(stringResource(R.string.add_contact)) }
        }
        addInfo?.let {
            if (!showAdd) Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (accepted.isEmpty()) {
            Text(
                stringResource(R.string.contacts_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        for (c in accepted) {
            ContactShortcutRow(
                c = c,
                onOpenConversation = onOpenConversation,
                onOpenContactProfile = onOpenContactProfile,
                onBlock = onBlock
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Rangée « Contacts » (v0.2.29) : avatar + alias, puis les TROIS RACCOURCIS
 * cliquables demandés par l'opérateur — Message (ouvre la conversation),
 * Appel (audio), Visio (vidéo). Clic sur la rangée = conversation ;
 * pression longue = menu Fiche / Bloquer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactShortcutRow(
    c: ContactEntity,
    onOpenConversation: (String) -> Unit,
    onOpenContactProfile: (String) -> Unit,
    onBlock: (ContactEntity) -> Unit
) {
    val repo = remember { ServiceLocator.repository }
    // v0.2.15 : profil public en cache du pair (avatar — apparaît dès que le
    // fetch de rafraîchissement aboutit).
    val peerProfile by repo.peerProfileFlow(c.onion).collectAsState(initial = null)
    var menuOpen by remember { mutableStateOf(false) }
    val label = c.alias.ifBlank { c.onion.take(10) + "…" }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .combinedClickable(
                    onClick = { onOpenConversation(c.onion) },
                    onLongClick = { menuOpen = true }
                )
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp)
        ) {
            AvatarView(peerProfile?.avatar?.ifBlank { null }, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                c.alias,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onOpenConversation(c.onion) }) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.cd_message),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { CallManager.call(c.onion, label, withVideo = false) }) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = stringResource(R.string.call_start),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { CallManager.call(c.onion, label, withVideo = true) }) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = stringResource(R.string.call_start_video),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.contact_fiche)) },
                    onClick = { menuOpen = false; onOpenContactProfile(c.onion) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.block_action)) },
                    onClick = { menuOpen = false; onBlock(c) }
                )
            }
        }
    }
}

/**
 * Onglet « Blocages » (v0.2.29) — la liste des contacts bloqués avec
 * « Débloquer » ; rappel du sens du blocage (purement local).
 */
@Composable
private fun BlockedTab(
    blocked: List<ContactEntity>,
    onUnblock: (ContactEntity) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Text(
            stringResource(R.string.blocked_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        if (blocked.isEmpty()) {
            Text(
                stringResource(R.string.blocked_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        for (c in blocked) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                ) {
                    // Pas d'avatar : AUCUN fetch de profil vers un pair bloqué.
                    AvatarView(null, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.alias,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(
                                R.string.blocked_since,
                                java.text.SimpleDateFormat(
                                    "dd/MM/yyyy", java.util.Locale.getDefault()
                                ).format(java.util.Date(c.blockedAt))
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    TextButton(onClick = { onUnblock(c) }) {
                        Text(stringResource(R.string.unblock))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * v0.2.14 — carte d'un contact (actif ou archivé) : ALIAS seul (l'adresse
 * onion n'est visible QUE dans la fiche — bouton ⓘ), statut d'invitation,
 * boutons Accepter/Refuser sur les invitations reçues.
 *
 * v0.2.29 — pression longue = MENU (Fiche / Bloquer / Supprimer le contact /
 * Supprimer la conversation) au lieu du seul dialogue de suppression :
 * le BLOCAGE (demande opérateur) vit à côté des suppressions.
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
    onOpenContactProfile: (String) -> Unit,
    onBlock: ((net.nyx.messenger.data.db.ContactEntity) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val repo = remember { ServiceLocator.repository }
    var menuOpen by remember { mutableStateOf(false) }
    var askDeleteContact by remember { mutableStateOf(false) }
    var askDeleteConversation by remember { mutableStateOf(false) }
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
                    onLongClick = { menuOpen = true }
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
            // v0.2.29 — menu contextuel (pression longue) : Fiche / Bloquer /
            // Supprimer le contact / Supprimer la conversation.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.contact_fiche)) },
                    onClick = { menuOpen = false; onOpenContactProfile(c.onion) }
                )
                if (!archived && onBlock != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.block_action)) },
                        onClick = { menuOpen = false; onBlock(c) }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete_contact_title)) },
                    onClick = { menuOpen = false; askDeleteContact = true }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete_conversation_confirm)) },
                    onClick = { menuOpen = false; askDeleteConversation = true }
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
    if (askDeleteContact) {
        AlertDialog(
            onDismissRequest = { askDeleteContact = false },
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = { Text(stringResource(R.string.delete_contact_text, c.alias)) },
            confirmButton = {
                TextButton(onClick = {
                    askDeleteContact = false
                    scope.launch { repo.deleteContact(c.onion) }
                }) { Text(stringResource(R.string.delete_contact_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askDeleteContact = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (askDeleteConversation) {
        AlertDialog(
            onDismissRequest = { askDeleteConversation = false },
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = { Text(stringResource(R.string.delete_conversation_text, c.alias)) },
            confirmButton = {
                TextButton(onClick = {
                    askDeleteConversation = false
                    scope.launch { repo.deleteConversation(c.onion) }
                }) { Text(stringResource(R.string.delete_conversation_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askDeleteConversation = false }) {
                    Text(stringResource(R.string.cancel))
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
 * v0.2.40 — BANNIÈRE MISE À JOUR AUTOMATIQUE (demande opérateur : « la version
 * test se met à jour automatiquement dès qu'une nouvelle version est poussée
 * sur github »). Compacte et NON INTRUSIVE dans la zone fixe de l'écran
 * principal (le plafond v0.2.38 doit rester maîtrisé — ScrollPolicy) :
 *  - READY (APK déjà téléchargé, canal test ou standard) : carte CLIQUABLE
 *    « Nouvelle version X prête — installer » → installateur système
 *    (confirmation Android) ; disparaît dès l'installation ;
 *  - DOWNLOADING : simple ligne de progression discrète (le détail vit dans
 *    la carte Paramètres) ;
 *  - tous les autres états : RIEN (à jour / erreur réseau = silence).
 */
@Composable
private fun UpdateBanner() {
    val state by net.nyx.messenger.update.Updater.state.collectAsState()
    val testChannel = BuildConfig.TEST_BUILD
    when (val st = state) {
        is net.nyx.messenger.update.Updater.State.Ready -> {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { net.nyx.messenger.update.Updater.install(st.apk) },
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    stringResource(
                        R.string.update_banner_ready,
                        if (testChannel) "${st.release.version} (test)" else st.release.version
                    ),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        is net.nyx.messenger.update.Updater.State.Downloading -> {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.update_banner_downloading, st.release.version),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        else -> {}
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

/** v0.2.29 — indices des onglets de la liste principale. */
private const val TAB_CONVERSATIONS = 0
private const val TAB_CONTACTS = 1
private const val TAB_BLOCKED = 2

/**
 * v0.2.38 — POLITIQUE DE DÉFILEMENT DE L'ÉCRAN PRINCIPAL (retour opérateur :
 * « l'écran principal de la messagerie ne défile pas, il faudrait vérifier
 * systématiquement le défilement de toutes les fenêtres »).
 *
 * AVANT : la zone fixe (bannières + cartes de diagnostic du build test)
 * n'avait AUCUN plafond — sur un écran compact (ou grande police, bannières
 * d'erreur multi-lignes) elle pouvait dépasser l'écran entier et la zone
 * d'onglets (weight(1f), confinée au restant depuis v0.2.31) tombait à
 * ~0 px : plus rien ne défilait. MAINTENANT : la zone fixe est plafonnée à
 * [HEADER_MAX_FRACTION] de la hauteur du corps et défile EN INTERNE si elle
 * déborde ; la zone d'onglets garde TOUJOURS au moins [LIST_MIN_FRACTION]
 * de l'écran (moins le chrome des onglets).
 *
 * Objet VOLONTAIREMENT pur Kotlin (Float, aucun type Compose) : l'invariant
 * est vérifié par [net.nyx.messenger.ui.ScrollPolicyTest] en JVM.
 */
internal object ScrollPolicy {
    /** Part MAXIMALE de la hauteur du corps dévolue à la zone fixe. */
    const val HEADER_MAX_FRACTION = 0.40f

    /** Part MINIMALE garantie à la zone d'onglets (listes défilantes). */
    const val LIST_MIN_FRACTION = 1f - HEADER_MAX_FRACTION

    /** Chrome des onglets (TabRow 48 dp + espace 8 dp), en dp. */
    const val TAB_CHROME_DP = 56f

    /** Plafond de la zone fixe (dp) pour une hauteur de corps donnée (dp). */
    fun headerMaxDp(availableDp: Float): Float = availableDp * HEADER_MAX_FRACTION

    /** Hauteur MINIMALE GARANTIE de la zone d'onglets (dp) pour un corps donné. */
    fun listMinDp(availableDp: Float): Float =
        availableDp * LIST_MIN_FRACTION - TAB_CHROME_DP
}
