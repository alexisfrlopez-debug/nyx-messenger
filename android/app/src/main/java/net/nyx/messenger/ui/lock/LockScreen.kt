package net.nyx.messenger.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.nyx.messenger.R
import net.nyx.messenger.security.AppLock
import net.nyx.messenger.security.PasswordVault
import net.nyx.messenger.security.Wipe
import net.nyx.messenger.settings.SettingsManager
import net.nyx.messenger.ui.theme.nyxNightBackground
import androidx.compose.ui.graphics.luminance

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
                // faire » (annulation, verrou biométrique temporaire, capteur
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
 * Écran de verrou : affiché AU-DESSUS de la navigation quand le verrou d'accès
 * est actif (biométrie opt-in ADR-010 §3, ou l'un des trois mots de passe
 * v0.2.18). OPAQUE par construction (fond du thème) — composé en calque par
 * NyxNavHost.
 *
 * v0.2.3 : le prompt biométrique est déclenché SUR ON_RESUME (lifecycle), plus
 * un délai arbitraire de 300 ms — sur certains OEM (MIUI/HyperOS, Android 15+),
 * un BiometricPrompt lancé avant la pleine reprise de la fenêtre ne s'affiche
 * jamais. Les erreurs sont affichées et loggées (nyx-lock).
 *
 * v0.2.18 — MOTS DE PASSE : saisie manuelle en plus de la biométrie.
 *  - « normal »  → déverrouillage standard (ou retour du compte réel si la
 *    session courante est en mode panique — redémarrage du process) ;
 *  - « panique » → pose le mode PANIQUE puis redémarre : base leurre vide,
 *    poll et P2P coupés ;
 *  - « destruction » → RESET IMMÉDIAT du compte (contacts, historique,
 *    fichiers, identité) — pas de confirmation, c'est son rôle.
 */
@Composable
fun LockScreen() {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val available = remember { canVerify(context) }
    // v0.2.18 : le prompt biométrique ne se déclenche que si l'OPT-IN
    // biométrique est actif — un verrou posé par mots de passe seuls ne doit
    // pas ouvrir le prompt OS à chaque reprise.
    val biometricOn = remember { SettingsManager.get(context).biometricLock }
    val hasPasswords = remember { PasswordVault.anySet(context) }
    var attempt by remember { mutableStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun restartProcess(newMode: PasswordVault.Mode) {
        PasswordVault.setPendingMode(context, newMode)
        // La base Room est choisie à l'initialisation du process : redémarrage.
        val intent = android.content.Intent(context, net.nyx.messenger.MainActivity::class.java)
            .addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    fun submitPassword() {
        val mode = PasswordVault.verify(context, password)
        if (mode == null) {
            errorMsg = context.getString(R.string.lock_password_wrong)
            password = ""
            return
        }
        when (mode) {
            PasswordVault.Mode.NORMAL -> {
                if (net.nyx.messenger.di.ServiceLocator.mode ==
                    net.nyx.messenger.di.ServiceLocator.SessionMode.PANIC
                ) {
                    // Session leurre en cours → retour au compte réel (redémarrage).
                    restartProcess(PasswordVault.Mode.NORMAL)
                } else {
                    AppLock.unlock()
                }
            }
            PasswordVault.Mode.PANIC -> restartProcess(PasswordVault.Mode.PANIC)
            PasswordVault.Mode.DESTRUCTION -> scope.launch(Dispatchers.IO) {
                Wipe.execute(context)
            }
        }
    }

    // Prompt automatique à CHAQUE reprise (froid, retour de l'arrière-plan,
    // retour d'un flux système) — piloté par le lifecycle, pas par un délai.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, available) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && available && biometricOn && activity != null) {
                errorMsg = null
                attempt += 1 // (re)lance le prompt
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        // Rattrapage : la composition peut avoir lieu APRÈS le premier ON_RESUME
        // (première frame) — si l'activité est déjà resumed, lancer maintenant.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            && available && biometricOn && activity != null) {
            errorMsg = null
            attempt += 1
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    androidx.compose.runtime.LaunchedEffect(attempt) {
        if (attempt > 0 && available && biometricOn && activity != null && errorMsg == null) {
            prompt(
                activity,
                onSuccess = {
                    // Biométrie = session NORMALE : si l'app tourne en mode
                    // panique, repasser au compte réel (redémarrage du process).
                    if (net.nyx.messenger.di.ServiceLocator.mode ==
                        net.nyx.messenger.di.ServiceLocator.SessionMode.PANIC
                    ) {
                        restartProcess(PasswordVault.Mode.NORMAL)
                    } else {
                        AppLock.unlock()
                    }
                },
                onError = { code, msg -> errorMsg = "[$code] $msg" }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // v0.2.31 — DÉFILEMENT SYSTÉMATIQUE (demande opérateur : « vérifie
            // systématiquement que les écrans puissent défiler ») : petit écran
            // ou clavier ouvert — le contenu du verrou reste accessible.
            .verticalScroll(rememberScrollState())
            // Opaque : le verrou est un calque au-dessus de la navigation
            // (NyxNavHost v0.2.1) — le contenu dessous ne doit pas transparaître.
            // v0.2.35 : ciel de nuit DÉGRADÉ (opaque — le contenu dessous
            // ne transparaît toujours pas) au lieu de l'aplat violet.
            .background(nyxNightBackground(
                MaterialTheme.colorScheme.background.luminance() > 0.5f
            ))
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

        if (available && biometricOn) {
            Button(
                onClick = {
                    errorMsg = null
                    activity?.let { prompt(it, {
                        if (net.nyx.messenger.di.ServiceLocator.mode ==
                            net.nyx.messenger.di.ServiceLocator.SessionMode.PANIC
                        ) restartProcess(PasswordVault.Mode.NORMAL) else AppLock.unlock()
                    }, { _, m -> errorMsg = m.toString() }) }
                },
                enabled = activity != null
            ) {
                Text(stringResource(R.string.lock_unlock))
            }
            if (hasPasswords) {
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.lock_password_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (hasPasswords) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMsg = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.lock_password_label)) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine = true,
                isError = errorMsg != null
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { submitPassword() },
                enabled = password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.lock_password_submit))
            }
        }

        if (!available && !hasPasswords) {
            // Ni biométrie ni code d'appareil enregistrés, aucun mot de passe :
            // sortie de secours explicite (l'utilisateur doit désactiver l'opt-in).
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

        errorMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
