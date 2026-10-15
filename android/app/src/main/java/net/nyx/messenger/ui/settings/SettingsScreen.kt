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
