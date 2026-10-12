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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.nyx.messenger.R
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.repo.NyxRepository

/**
 * Liste des conversations + ajout de contact (adresse onion collée ou lien nyx://add).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    pendingAdd: String? = null,
    onOpenConversation: (String) -> Unit
) {
    val db = ServiceLocator.database
    val scope = rememberCoroutineScope()
    val contacts by db.contactDao().all().collectAsState(initial = emptyList())

    var showAdd by remember { mutableStateOf(pendingAdd != null) }
    var onionInput by remember { mutableStateOf(pendingAdd ?: "") }
    var aliasInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    val repo = remember {
        NyxRepository(
            ServiceLocator.identity, ServiceLocator.cipher,
            db.messageDao(), db.contactDao(), db.pollStateDao()
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.conversations)) }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (showAdd) {
                OutlinedTextField(
                    value = onionInput, onValueChange = { onionInput = it },
                    label = { Text(stringResource(R.string.add_contact_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = aliasInput, onValueChange = { aliasInput = it },
                    label = { Text("Alias (local)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Button(onClick = {
                        scope.launch {
                            repo.addContact(onionInput, aliasInput)
                                .onSuccess { showAdd = false; onionInput = ""; aliasInput = "" }
                                .onFailure { addError = it.message }
                        }
                    }) { Text(stringResource(R.string.add)) }
                    addError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            } else {
                Button(onClick = { showAdd = true }) { Text(stringResource(R.string.add_contact)) }
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
