package net.nyx.messenger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.security.AppLock
import net.nyx.messenger.ui.call.CallOverlay
import net.nyx.messenger.ui.connexions.ConnexionsScreen
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
 * L'écran de verrou est un CALQUE AU-DESSUS de la navigation (v0.2.1) : aucun
 * contenu n'est accessible tant que l'OS n'a pas authentifié l'utilisateur,
 * MAIS le NavController et l'écran en cours SURVIVENT au verrouillage — après
 * déverrouillage, l'utilisateur retrouve exactement où il en était (avant
 * v0.2.1, le verrou démontait la navigation et ramenait à l'écran d'accueil
 * en perdant les éditions en cours, p. ex. une photo de profil en cours de
 * sélection).
 *
 * v0.2.0 : le dialogue de fermeture (réduire = Tor + notifications actifs /
 * quitter = tout arrêter proprement) est fourni par MainActivity et composé
 * ici — un AlertDialog Compose se rend dans sa propre fenêtre, quelle que soit
 * sa position dans l'arbre.
 *
 * v0.2.5 (demande opérateur) : LA PREMIÈRE PAGE est désormais l'écran
 * CONNEXIONS (barre de progression Tor puis Réplica, bouton rouge de logs sur
 * le build test). Le reste (onboarding ou conversations) ne s'affiche qu'une
 * fois les deux étapes validées — avance automatique, écran connexions démonté.
 */
@Composable
fun NyxNavHost(
    initialAddRequest: String? = null,
    exitDialog: (@Composable () -> Unit)? = null
) {
    val locked by AppLock.locked.collectAsState()
    val nav = rememberNavController()
    val hasIdentity = ServiceLocator.initialized && ServiceLocator.identity.exists

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "connexions") {
            // v0.2.5 : porte d'entrée unique — Tor puis réplica validés AVANT tout.
            composable("connexions") {
                ConnexionsScreen(
                    onConnected = {
                        nav.navigate(if (hasIdentity) "conversations" else "onboarding") {
                            popUpTo("connexions") { inclusive = true }
                        }
                    }
                )
            }
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
                    onOpenConversation = { peer ->
                        // v0.2.11 : encodage URI — un caractère exotique dans une
                        // adresse ne doit jamais casser la route de navigation.
                        nav.navigate("chat/${java.net.URLEncoder.encode(peer, "UTF-8").replace("+", "%20")}")
                    },
                    // v0.2.14 : fiche du contact accessible DEPUIS LA LISTE (bouton
                    // ⓘ) — l'adresse onion n'y est visible QUE dans cette fiche.
                    onOpenContactProfile = { peer ->
                        nav.navigate("contactprofile/${java.net.URLEncoder.encode(peer, "UTF-8").replace("+", "%20")}")
                    },
                    onOpenMyOnion = { nav.navigate("myonion") },
                    onOpenMyProfile = { nav.navigate("myprofile") },
                    onOpenSettings = { nav.navigate("settings") }
                )
            }
            composable("chat/{peer}") { entry ->
                ChatScreen(
                    peerOnion = entry.arguments?.getString("peer").orEmpty(),
                    onOpenProfile = { peer ->
                        nav.navigate("contactprofile/${java.net.URLEncoder.encode(peer, "UTF-8").replace("+", "%20")}")
                    }
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

        // v0.2.18 — CALQUE D'APPEL : au-dessus de la navigation (sonnerie,
        // conversation, fin) — un appel en cours est visible DEPUIS N'IMPORTE
        // QUEL écran ; le verrou reste au-dessus (verrouillé = pas d'appel vu).
        CallOverlay()

        // Calque de verrou : dessiné en dernier = au-dessus de tout le contenu,
        // opaque (fond système) — la navigation reste composée SOUS le verrou.
        if (locked) LockScreen()

        // v0.2.26 — CRÉATION FORCÉE DES MOTS DE PASSE : dès qu'une identité
        // existe sans mot de passe NORMAL (retour terrain : « le mot de passe
        // protège l'application mais pas le compte — une clé de récupération
        // importée fonctionne sans mot de passe »), ce calque opaque exige la
        // création du mot de passe NORMAL puis du mot de passe DESTRUCTION
        // (effacement du fichier de clé importé si saisi à l'importation).
        // Pas de session PANIQUE (base leurre) ni avant création d'identité.
        val gateContext = androidx.compose.ui.platform.LocalContext.current
        val showPasswordGate = ServiceLocator.initialized &&
            ServiceLocator.mode != net.nyx.messenger.di.ServiceLocator.SessionMode.PANIC &&
            ServiceLocator.identity.exists &&
            !net.nyx.messenger.security.PasswordVault.isSet(
                gateContext, net.nyx.messenger.security.PasswordVault.Mode.NORMAL
            )
        if (!locked && showPasswordGate) net.nyx.messenger.ui.components.PasswordGate()
    }

    // Dialogue de fermeture (réduire vs quitter) — fenêtre système au-dessus de tout.
    exitDialog?.invoke()
}
