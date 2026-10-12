package net.nyx.messenger.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.R
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.tor.TorManager

/**
 * Onboarding : création de l'identité (paire ed25519 + adresse onion), affichage
 * de l'adresse avec avertissement de sauvegarde (clé perdue = compte perdu — ADR-003).
 */
@Composable
fun OnboardingScreen(onIdentityCreated: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var onion by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors()) {
            Text(
                stringResource(R.string.onboarding_warning),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(24.dp))

        onion?.let { addr ->
            Text(stringResource(R.string.your_onion), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                addr,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("nyx_onion", addr))
                    copied = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (copied) R.string.copied else R.string.copy))
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onIdentityCreated, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.continuer))
            }
        } ?: run {
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val created = withContext(Dispatchers.IO) {
                                ServiceLocator.identity.createIfNeeded()
                            }
                            onion = created
                            // Tor embarqué (ADR-007) : démarre dès que l'identité existe —
                            // le bootstrap et la publication du service onion de
                            // l'utilisateur se font pendant qu'il lit son adresse.
                            TorManager.get(context).start { ServiceLocator.identity.secretKey() }
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) stringResource(R.string.onboarding_creating) else stringResource(R.string.onboarding_create))
            }
            OutlinedButton(
                onClick = { /* TODO: restauration par phrase secrète (v0.2) */ },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(stringResource(R.string.restore_backup)) }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
