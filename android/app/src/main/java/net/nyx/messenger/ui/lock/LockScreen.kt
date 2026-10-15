package net.nyx.messenger.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.nyx.messenger.R
import net.nyx.messenger.security.AppLock
import net.nyx.messenger.settings.SettingsManager

/** Authenticators requis : biométrie classe 2+ OU code de l'appareil (repli — ADR-010 §3). */
private const val AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

private fun canVerify(context: android.content.Context): Boolean {
    val res = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
    // Diagnostic device (build test → bouton rouge) : pourquoi le verrou est indisponible.
    android.util.Log.i("nyx-lock", "canAuthenticate=$res (0 = SUCCESS)")
    return res == BiometricManager.BIOMETRIC_SUCCESS
}

private fun prompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (Int, CharSequence) -> Unit
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                android.util.Log.i("nyx-lock", "authentification réussie")
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // AVANT v0.2.3 : erreur silencieuse — l'écran semblait « ne rien
                // faire » (annulation, verrouillage biométrique temporaire, capteur
                // indisponible…). Désormais : message visible + log diagnostique.
                android.util.Log.w("nyx-lock", "erreur biométrique code=$errorCode : $errString")
                onError(errorCode, errString)
            }

            override fun onAuthenticationFailed() {
                android.util.Log.i("nyx-lock", "authentification rejetée (nouvel essai possible)")
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
 * Écran de verrou : affiché AU-DESSUS de la navigation quand l'opt-in
 * biométrique est actif (à froid, après retour au premier plan). OPAQUE par
 * construction (fond du thème) — v0.2.1 : composé en calque par NyxNavHost,
 * la navigation survive dessous mais rien n'est lisible ni accessible tant
 * que l'OS n'a pas authentifié l'utilisateur.
 *
 * v0.2.3 : le prompt est déclenché SUR ON_RESUME (lifecycle), plus un délai
 * arbitraire de 300 ms — sur certains OEM (MIUI/HyperOS, Android 15+), un
 * BiometricPrompt lancé avant la pleine reprise de la fenêtre ne s'affiche
 * jamais. Les erreurs sont affichées et loggées (nyx-lock) — plus d'écran
 * muet ; le prompt se relance à chaque retour au premier plan.
 */
@Composable
fun LockScreen() {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val available = remember { canVerify(context) }
    var attempt by remember { mutableStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Prompt automatique à CHAQUE reprise (froid, retour de l'arrière-plan,
    // retour d'un flux système) — piloté par le lifecycle, pas par un délai.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, available) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && available && activity != null) {
                errorMsg = null
                attempt += 1 // (re)lance le prompt
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        // Rattrapage : la composition peut avoir lieu APRÈS le premier ON_RESUME
        // (première frame) — si l'activité est déjà resumed, lancer maintenant.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            && available && activity != null) {
            errorMsg = null
            attempt += 1
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    androidx.compose.runtime.LaunchedEffect(attempt) {
        if (attempt > 0 && available && activity != null && errorMsg == null) {
            prompt(
                activity,
                onSuccess = { AppLock.unlock() },
                onError = { code, msg -> errorMsg = "[$code] $msg" }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Opaque : le verrou est un calque au-dessus de la navigation
            // (NyxNavHost v0.2.1) — le contenu dessous ne doit pas transparaître.
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
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
                onClick = {
                    errorMsg = null
                    activity?.let { prompt(it, { AppLock.unlock() }, { _, m -> errorMsg = m.toString() }) }
                },
                enabled = activity != null
            ) {
                Text(stringResource(R.string.lock_unlock))
            }
            errorMsg?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
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
