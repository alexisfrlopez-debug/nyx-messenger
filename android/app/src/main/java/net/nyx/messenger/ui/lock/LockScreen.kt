package net.nyx.messenger.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import net.nyx.messenger.R
import net.nyx.messenger.security.AppLock
import net.nyx.messenger.settings.SettingsManager

/** Authenticators requis : biométrie classe 2+ OU code de l'appareil (repli — ADR-010 §3). */
private const val AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

private fun canVerify(context: android.content.Context): Boolean =
    BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
        BiometricManager.BIOMETRIC_SUCCESS

private fun prompt(activity: FragmentActivity, onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.lock_title))
        .setSubtitle(activity.getString(R.string.lock_subtitle))
        .setAllowedAuthenticators(AUTHENTICATORS)
        .build()
    prompt.authenticate(info)
}

/**
 * Écran de verrou : affiché AVANT toute navigation quand l'opt-in biométrique
 * est actif (à froid, après retour au premier plan). Le prompt se déclenche
 * automatiquement ; l'OS effectue la vérification — aucun secret ne transite.
 */
@Composable
fun LockScreen() {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val available = remember { canVerify(context) }
    var attempted by remember { mutableStateOf(false) }

    // Prompt automatique à l'entrée (léger délai pour laisser le Compose se poser)
    LaunchedEffect(Unit) {
        delay(300)
        if (!attempted && available && activity != null) {
            attempted = true
            prompt(activity) { AppLock.unlock() }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.lock_subtitle), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))

        if (available) {
            Button(
                onClick = { activity?.let { prompt(it) { AppLock.unlock() } } },
                enabled = activity != null
            ) {
                Text(stringResource(R.string.lock_unlock))
            }
        } else {
            // Ni biométrie ni code d'appareil enregistrés : impossible de vérifier.
            // Sortie de secours explicite (l'utilisateur doit désactiver l'opt-in).
            Text(
                stringResource(R.string.lock_unavailable_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = {
                SettingsManager.get(context).biometricLock = false
                AppLock.disable()
            }) {
                Text(stringResource(R.string.lock_disable))
            }
        }
    }
}
