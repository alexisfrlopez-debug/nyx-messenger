package net.nyx.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
 */
class MainActivity : ComponentActivity() {

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
