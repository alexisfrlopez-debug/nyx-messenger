package net.nyx.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.nyx.messenger.ui.NyxNavHost
import net.nyx.messenger.ui.theme.NyxTheme

/**
 * Activité unique (single-activity) — navigation Compose :
 * onboarding → liste des conversations → chat.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}
