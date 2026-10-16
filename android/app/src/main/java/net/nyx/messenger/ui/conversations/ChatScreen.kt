package net.nyx.messenger.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
 * v0.2.15 (demande opérateur) :
 *  - CLAVIER : la fenêtre se REDIMENSIONNE quand le clavier s'ouvre — le champ
 *    de saisie n'est plus caché (cause : enableEdgeToEdge() désactive l'effet
 *    d'adjustResize du manifest sur API 30+ ; le remède Compose est imePadding) ;
 *  - ACCUSÉS : sous chaque message sortant — ✓ Envoyé / ✓✓ Reçu / ✓✓ Lu ;
 *  - LECTURE : ouvrir la conversation marque les messages reçus « lus »
 *    (indicateur non-lu de la liste) et dépose les accusés « read » au pair.
 */
class ChatViewModel(peerOnion: String) : ViewModel() {
    private val db = ServiceLocator.database
    val repo = ServiceLocator.repository

    /** Conversation observable — re-émet à chaque écriture dans `messages`. */
    val messages: Flow<List<MessageEntity>> = db.messageDao().conversation(peerOnion)

    /** v0.2.11 : dernier échec d'envoi (null = rien à afficher). */
    val sendError = MutableStateFlow<String?>(null)

    /** v0.2.14 : ligne contact du pair (alias + statut — bannière non mutuel). */
    val contact = db.contactDao().byOnion(peerOnion.trim().lowercase())

    init {
        viewModelScope.launch {
            while (true) {
                runCatching { repo.pollInbox() }
                delay(10_000)
            }
        }
        // v0.2.15 — MARQUAGE « LU » à l'ouverture de la conversation (et à
        // chaque arrivée pendant qu'elle est ouverte) : le Flow re-émet après
        // l'écriture readLocal=1, la boucle s'arrête d'elle-même (plus de
        // non-lu). Les accusés « read » partent dans la foulée.
        viewModelScope.launch {
            messages.collect { list ->
                if (list.any { !it.outgoing && it.readLocal == 0 }) {
                    runCatching { repo.markConversationRead(peerOnion) }
                }
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(peerOnion: String, onOpenProfile: (String) -> Unit = {}) {
    val vm: ChatViewModel = viewModel(key = peerOnion, factory = viewModelFactory {
        ChatViewModel(peerOnion)
    })
    // v0.2.11 : collectAsState(initial) sur le Flow Room canonique — la liste se
    // met à jour IMMÉDIATEMENT après l'insertion locale (envoi ou réception).
    val messages by vm.messages.collectAsState(initial = emptyList())
    val sendError by vm.sendError.collectAsState()
    // v0.2.14 : contact observable — alias en titre, bannière si non mutuel.
    val contact by vm.contact.collectAsState(initial = null)
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // v0.2.15 : le clavier s'ouvre → la liste reste collée au bas (dernier
    // message visible au-dessus du champ de saisie redimensionné).
    // NOTE : isImeVisible se lit DANS la composition (capturé dans imeVisible)
    // — une lecture dans la coroutine du LaunchedEffect serait hors contexte.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        // v0.2.15 — CORRECTIF CLAVIER (demande opérateur : « quand le clavier
        // s'ouvre pour écrire, je veux que la fenêtre se resize pour que le
        // clavier ne cache pas le message que l'on est en train de taper »).
        // enableEdgeToEdge() (MainActivity) fait dessiner l'app derrière les
        // insets : adjustResize du manifest n'a PLUS d'effet sur API 30+ —
        // imePadding() relève tout le Scaffold (liste + saisie) au-dessus du
        // clavier, avec l'animation système.
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                // v0.2.14 : le titre affiche l'ALIAS du contact (jamais l'adresse
                // onion — visible uniquement dans la fiche, demande opérateur).
                title = {
                    Text(
                        contact?.alias?.ifBlank { null }
                            ?: (peerOnion.take(12) + "…")
                    )
                },
                actions = {
                    IconButton(onClick = { onOpenProfile(peerOnion) }) {
                        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.contact_profile_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // v0.2.14 : bannière si l'échange n'est pas (encore) mutuel —
            // l'envoi est de toute façon bloqué côté repository (verrou mutuel).
            if (contact?.status != net.nyx.messenger.data.db.ContactStatus.ACCEPTED) {
                Text(
                    stringResource(R.string.chat_not_mutual),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
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
                        // v0.2.15 : état + accusés UNIQUEMENT sur les sortants —
                        // « ✓ Envoyé » (déposé au réplica) → « ✓✓ Reçu » (accusé
                        // de réception du pair) → « ✓✓ Lu » (accusé de lecture).
                        // Les entrants restent muets (l'indicateur non-lu vit
                        // dans la liste, côté destinataire).
                        outgoingStatusLabel(m)?.let { label ->
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (m.readAt > 0L) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
