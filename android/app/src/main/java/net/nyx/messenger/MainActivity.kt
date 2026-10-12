package net.nyx.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.security.AppLock
import net.nyx.messenger.settings.SettingsManager
import net.nyx.messenger.tor.TorManager
import net.nyx.messenger.ui.NyxNavHost
import net.nyx.messenger.ui.theme.NyxTheme

/**
 * Activité unique (single-activity) — navigation Compose :
 * onboarding → liste des conversations → chat.
 *
 * Démarrage du Tor embarqué (ADR-007) dès que l'identité existe : le démon tor
 * bootstrape pendant que l'utilisateur navigue. La notification persistante est
 * demandée (Android 13+) au premier lancement.
 *
 * Note (v0.1.2) : FragmentActivity (hérite de ComponentActivity) — requise par
 * BiometricPrompt pour le verrou biométrique opt-in (ADR-010 §3). Le passage en
 * arrière-plan re-verrouille ([AppLock.onBackgrounded]).
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
        // Verrou biométrique opt-in (ADR-010 §3) : verrouillé à froid si activé.
        AppLock.init(
            ServiceLocator.identity.exists && SettingsManager.get(this).biometricLock
        )
        setContent {
            NyxTheme {
                NyxNavHost(
                    // Lien d'invitation entrant : nyx://add?o=<onion>&k=<alias>
                    initialAddRequest = intent?.data?.let { uri ->
                        if (uri.scheme == "nyx" && uri.host == "add") uri.getQueryParameter("o") else null
                    }
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
