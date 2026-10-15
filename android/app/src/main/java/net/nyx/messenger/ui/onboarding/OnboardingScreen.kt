package net.nyx.messenger.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.R
import net.nyx.messenger.crypto.AliasValidator
import net.nyx.messenger.crypto.IdentityBackup
import net.nyx.messenger.crypto.RecoveryKey
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.repo.NyxRepository
import net.nyx.messenger.tor.TorManager

/**
 * Onboarding : création de l'identité (paire ed25519 + adresse onion), affichage
 * de l'adresse avec avertissement de sauvegarde (clé perdue = compte perdu — ADR-003).
 *
 * v0.1.2 : alias unique optionnel + opt-in du VERROU BIOMÉTRIQUE local (ADR-010 §3).
 *
 * v0.2.3 (demande opérateur + audit logs device) :
 *  - Tor démarre DÈS l'entrée sur l'écran (sans clé — rien à publier) ;
 *  - « Créer mon identité » n'est activé QUE quand la connexion au réplica
 *    d'amorçage est VÉRIFIÉE (GET /health via Tor) — plus de compte créé
 *    « à sec » puis délégataire d'un réseau absent ; sonde répétée toutes
 *    les 8 s jusqu'à joignabilité ;
 *  - CLÉ DE RÉCUPÉRATION affichée après création (export fichier SAF +
 *    copie) — la sauvegarde n'existait pas ;
 *  - « Restaurer une sauvegarde » est RÉEL : collage de la clé ou import du
 *    fichier, re-dérivation de la paire (même adresse garantie).
 */
@Composable
fun OnboardingScreen(onIdentityCreated: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var onion by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    var showRestore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val torMgr = TorManager.get(context)
    val torState by torMgr.state.collectAsState()

    // Sonde réplica (v0.2.3) : OK dès que GET /health répond via Tor.
    var replicaOk by remember { mutableStateOf(false) }
    var replicaNote by remember { mutableStateOf("") }

    // Tor démarre dès l'onboarding — la sonde réplica en dépend.
    LaunchedEffect(Unit) { torMgr.start() }

    LaunchedEffect(torState) {
        if (torState !is TorManager.TorState.Ready) { replicaOk = false; return@LaunchedEffect }
        val repo = ServiceLocator.repository
        while (true) {
            if (repo.replicas.isEmpty()) {
                replicaOk = false
                replicaNote = context.getString(R.string.replica_none_configured)
                break // rien à sonder : message explicite, pas de boucle infinie
            }
            replicaNote = context.getString(R.string.replica_checking)
            if (repo.replicaHealth()) {
                replicaOk = true
                replicaNote = context.getString(R.string.replica_ok, repo.replicas.size)
                break
            }
            replicaOk = false
            replicaNote = context.getString(R.string.replica_down)
            delay(8_000)
        }
    }

    // Export du fichier de sauvegarde (SAF — aucune permission).
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && onion != null && recoveryKey != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(IdentityBackup.toJson(onion!!, recoveryKey!!).toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
    }

    // Import du fichier de sauvegarde (SAF — aucune permission).
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    }.getOrNull()
                }
                doRestore(text, context) { addr, key ->
                    onion = addr
                    recoveryKey = key
                }
            }
        }
    }

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

            // ---- clé de récupération (v0.2.3 — la sauvegarde n'existait pas) ----
            recoveryKey?.let { rk ->
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            stringResource(R.string.recovery_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.recovery_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            rk,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("nyx_recovery", rk))
                            }) { Text(stringResource(R.string.copy)) }
                            TextButton(onClick = {
                                saveBackup.launch("nyx-sauvegarde-${System.currentTimeMillis() / 1000}.json")
                            }) { Text(stringResource(R.string.recovery_save)) }
                        }
                    }
                }
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
            Text(
                stringResource(R.string.onboarding_gate_hint),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            when (val st = torState) {
                is TorManager.TorState.Starting -> Text(
                    stringResource(R.string.tor_starting, st.percent),
                    style = MaterialTheme.typography.bodySmall
                )
                is TorManager.TorState.Failed -> Text(
                    stringResource(R.string.tor_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                else -> {}
            }
            if (replicaNote.isNotEmpty()) {
                Text(
                    replicaNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (replicaOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
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
                            recoveryKey = RecoveryKey.encode(ServiceLocator.identity.seed())
                            // Tor tourne déjà (sonde réplica) — publication directe.
                            torMgr.provideKeyAndPublish { ServiceLocator.identity.secretKey() }
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && replicaOk,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) stringResource(R.string.onboarding_creating) else stringResource(R.string.onboarding_create))
            }
            OutlinedButton(
                onClick = { showRestore = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(stringResource(R.string.restore_backup)) }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showRestore) {
        RestoreDialog(
            onPickFile = {
                showRestore = false
                openBackup.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
            },
            onDismiss = { showRestore = false },
            onRestore = { text ->
                showRestore = false
                scope.launch { doRestore(text, context) { addr, key -> onion = addr; recoveryKey = key } }
            }
        )
    }
}

/** Restauration partagée (collage ou fichier) — état UI mis à jour via [apply]. */
private suspend fun doRestore(text: String?, context: Context, apply: (String, String) -> Unit) {
    if (text == null) return
    val found = IdentityBackup.extractKey(text)
    if (found == null) {
        android.util.Log.w("nyx-restore", "contenu de sauvegarde méconnaissable")
        return
    }
    val (key, _) = found
    runCatching {
        withContext(Dispatchers.IO) {
            val seed = RecoveryKey.decode(key)
            val addr = ServiceLocator.identity.restoreFromSeed(seed)
            addr to RecoveryKey.encode(ServiceLocator.identity.seed())
        }
    }.onSuccess { (addr, rk) ->
        apply(addr, rk)
        // Tor tourne déjà (onboarding) — publication immédiate de l'adresse restaurée.
        TorManager.get(context).provideKeyAndPublish { ServiceLocator.identity.secretKey() }
    }.onFailure { e ->
        android.util.Log.w("nyx-restore", "restauration impossible : ${e.message}")
    }
}

/** Dialogue de restauration : collage de la clé ou import de fichier. */
@Composable
private fun RestoreDialog(
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_title)) },
        text = {
            Column {
                Text(stringResource(R.string.restore_desc), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; err = null },
                    label = { Text(stringResource(R.string.restore_paste_hint)) },
                    singleLine = false,
                    maxLines = 4,
                    visualTransformation = VisualTransformation.None,
                    isError = err != null,
                    supportingText = err?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onPickFile, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.restore_pick_file))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    RecoveryKey.decode(input) // validation immédiate (checksum)
                    onRestore(input)
                } catch (e: Exception) {
                    err = context.getString(R.string.restore_invalid, e.message ?: "format")
                }
            }) { Text(stringResource(R.string.restore_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
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
