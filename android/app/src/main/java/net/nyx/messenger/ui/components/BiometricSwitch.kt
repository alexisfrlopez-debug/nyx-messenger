package net.nyx.messenger.ui.components

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import net.nyx.messenger.R
import net.nyx.messenger.security.AppLock
import net.nyx.messenger.settings.SettingsManager

/**
 * Interrupteur du VERROU BIOMÉTRIQUE local opt-in (ADR-010 §3) — partagé par
 * l'onboarding (v0.1.2) et les Paramètres (v0.2.0) : UNE seule implémentation.
 *
 * L'OS vérifie l'utilisateur (BiometricPrompt) et ne renvoie qu'un succès/échec :
 * aucune donnée biométrique n'est lue, stockée ou transmise. L'activation exige
 * une confirmation OS immédiate ; la désactivation est instantanée.
 */
@Composable
fun BiometricSwitch() {
    val context = LocalContext.current
    val settings = remember { SettingsManager.get(context) }
    var enabled by remember { mutableStateOf(settings.biometricLock) }
    val available = BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    ) == BiometricManager.BIOMETRIC_SUCCESS

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.biometric_enable), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    if (available) R.string.biometric_enable_desc else R.string.biometric_unavailable
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { want ->
                val activity = context as? FragmentActivity
                if (!want) {
                    settings.biometricLock = false
                    AppLock.disable()
                    enabled = false
                } else if (activity != null) {
                    // Confirmation immédiate par l'OS avant d'activer l'opt-in
                    val prompt = BiometricPrompt(
                        activity,
                        ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                settings.biometricLock = true
                                AppLock.enable(sessionActive = true)
                                enabled = true
                            }
                        }
                    )
                    prompt.authenticate(
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle(activity.getString(R.string.biometric_confirm_title))
                            .setSubtitle(activity.getString(R.string.biometric_confirm_subtitle))
                            .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            )
                            .build()
                    )
                }
            },
            enabled = available || enabled
        )
    }
}
