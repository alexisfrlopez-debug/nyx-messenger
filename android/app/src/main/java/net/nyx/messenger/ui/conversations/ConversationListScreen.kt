package net.nyx.messenger.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
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
import net.nyx.messenger.R
import net.nyx.messenger.crypto.AliasValidator
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.tor.TorManager

/**
 * Liste des conversations + ajout de contact (v0.2.0) :
 *  - par ALIAS UNIQUE (invitations « @pseudo » — résolution GET /client/alias) ;
 *  - par adresse onion collée (56 caractères) ou lien nyx://add.
 * Une bannière reflète l'état du Tor embarqué (ADR-007) — jamais de trafic hors Tor.
 * Le menu (⋮) ouvre : Mon adresse Nyx · Mon profil · Paramètres.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    pendingAdd: String? = null,
    onOpenConversation: (String) -> Unit,
    onOpenMyOnion: () -> Unit = {},
    onOpenMyProfile: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val db = ServiceLocator.database
    val scope = rememberCoroutineScope()
    val contacts by db.contactDao().all().collectAsState(initial = emptyList())

    var showAdd by remember { mutableStateOf(pendingAdd != null) }
    var input by remember { mutableStateOf(pendingAdd ?: "") }
    var aliasInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var addInfo by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val repo = remember { ServiceLocator.repository }
    val context = LocalContext.current

    val torState by TorManager.get(LocalContext.current).state.collectAsState()

    // Claim d'alias différé : si l'alias choisi à l'onboarding n'a pas pu être
    // réservé (aucun réplica joignable), il part dès que le Tor embarqué est prêt.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2_000) // laisse le bootstrap Tor démarrer
        repo.ensureAliasClaimed()
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
                                // Adresse directe
                                repo.addContact(value, aliasInput)
                                    .onSuccess {
                                        showAdd = false; input = ""; aliasInput = ""
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
                items(contacts, key = { it.onion }) { c ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        onClick = { onOpenConversation(c.onion) }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(c.alias, style = MaterialTheme.typography.titleMedium)
                            Text(
                                c.onion.take(20) + "…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
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
