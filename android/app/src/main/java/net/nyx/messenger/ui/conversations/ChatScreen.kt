package net.nyx.messenger.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.nyx.messenger.R
import net.nyx.messenger.data.db.MessageEntity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.repo.NyxRepository

/**
 * Écran de chat — l'envoi et la réception passent par [NyxRepository]
 * (chiffrement E2E + dépôt via Tor). Poll inbox toutes les 10 s (v0.1).
 * v0.2.0 : accès au profil public du contact (icône — GET /client/profile).
 * v0.2.11 : (a) le Flow Room est collecté de façon canonique — l'ancien
 * wrapper MutableStateFlow+collect ne rafraîchissait PAS l'écran en session
 * (constat terrain : le message tapé n'apparaissait qu'après relance) ;
 * (b) un échec d'envoi est AFFICHÉ (avant : callback vide = silence total).
 */
class ChatViewModel(peerOnion: String) : ViewModel() {
    private val db = ServiceLocator.database
    val repo = ServiceLocator.repository

    /** Conversation observable — re-émet à chaque écriture dans `messages`. */
    val messages: Flow<List<MessageEntity>> = db.messageDao().conversation(peerOnion)

    /** v0.2.11 : dernier échec d'envoi (null = rien à afficher). */
    val sendError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            while (true) {
                runCatching { repo.pollInbox() }
                delay(10_000)
            }
        }
    }

    fun send(peer: String, text: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            repo.send(peer, text).fold(
                onSuccess = { sendError.value = null; onDone(true) },
                onFailure = { e ->
                    sendError.value = e.message ?: "envoi impossible"
                    onDone(false)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(peerOnion: String, onOpenProfile: (String) -> Unit = {}) {
    val vm: ChatViewModel = viewModel(key = peerOnion, factory = viewModelFactory {
        ChatViewModel(peerOnion)
    })
    // v0.2.11 : collectAsState(initial) sur le Flow Room canonique — la liste se
    // met à jour IMMÉDIATEMENT après l'insertion locale (envoi ou réception).
    val messages by vm.messages.collectAsState(initial = emptyList())
    val sendError by vm.sendError.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(peerOnion.take(20) + "…") },
                actions = {
                    IconButton(onClick = { onOpenProfile(peerOnion) }) {
                        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.contact_profile_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                items(messages, key = { it.eventId }) { m ->
                    val outgoing = m.outgoing
                    Column(
                        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            m.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .padding(4.dp)
                        )
                        Text(
                            when (m.state) {
                                NyxRepository.STATE_PENDING -> stringResource(R.string.sending)
                                NyxRepository.STATE_SENT -> stringResource(R.string.sent)
                                NyxRepository.STATE_FAILED -> stringResource(R.string.failed)
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.message_hint)) }
                )
                IconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            input = ""
                            vm.send(peerOnion.trim().lowercase(), text) { ok ->
                                if (!ok) android.util.Log.w("nyx-send", "envoi signalé en échec à l'UI")
                            }
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                }
            }
            // v0.2.11 : l'échec d'envoi n'est plus muet — l'opérateur voit POURQUOI.
            sendError?.let {
                Text(
                    stringResource(R.string.send_error_prefix) + " " + it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** Petite fabrique locale (évite une dépendance viewmodel-compose supplémentaire). */
private inline fun <VM : ViewModel> viewModelFactory(crossinline create: () -> VM):
        androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
