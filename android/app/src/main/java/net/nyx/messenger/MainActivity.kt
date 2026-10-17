package net.nyx.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.security.AppLock
import net.nyx.messenger.settings.SettingsManager
import net.nyx.messenger.tor.TorManager
import net.nyx.messenger.ExitDialog
import net.nyx.messenger.ui.NyxNavHost
import net.nyx.messenger.ui.theme.NyxTheme

/**
 * Activité unique (single-activity) — navigation Compose :
 * onboarding → liste des conversations → chat (+ adresse / profil / paramètres).
 *
 * Démarrage du Tor embarqué (ADR-007) dès que l'identité existe : le démon tor
 * bootstrape pendant que l'utilisateur navigue. La notification persistante est
 * demandée (Android 13+) au premier lancement.
 *
 * Note (v0.1.2) : FragmentActivity (hérite de ComponentActivity) — requise par
 * BiometricPrompt pour le verrou biométrique opt-in (ADR-010 §3). Le passage en
 * arrière-plan re-verrouille ([AppLock.onBackgrounded]).
 *
 * v0.2.0 (session 12) :
 *  - thème piloté par le réglage utilisateur (système/clair/sombre) ;
 *  - fermeture : le bouton retour ouvre le dialogue « Réduire (Tor actif) /
 *    Quitter (tout arrêter proprement) » — [ExitController] — sauf si
 *    l'utilisateur a choisi un comportement permanent dans Paramètres.
 */
class MainActivity : FragmentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Refusée : Tor tourne quand même (service au premier plan sans notification
            // visible sur < 13 ; sur 13+ la notification est simplement masquée).
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        startEmbeddedTor()
        // Verrou d'accès (ADR-010 §3) : biométrie opt-in OU un des trois mots
        // de passe (v0.2.18 : normal/panique/destruction) — verrouillé à froid.
        val biometricOptIn = SettingsManager.get(this).biometricLock
        val passwordLock = net.nyx.messenger.security.PasswordVault.anySet(this)
        val identityExists = ServiceLocator.identity.exists
        android.util.Log.i(
            "nyx-lock", "onCreate : identité=$identityExists, opt-in=$biometricOptIn, mdp=$passwordLock, " +
                "mode=${ServiceLocator.mode}"
        )
        AppLock.init(identityExists && (biometricOptIn || passwordLock))

        // Fermeture (v0.2.0, rév. v0.2.9) : SANS identité (première étape de
        // connexion), le retour système QUITTE avec arrêt PROPRE du démon tor
        // (il tourne pourtant dès l'onboarding depuis v0.2.3 — l'ancien code
        // le laissait orphelin, d'où des tor fantômes au relancement) ;
        // avec identité, dialogue « réduire ou quitter » ([ExitController]).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val identityExists = ServiceLocator.initialized && ServiceLocator.identity.exists
                when {
                    !identityExists -> ExitController.exitCompletely(this@MainActivity)
                    !ExitController.handleBack(this@MainActivity) -> finish()
                }
            }
        })

        setContent {
            val settings = SettingsManager.get(this)
            val themeMode by settings.themeMode.collectAsState()
            NyxTheme(mode = themeMode) {
                NyxNavHost(
                    // Lien d'invitation entrant : nyx://add?o=<onion>&k=<alias>
                    initialAddRequest = intent?.data?.let { uri ->
                        if (uri.scheme == "nyx" && uri.host == "add") uri.getQueryParameter("o") else null
                    },
                    exitDialog = { ExitDialog(this@MainActivity) }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AppLock.onBackgrounded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Tor embarqué : démarrage dès que l'identité existe (sinon après l'onboarding). */
    private fun startEmbeddedTor() {
        val identity = net.nyx.messenger.di.ServiceLocator.identity
        if (identity.exists) {
            TorManager.get(this).start { identity.secretKey() }
        }
    }
}
