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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.nyx.messenger.R
import net.nyx.messenger.data.db.MessageEntity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.repo.NyxRepository

/**
 * Écran de chat — l'envoi et la réception passent par [NyxRepository]
 * (chiffrement E2E + dépôt via Tor). Poll inbox toutes les 10 s (v0.1).
 */
class ChatViewModel(peerOnion: String) : ViewModel() {
    private val db = ServiceLocator.database
    val repo = ServiceLocator.repository

    val messages: StateFlow<List<MessageEntity>> =
        db.messageDao().conversation(peerOnion)
            .let { flow -> MutableStateFlow<List<MessageEntity>>(emptyList()).also { out ->
                viewModelScope.launch { flow.collect { out.value = it } }
            } }

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
            repo.send(peer, text).fold({ onDone(true) }, { onDone(false) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(peerOnion: String) {
    val vm: ChatViewModel = viewModel(key = peerOnion, factory = viewModelFactory {
        ChatViewModel(peerOnion)
    })
    val messages by vm.messages.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(peerOnion.take(20) + "…") })
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
                            vm.send(peerOnion, text) {}
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                }
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
