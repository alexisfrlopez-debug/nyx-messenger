package net.nyx.messenger.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.ui.conversations.ChatScreen
import net.nyx.messenger.ui.conversations.ConversationListScreen
import net.nyx.messenger.ui.onboarding.OnboardingScreen

/**
 * Navigation racine :
 *   onboarding (1er lancement) → conversations → chat/{peerOnion}
 */
@Composable
fun NyxNavHost(initialAddRequest: String? = null) {
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
                onOpenConversation = { peer -> nav.navigate("chat/$peer") }
            )
        }
        composable("chat/{peer}") { entry ->
            ChatScreen(peerOnion = entry.arguments?.getString("peer").orEmpty())
        }
    }
}
