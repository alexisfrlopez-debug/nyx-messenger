package net.nyx.messenger.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.R
import net.nyx.messenger.crypto.IdentityBackup
import net.nyx.messenger.crypto.RecoveryKey
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.settings.SettingsManager

/**
 * Paramètres de l'application (v0.2.0) — réglages LOCAUX de l'interface :
 *  - thème (système / clair / sombre) ;
 *  - comportement à la fermeture (demander / réduire — Tor reste actif — /
 *    quitter — tous les processus, Tor compris, sont arrêtés proprement) ;
 *  - verrou biométrique opt-in (ADR-010 §3 — vérification par l'OS, aucune
 *    donnée biométrique lue/stockée/transmise).
 * Aucun de ces réglages ne quitte l'appareil.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsManager.get(context) }
    val theme by settings.themeMode.collectAsState()
    val close by settings.closeBehavior.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ---- thème ----
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            SettingsManager.ThemeMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(selected = theme == mode, onClick = { settings.setThemeMode(mode) })
                    Text(
                        stringResource(
                            when (mode) {
                                SettingsManager.ThemeMode.SYSTEM -> R.string.settings_theme_system
                                SettingsManager.ThemeMode.LIGHT -> R.string.settings_theme_light
                                SettingsManager.ThemeMode.DARK -> R.string.settings_theme_dark
                            }
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ---- fermeture ----
            Text(stringResource(R.string.settings_close), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_close_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            SettingsManager.CloseBehavior.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(selected = close == mode, onClick = { settings.setCloseBehavior(mode) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                when (mode) {
                                    SettingsManager.CloseBehavior.ASK -> R.string.settings_close_ask
                                    SettingsManager.CloseBehavior.MINIMIZE -> R.string.settings_close_minimize
                                    SettingsManager.CloseBehavior.EXIT -> R.string.settings_close_exit
                                }
                            )
                        )
                        if (mode == SettingsManager.CloseBehavior.MINIMIZE) {
                            Text(
                                stringResource(R.string.settings_close_minimize_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (mode == SettingsManager.CloseBehavior.EXIT) {
                            Text(
                                stringResource(R.string.settings_close_exit_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            net.nyx.messenger.ui.components.BiometricSwitch()
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ---- v0.2.18 : les trois mots de passe (normal/panique/destruction) ----
            PasswordsCard()
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ---- v0.2.20 : mise à jour automatique (VERSION NORMALE UNIQUEMENT) ----
            if (!net.nyx.messenger.BuildConfig.TEST_BUILD) {
                UpdateCard()
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }

            // ---- v0.2.19 : réplica temporaire + liste des réplicas (TEST 2) ----
            if (net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA) {
                EmbeddedReplicaCard()
                Spacer(Modifier.height(8.dp))
                ReplicasListCard()
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }

            // ---- sauvegarde de l'identité (v0.2.3 — ADR-003) ----
            BackupCard()
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ---- à propos ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_about_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/**
 * v0.2.20 — CARTE MISE À JOUR (version normale uniquement — demande opérateur :
 * « la version "normale" se met à jour toute seule, quand la dernière release
 * est validée pour la production »). États : vérification / à jour /
 * disponible (téléchargement AUTO via Tor) / prête (appui = installateur
 * système). La validation = la release passe non-pré-release sur GitHub.
 */
@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by net.nyx.messenger.update.Updater.state.collectAsState()

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.settings_update_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_update_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            when (val st = state) {
                is net.nyx.messenger.update.Updater.State.UpToDate ->
                    Text(stringResource(R.string.settings_update_latest, st.tag),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                is net.nyx.messenger.update.Updater.State.Checking ->
                    Text(stringResource(R.string.settings_update_downloading, "…"),
                        style = MaterialTheme.typography.bodyMedium)
                is net.nyx.messenger.update.Updater.State.Available ->
                    Text(stringResource(R.string.settings_update_available, st.release.version),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodyMedium)
                is net.nyx.messenger.update.Updater.State.Downloading -> {
                    val pct = if (st.total > 0) "${st.bytes * 100 / st.total} %" else "${st.bytes / 1024} Ko"
                    Text(stringResource(R.string.settings_update_downloading, pct),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                is net.nyx.messenger.update.Updater.State.Ready -> {
                    Text(
                        stringResource(R.string.settings_update_ready, st.release.version),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { net.nyx.messenger.update.Updater.install(st.apk) }) {
                        Text(stringResource(R.string.settings_update_ready, st.release.version))
                    }
                }
                is net.nyx.messenger.update.Updater.State.Failed ->
                    Text(stringResource(R.string.settings_update_failed, st.reason),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                else -> {}
            }
            TextButton(onClick = {
                scope.launch {
                    net.nyx.messenger.update.Updater.checkNow(
                        net.nyx.messenger.tor.TorManager.activeSocksPort,
                        context.cacheDir
                    )
                }
            }) { Text(stringResource(R.string.settings_update_check)) }
        }
    }
}

/**
 * Sauvegarde de l'identité (v0.2.3) : affichage masqué par défaut de la clé de
 * récupération, copie et export fichier (SAF). La clé EST le compte (ADR-003) —
 * elle ne quitte l'écran que sur action explicite, jamais journalisée.
 */
@Composable
private fun BackupCard() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var revealed by remember { mutableStateOf(false) }

    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val key = RecoveryKey.encode(ServiceLocator.identity.seed())
                        val onion = ServiceLocator.identity.onionAddress ?: ""
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(IdentityBackup.toJson(onion, key).toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.settings_backup_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_backup_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (revealed) {
                val key = remember { runCatching { RecoveryKey.encode(ServiceLocator.identity.seed()) }.getOrNull() }
                if (key != null) {
                    Text(
                        key,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(
                        stringResource(R.string.tor_offline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        key?.let { cm.setPrimaryClip(ClipData.newPlainText("nyx_recovery", it)) }
                    }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = {
                        export.launch("nyx-sauvegarde-${System.currentTimeMillis() / 1000}.json")
                    }) { Text(stringResource(R.string.settings_backup_export)) }
                    TextButton(onClick = { revealed = false }) {
                        Text(stringResource(R.string.settings_backup_hide))
                    }
                }
            } else {
                TextButton(onClick = { revealed = true }) {
                    Text(stringResource(R.string.settings_backup_show))
                }
            }
        }
    }
}

/**
 * v0.2.18 — LES TROIS MOTS DE PASSE (demande opérateur) : normal (accès
 * standard, comme la biométrie), panique (base leurre vide — contacts et
 * historique apparents : aucun), destruction (reset immédiat du compte :
 * contacts, historique, fichiers, identité). Hachés PBKDF2 (150 000 itérations)
 * en local — jamais transmis, jamais en clair.
 */
@Composable
private fun PasswordsCard() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<net.nyx.messenger.security.PasswordVault.Mode?>(null) }
    var confirmDestruction by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.settings_passwords), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_passwords_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))

            listOf(
                Triple(
                    net.nyx.messenger.security.PasswordVault.Mode.NORMAL,
                    R.string.settings_pwd_normal,
                    R.string.settings_pwd_normal_desc
                ),
                Triple(
                    net.nyx.messenger.security.PasswordVault.Mode.PANIC,
                    R.string.settings_pwd_panic,
                    R.string.settings_pwd_panic_desc
                ),
                Triple(
                    net.nyx.messenger.security.PasswordVault.Mode.DESTRUCTION,
                    R.string.settings_pwd_destruction,
                    R.string.settings_pwd_destruction_desc
                ),
            ).forEach { (mode, title, desc) ->
                val set = remember(refresh) {
                    net.nyx.messenger.security.PasswordVault.isSet(context, mode)
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { editing = mode }) {
                            Text(
                                stringResource(
                                    if (set) R.string.settings_pwd_change else R.string.settings_pwd_create
                                ),
                                color = if (mode == net.nyx.messenger.security.PasswordVault.Mode.DESTRUCTION && set)
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        if (set) {
                            TextButton(onClick = {
                                if (mode == net.nyx.messenger.security.PasswordVault.Mode.DESTRUCTION) {
                                    confirmDestruction = true
                                } else {
                                    net.nyx.messenger.security.PasswordVault.clear(context, mode)
                                    refresh++
                                }
                            }) {
                                Text(
                                    stringResource(R.string.settings_pwd_remove),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { mode ->
        PasswordDialog(
            mode = mode,
            onDismiss = { editing = null },
            onSaved = { editing = null; refresh++ }
        )
    }

    if (confirmDestruction) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDestruction = false },
            title = { Text(stringResource(R.string.settings_pwd_remove)) },
            text = { Text(stringResource(R.string.settings_pwd_destruction_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    net.nyx.messenger.security.PasswordVault.clear(
                        context, net.nyx.messenger.security.PasswordVault.Mode.DESTRUCTION
                    )
                    confirmDestruction = false
                    refresh++
                }) { Text(stringResource(R.string.settings_pwd_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDestruction = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * v0.2.19 — CARTE RÉPLICA TEMPORAIRE (build « test 2 » uniquement) : interrupteur
 * (actif tant que l'application tourne), adresse à partager, statistiques de
 * stockage, purge manuelle. Les plafonds (dépôts/min, taille, TTL 48 h) sont
 * documentés dans le texte — la promesse « ne pas bouffer les ressources ».
 */
@Composable
private fun EmbeddedReplicaCard() {
    val context = LocalContext.current
    val settings = remember { SettingsManager.get(context) }
    val enabled by settings.replicaEnabled.collectAsState()
    val scope = rememberCoroutineScope()
    var statsTick by remember { mutableStateOf(0) }
    val stats = remember(statsTick) {
        runCatching {
            val dao = ServiceLocator.database.replicaDao()
            kotlinx.coroutines.runBlocking {
                Pair(dao.count(), dao.totalBytes())
            }
        }.getOrDefault(Pair(0, 0L))
    }
    val onion = remember { ServiceLocator.identity.onionAddress ?: "" }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.settings_replica_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_replica_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_replica_enabled),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(
                            if (enabled) R.string.settings_replica_status_on
                            else R.string.settings_replica_status_off
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { settings.setReplicaEnabled(it) })
            }
            if (onion.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_replica_address), style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$onion:80",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("nyx_replica", "$onion:80"))
                    }) { Text(stringResource(R.string.copy)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.settings_replica_stats,
                    stats.first,
                    if (stats.second > 0) net.nyx.messenger.repo.ChunkCodec.humanSize(stats.second) else "0 o"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { ServiceLocator.database.replicaDao().clear() }
                    }
                    statsTick++
                    android.widget.Toast.makeText(
                        context, context.getString(R.string.settings_replica_purged),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }) {
                Text(stringResource(R.string.settings_replica_purge))
            }
        }
    }
}

/**
 * v0.2.19 — CARTE LISTE DES RÉPLICAS (build « test 2 » uniquement) : les
 * adresses custom passent AVANT les réplicas d'amorçage embarqués (le premier
 * de la liste est utilisé). Permet de pointer les DEUX téléphones vers le
 * réplica temporaire de l'un d'eux — le réseau fonctionne sans le serveur
 * central. Validée par le même format que l'amorçage (BootstrapReplicas).
 */
@Composable
private fun ReplicasListCard() {
    val context = LocalContext.current
    val settings = remember { SettingsManager.get(context) }
    var customs by remember { mutableStateOf(settings.customReplicas) }
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val bootstraps = remember {
        net.nyx.messenger.net.BootstrapReplicas.parse(net.nyx.messenger.BuildConfig.BOOTSTRAP_REPLICAS)
    }

    fun apply(list: List<String>) {
        customs = list
        settings.customReplicas = list
        ServiceLocator.repository.setReplicas((list + bootstraps).distinct())
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.settings_replicas_list_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_replicas_list_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (customs.isEmpty() && bootstraps.isNotEmpty()) {
                Text(
                    bootstraps.first(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            customs.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { apply(customs - r) }) {
                        Text(
                            stringResource(R.string.settings_replicas_remove),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; invalid = false },
                    label = { Text("…onion:80") },
                    singleLine = true,
                    isError = invalid,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    val cleaned = input.trim().lowercase()
                    val ok = Regex("^[a-z2-7]{56}\\.onion(:\\d+)?$").matches(cleaned)
                    if (ok && !customs.contains(cleaned)) {
                        apply(customs + cleaned)
                        input = ""
                    } else {
                        invalid = true
                    }
                }) { Text(stringResource(R.string.settings_replicas_add)) }
            }
            if (invalid) {
                Text(
                    stringResource(R.string.settings_replicas_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Dialogue de création/changement d'un mot de passe (saisie double + garde). */
@Composable
private fun PasswordDialog(
    mode: net.nyx.messenger.security.PasswordVault.Mode,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    val title = stringResource(
        when (mode) {
            net.nyx.messenger.security.PasswordVault.Mode.NORMAL -> R.string.settings_pwd_normal
            net.nyx.messenger.security.PasswordVault.Mode.PANIC -> R.string.settings_pwd_panic
            net.nyx.messenger.security.PasswordVault.Mode.DESTRUCTION -> R.string.settings_pwd_destruction
        }
    )

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_pwd_dialog_title, title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        when (mode) {
                            net.nyx.messenger.security.PasswordVault.Mode.NORMAL -> R.string.settings_pwd_normal_desc
                            net.nyx.messenger.security.PasswordVault.Mode.PANIC -> R.string.settings_pwd_panic_desc
                            net.nyx.messenger.security.PasswordVault.Mode.DESTRUCTION -> R.string.settings_pwd_destruction_desc
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = p1, onValueChange = { p1 = it; error = null },
                    label = { Text(stringResource(R.string.settings_pwd_new)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = p2, onValueChange = { p2 = it; error = null },
                    label = { Text(stringResource(R.string.settings_pwd_confirm)) },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
                error?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    p1.isBlank() -> error = R.string.settings_pwd_blank
                    p1 != p2 -> error = R.string.settings_pwd_mismatch
                    else -> {
                        val ok = net.nyx.messenger.security.PasswordVault.set(context, mode, p1)
                        if (ok) {
                            onSaved()
                        } else {
                            error = R.string.settings_pwd_conflict
                        }
                    }
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
