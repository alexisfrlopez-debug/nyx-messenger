package net.nyx.messenger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.security.AppLock
import net.nyx.messenger.ui.conversations.ChatScreen
import net.nyx.messenger.ui.conversations.ConversationListScreen
import net.nyx.messenger.ui.lock.LockScreen
import net.nyx.messenger.ui.onboarding.OnboardingScreen
import net.nyx.messenger.ui.onion.MyOnionScreen
import net.nyx.messenger.ui.profile.ContactProfileScreen
import net.nyx.messenger.ui.profile.ProfileEditScreen
import net.nyx.messenger.ui.settings.SettingsScreen

/**
 * Navigation racine :
 *   verrou biométrique (opt-in, ADR-010 §3) → onboarding (1er lancement)
 *   → conversations → chat/{peer}
 *   ; mon adresse (partage) · mon profil · profil contact · paramètres.
 *
 * L'écran de verrou est la porte d'entrée : aucun contenu (ni conversations,
 * ni onboarding) n'est accessible tant que l'OS n'a pas authentifié l'utilisateur.
 *
 * v0.2.0 : le dialogue de fermeture (réduire = Tor + notifications actifs /
 * quitter = tout arrêter proprement) est fourni par MainActivity et composé
 * ici — un AlertDialog Compose se rend dans sa propre fenêtre, quelle que soit
 * sa position dans l'arbre.
 */
@Composable
fun NyxNavHost(
    initialAddRequest: String? = null,
    exitDialog: (@Composable () -> Unit)? = null
) {
    val locked by AppLock.locked.collectAsState()
    if (locked) {
        LockScreen()
        return
    }

    val nav = rememberNavController()
    val hasIdentity = ServiceLocator.initialized && ServiceLocator.identity.exists
    val start = if (hasIdentity) "conversations" else "onboarding"

    NavHost(navController = nav, startDestination = start) {
        composable("onboarding") {
            OnboardingScreen(
                onIdentityCreated = {
                    nav.navigate("conversations") { popUpTo("onboarding") { inclusive = true } }
                }
            )
        }
        composable("conversations") {
            ConversationListScreen(
                pendingAdd = initialAddRequest,
                onOpenConversation = { peer -> nav.navigate("chat/$peer") },
                onOpenMyOnion = { nav.navigate("myonion") },
                onOpenMyProfile = { nav.navigate("myprofile") },
                onOpenSettings = { nav.navigate("settings") }
            )
        }
        composable("chat/{peer}") { entry ->
            ChatScreen(
                peerOnion = entry.arguments?.getString("peer").orEmpty(),
                onOpenProfile = { peer -> nav.navigate("contactprofile/$peer") }
            )
        }
        composable("myonion") {
            MyOnionScreen(
                onBack = { nav.popBackStack() },
                onOpenProfile = { nav.navigate("myprofile") }
            )
        }
        composable("myprofile") {
            ProfileEditScreen(onBack = { nav.popBackStack() })
        }
        composable("contactprofile/{peer}") { entry ->
            ContactProfileScreen(
                peerOnion = entry.arguments?.getString("peer").orEmpty(),
                onBack = { nav.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }

    // Dialogue de fermeture (réduire vs quitter) — fenêtre système au-dessus de tout.
    exitDialog?.invoke()
}
