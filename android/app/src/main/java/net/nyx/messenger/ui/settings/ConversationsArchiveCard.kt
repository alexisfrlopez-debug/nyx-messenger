package net.nyx.messenger.ui.settings

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.R
import net.nyx.messenger.crypto.PasswordCrypto
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.ui.components.ErrorLine
import net.nyx.messenger.ui.components.PasswordField
import net.nyx.messenger.settings.SettingsManager

/* ================================================================
 * v0.2.26 — CARTE « RÉPLICAS » (demande opérateur : « je veux une liste
 * mise à jour des replicas et replicas temporaires sur lesquels le
 * téléphone est connecté, avec un indicateur pour montrer celui/ceux qui
 * sont actifs et utilisés »).
 *
 * Source : NyxRepository.replicas (fusion custom > découverts >
 * rendez-vous > amorçage, re-fusionnée à chaque annonce) + l'état vivant
 * par réplica (replicaStates : utilisé/OK/échec) + la sonde du réplica
 * actif (lastProbe). Rafraîchi chaque seconde (durées relatives).
 * ================================================================ */

/** « il y a Xs / Xmin / Xh / — » pour un horodatage (0 = jamais). */
private fun relative(fr: (Int) -> String, at: Long, now: Long): String =
    if (at <= 0) "—" else {
        val s = (((now - at) / 1000).coerceAtLeast(0)).toInt()
        when {
            s < 60 -> fr(s) + " s"
            s < 3600 -> fr(s / 60) + " min"
            else -> fr(s / 3600) + " h"
        }
    }

@Composable
fun ReplicaStatusCard() {
    val repo = ServiceLocator.repository
    val context = LocalContext.current
    val states by repo.replicaStates.collectAsState()
    val probe by repo.lastProbe.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }

    val customs = remember(tick) { SettingsManager.get(context).customReplicas.toSet() }
    val discovered = remember(tick) {
        runCatching { net.nyx.messenger.net.ReplicaRegistry.list().toSet() }.getOrDefault(emptySet())
    }
    val now = System.currentTimeMillis()
    val replicas = repo.replicas
    val active = repo.activeReplica

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.replica_status_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.replica_status_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (replicas.isEmpty()) {
                Text(
                    stringResource(R.string.replica_none_configured),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            replicas.forEach { addr ->
                val st = states[addr]
                val isActive = addr == active
                val probeOk = isActive && probe?.ok == true
                val dot = when {
                    probeOk -> Color(0xFF2E7D32)
                    isActive -> Color(0xFFF9A825)
                    (st?.lastFailAt ?: 0L) > (st?.lastOkAt ?: 0L) -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val source = when {
                    addr in customs -> stringResource(R.string.replica_src_custom)
                    addr in discovered -> stringResource(R.string.replica_src_discovered)
                    else -> stringResource(R.string.replica_src_builtin)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(color = dot)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        addr.removeSuffix(".onion").take(22) + "…",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (isActive) {
                        Text(
                            stringResource(R.string.replica_badge_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
                // Détail : utilisé / sonde OK / dernier échec.
                val detail = buildString {
                    if ((st?.lastUsedAt ?: 0L) > 0) {
                        append("UTILISÉ il y a " + relative({ n -> n.toString() }, st!!.lastUsedAt, now))
                    }
                    if ((st?.lastOkAt ?: 0L) > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("OK il y a " + relative({ n -> n.toString() }, st!!.lastOkAt, now))
                    }
                    if ((st?.lastFailAt ?: 0L) > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("échec il y a " + relative({ n -> n.toString() }, st!!.lastFailAt, now))
                        if (st!!.lastFailReason.isNotBlank()) append(" (${st.lastFailReason.take(40)})")
                    }
                    if (probeOk) {
                        if (isNotEmpty()) append(" · ")
                        append(probe?.ms?.toString() + " ms")
                    }
                }
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp)
                    )
                }
            }
            // Réplica TEMPORAIRE embarqué (ce téléphone sert de réplica).
            if (net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA) {
                val onion = remember { ServiceLocator.identity.onionAddress ?: "" }
                val serving by SettingsManager.get(context).replicaEnabled.collectAsState()
                if (onion.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.replica_src_embedded),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = "$onion:80",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            stringResource(
                                if (serving) R.string.replica_embedded_on else R.string.replica_embedded_off
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (serving) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/* ================================================================
 * v0.2.26 — CARTE « CONVERSATIONS » : export des conversations CHOISIES
 * dans un fichier chiffré par mot de passe (enveloppe PasswordCrypto) et
 * importation inverse (demande opérateur : « permettre l'exportation des
 * conversations (avec choix des conversations possibles) dans un fichier
 * importable protégé par mot de passe »).
 * ================================================================ */

@Composable
fun ConversationsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = ServiceLocator.repository

    var showSelect by remember { mutableStateOf(false) }
    var showExportPwd by remember { mutableStateOf(false) }
    var showImportPwd by remember { mutableStateOf(false) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Conversations cochées (étape 1) — conservée jusqu'à l'étape mot de passe.
    var selectedPeers by remember { mutableStateOf<Set<String>>(emptySet()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        if (uri != null && json != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("flux d'écriture indisponible")
                    }
                }
                pendingExportJson = null
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = net.nyx.messenger.ui.components.readSafText(context, uri)
            if (text == null) {
                android.widget.Toast.makeText(
                    context, R.string.conv_import_read_fail, android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                pendingImportText = text
                showImportPwd = true
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.conv_card_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.conv_card_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { showSelect = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.conv_export))
                }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.conv_import)) }
            }
        }
    }

    // ---- Étape 1 : sélection des conversations ----
    if (showSelect) {
        var contacts by remember { mutableStateOf<List<net.nyx.messenger.data.db.ContactEntity>>(emptyList()) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { ServiceLocator.database.contactDao().accepted() }.getOrNull()
            }?.let { contacts = it; selectedPeers = it.map { c -> c.onion }.toSet() }
        }
        AlertDialog(
            onDismissRequest = { showSelect = false },
            title = { Text(stringResource(R.string.conv_select_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (contacts.isEmpty()) Text(stringResource(R.string.conv_select_empty))
                    contacts.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = c.onion in selectedPeers,
                                onCheckedChange = { on ->
                                    selectedPeers = if (on) selectedPeers + c.onion else selectedPeers - c.onion
                                }
                            )
                            Text(c.alias, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSelect = false
                        showExportPwd = true
                    },
                    enabled = selectedPeers.isNotEmpty()
                ) { Text(stringResource(R.string.conv_select_next)) }
            },
            dismissButton = {
                net.nyx.messenger.ui.components.DialogDismissButton { showSelect = false }
            }
        )
    }

    // ---- Étape 2 : mot de passe d'export (choisi) puis fichier ----
    if (showExportPwd) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) showExportPwd = false },
            title = { Text(stringResource(R.string.conv_pwd_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.conv_pwd_desc), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    PasswordField(p1, { p1 = it; err = null }, stringResource(R.string.gate_pwd))
                    Spacer(Modifier.height(8.dp))
                    PasswordField(p2, { p2 = it; err = null }, stringResource(R.string.gate_pwd_confirm))
                    ErrorLine(err)
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        if (p1.length < 6) {
                            err = context.getString(R.string.gate_pwd_too_short)
                        } else if (p1 != p2) {
                            err = context.getString(R.string.gate_pwd_mismatch)
                        } else {
                            busy = true
                            scope.launch {
                                val json = runCatching {
                                    repo.exportConversationsJson(selectedPeers.toList())
                                }
                                busy = false
                                json.fold(
                                    onSuccess = { j ->
                                        pendingExportJson =
                                            PasswordCrypto.encrypt(j.toByteArray(Charsets.UTF_8), p1.toCharArray())
                                        showExportPwd = false
                                        exportLauncher.launch(
                                            "nyx-conversations-${System.currentTimeMillis() / 1000}.json"
                                        )
                                    },
                                    onFailure = { e -> err = e.message }
                                )
                            }
                        }
                    }
                ) { Text(stringResource(R.string.conv_export)) }
            },
            dismissButton = {
                net.nyx.messenger.ui.components.DialogDismissButton { showExportPwd = false }
            }
        )
    }

    // ---- Étape 3 : mot de passe d'import puis fusion ----
    if (showImportPwd) {
        // Variables nommées ipwd/ierr (dialogue distinct de celui du compte).
        var ipwd by remember { mutableStateOf("") }
        var ierr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) showImportPwd = false },
            title = { Text(stringResource(R.string.conv_import_pwd_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.conv_import_pwd_desc), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    PasswordField(ipwd, { ipwd = it; ierr = null }, stringResource(R.string.gate_pwd))
                    ErrorLine(ierr)
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val text = pendingImportText
                        scope.launch {
                            val plain = runCatching {
                                if (PasswordCrypto.isEncrypted(text ?: "")) {
                                    PasswordCrypto.decrypt(text ?: "", ipwd.toCharArray())
                                        ?.toString(Charsets.UTF_8)
                                        ?: error(context.getString(R.string.conv_import_wrong_pwd))
                                } else text ?: error(context.getString(R.string.conv_import_bad_file))
                            }
                            val res = plain.mapCatching { repo.importConversationsJson(it) }
                            busy = false
                            res.fold(
                                onSuccess = { n ->
                                    pendingImportText = null
                                    showImportPwd = false
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.conv_import_done, n),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                },
                                onFailure = { e -> ierr = e.message ?: "?" }
                            )
                        }
                    }
                ) { Text(stringResource(R.string.conv_import)) }
            },
            dismissButton = {
                net.nyx.messenger.ui.components.DialogDismissButton { showImportPwd = false }
            }
        )
    }
}
