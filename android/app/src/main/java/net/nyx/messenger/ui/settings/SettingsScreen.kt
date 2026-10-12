package net.nyx.messenger.ui.settings

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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.nyx.messenger.R
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
