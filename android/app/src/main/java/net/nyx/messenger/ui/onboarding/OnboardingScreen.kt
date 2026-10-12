package net.nyx.messenger.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.R
import net.nyx.messenger.crypto.AliasValidator
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.repo.NyxRepository
import net.nyx.messenger.tor.TorManager

/**
 * Onboarding : création de l'identité (paire ed25519 + adresse onion), affichage
 * de l'adresse avec avertissement de sauvegarde (clé perdue = compte perdu — ADR-003).
 *
 * v0.1.2 :
 *  - choix d'un ALIAS unique optionnel (pseudonyme pour les invitations —
 *    wire-format.md §6) : validation format locale, vérification de disponibilité
 *    sur le réplica rattaché quand il est joignable, suggestions si pris ;
 *  - opt-in du VERROU BIOMÉTRIQUE local (ADR-010 §3) : l'OS vérifie l'utilisateur,
 *    l'app ne reçoit qu'un succès/échec — aucune donnée biométrique collectée.
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
            Spacer(Modifier.height(24.dp))
            AliasSection()
            Spacer(Modifier.height(16.dp))
            net.nyx.messenger.ui.components.BiometricSwitch()
            Spacer(Modifier.height(24.dp))
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

/**
 * Choix de l'alias : champ optionnel, normalisation, vérification en ligne
 * (réplica rattaché) et suggestions locales si le format est invalide.
 * Le claim signé part immédiatement si un réplica est joignable, sinon il est
 * différé à la première connexion ([NyxRepository.ensureAliasClaimed]).
 */
@Composable
private fun AliasSection() {
    var aliasInput by remember { mutableStateOf(ServiceLocator.identity.alias ?: "") }
    var status by remember { mutableStateOf<String?>(null) }   // état : libre / pris / différé
    var isError by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repo = ServiceLocator.repository

    Text(stringResource(R.string.alias_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.alias_optional), style = MaterialTheme.typography.bodySmall)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("@", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = aliasInput,
            onValueChange = {
                aliasInput = it
                status = null
                isError = false
            },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            isError = isError,
            supportingText = if (isError) {
                { Text(stringResource(R.string.alias_invalid)) }
            } else null,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                val name = AliasValidator.normalize(aliasInput)
                aliasInput = name
                if (!AliasValidator.isValid(name)) {
                    isError = true
                    suggestions = AliasValidator.suggestions(name)
                    status = null
                    return@Button
                }
                checking = true
                status = null
                scope.launch {
                    // 1. Vérification en ligne (réplica rattaché — différé s'il n'y en a pas)
                    val info = repo.checkAlias(name).getOrNull()
                    // 2. Réservation locale immédiate (claim différé si hors ligne)
                    withContext(Dispatchers.IO) { ServiceLocator.identity.setAlias(name) }
                    when {
                        info == null -> status = "PENDING"
                        info.taken -> {
                            status = "TAKEN"
                            suggestions = AliasValidator.suggestions(name)
                        }
                        else -> {
                            status = "FREE"
                            repo.claimAlias(name).onSuccess {
                                withContext(Dispatchers.IO) { ServiceLocator.identity.markAliasClaimed() }
                            }
                        }
                    }
                    checking = false
                }
            },
            enabled = !checking && aliasInput.isNotBlank(),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(if (checking) R.string.alias_checking else R.string.alias_check))
        }
    }

    when (status) {
        "FREE" -> Text(
            stringResource(R.string.alias_free, aliasInput),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium
        )
        "TAKEN" -> Text(
            stringResource(R.string.alias_taken),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        "PENDING" -> Text(
            stringResource(R.string.alias_pending, aliasInput),
            style = MaterialTheme.typography.bodyMedium
        )
    }

    if (suggestions.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.alias_suggestions), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.take(3).forEach { s ->
                TextButton(onClick = {
                    aliasInput = s
                    status = null
                    suggestions = emptyList()
                }) { Text("@$s") }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.alias_for_invites), style = MaterialTheme.typography.bodySmall)
}
