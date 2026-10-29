package net.nyx.messenger.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import net.nyx.messenger.notify.DesktopNotifier
import net.nyx.messenger.security.DesktopVault
import net.nyx.messenger.ui.ChatPanel
import net.nyx.messenger.ui.ContactProfileScreen
import net.nyx.messenger.ui.LockScreen
import net.nyx.messenger.ui.MainScreen
import net.nyx.messenger.ui.MyOnionPanel
import net.nyx.messenger.ui.NyxTheme
import net.nyx.messenger.ui.OnboardingScreen
import net.nyx.messenger.ui.PasswordGate
import net.nyx.messenger.ui.ProfileEditScreen
import net.nyx.messenger.ui.SettingsPanel
import net.nyx.messenger.ui.Sidebar
import net.nyx.messenger.ui.SidebarTab
import java.awt.Frame
import kotlin.system.exitProcess
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    // Autotest headless (v0.3.1 — pipeline de build + diagnostics terrain) :
    // aucun affichage, aucun Tor — marqueurs [SELFTEST] + code de sortie.
    if ("--selftest" in args) {
        exitProcess(if (SelfTest.run()) 0 else 3)
    }
    application {
        ServiceLocator.init()

        // v0.3.9 — FENÊTRE DE NOTIFICATION D'APPEL ENTRANTE (demande
        // opérateur session 79 : « une notification avec une icone pour
        // décrocher et une pour refuser l'appel »). Rendue À LA RACINE —
        // au-dessus de TOUT (app verrouillée, fenêtre réduite au plateau,
        // autres fenêtres) — deux icônes géantes ✓ Décrocher / ✕ Refuser.
        // Terrain 28/10 10:47 (export v0.3.8) : appel tel→PC sonné 43 s
        // sans décrochage — l'opérateur n'a pas trouvé l'écran
        // d'acceptation et a relancé l'appli.
        net.nyx.messenger.ui.IncomingCallWindow()

        // v0.3.11 — CARTE D'APPEL FLOTTANTE (demande terrain 28/10 : « un
        // bouton pour raccrocher toujours au premier plan jusqu'à la fin de
        // l'appel ») : au-dessus de TOUT pendant l'appel (sortant + actif) —
        // la sonnerie entrante garde sa carte dédiée IncomingCallWindow.
        net.nyx.messenger.ui.CallFloatWindow()

        Window(
            title = "Nyx — messagerie de la nuit",
            state = rememberWindowState(width = 1220.dp, height = 840.dp),
            icon = BitmapPainter(nyxIconBitmap(64)),
            onCloseRequest = {
                // v0.3.4 — comportement de fermeture miroir Android
                // (ExitController) : Demander / Réduire (Tor actif) / Quitter.
                when (ServiceLocator.settings.get().closeBehavior) {
                    "reduire" -> hideToTray()
                    "quitter" -> {
                        ServiceLocator.shutdownBackend()
                        exitApplication()
                    }
                    else -> exitDialogOpenFlow.value = true
                }
            },
        ) {
            val theme by ServiceLocator.themeSetting.collectAsState()
            NyxTheme(theme) {
                App(
                    onQuit = {
                        ServiceLocator.shutdownBackend()
                        exitApplication()
                    },
                    onHide = { hideToTray() },
                    awtWindow = this.window,
                )
            }
        }
    }
}

/** État du dialogue de sortie (partagé main()/App()) — v0.3.11 : FLUX
 *  OBSERVABLE (avant : simple @Volatile que Compose n'observait PAS — « le
 *  bouton annuler ne fait rien », terrain 28/10 : le dialogue ne se fermait
 *  qu'à la prochaine recomposition fortuite, ≤ 15 s plus tard). */
private val exitDialogOpenFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

private fun hideToTray() {
    // Réduit au plateau : Tor reste actif (miroir « Réduire » Android —
    // notifications vivantes) ; l'icône ramène la fenêtre (DesktopNotifier).
    appWindow?.let { w -> w.isVisible = false }
    if (DesktopVault.anySet()) ServiceLocator.lock()
}

@Volatile private var appWindow: java.awt.Window? = null

/** Icône de fenêtre : lune dorée générée (miroir nyx.ico — zéro ressource embarquée). */
private fun nyxIconBitmap(size: Int): androidx.compose.ui.graphics.ImageBitmap {
    val img = net.nyx.messenger.notify.DesktopNotifier.moonIcon(size)
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return org.jetbrains.skia.Image.makeFromEncoded(out.toByteArray()).asImageBitmap()
}

@Composable
fun App(onQuit: () -> Unit, onHide: () -> Unit, awtWindow: java.awt.Window?) {
    val window = awtWindow
    val hasIdentity = remember { mutableStateOf(ServiceLocator.identity.exists) }
    var identityVersion by remember { mutableStateOf(0) } // re-détection après wipe/déconnexion
    var backendStarted by remember { mutableStateOf(false) }
    var selectedPeer by remember { mutableStateOf<String?>(null) }
    var profilePeer by remember { mutableStateOf<String?>(null) } // fiche contact ouverte
    var tab by remember { mutableStateOf(SidebarTab.CONVERSATIONS) }
    val tick by ServiceLocator.tick.collectAsState()
    val locked by ServiceLocator.locked.collectAsState()

    // v0.3.4 — écran de connexion AU DÉMARRAGE (demande opérateur : barre de
    // progression des états Tor + réplicas, miroir ConnexionsScreen Android).
    var startupDone by remember { mutableStateOf(false) }

    // v0.3.4 — passe-portail (miroir PasswordGate Android : création FORCÉE
    // NORMAL puis DESTRUCTION quand l'identité existe sans coffre).
    var gateDone by remember { mutableStateOf(DesktopVault.anySet()) }

    // v0.3.4 — plateau système + notifications (icône → remonte la fenêtre).
    // v0.3.11 — SUIIVI DU FOCUS : la suppression de notification (conversation
    // ouverte) ne s'applique QUE si la fenêtre est VISIBLE ET ACTIVE — avant,
    // ouvrir une conversation tuait les notifications du pair POUR TOUJOURS,
    // même fenêtre minimisée ou en arrière-plan (terrain 28/10 : « pas de
    // notification coté pc » alors que le message était bien stocké).
    LaunchedEffect(window) {
        appWindow = window
        DesktopNotifier.init {
            (window as? Frame)?.apply {
                isVisible = true
                extendedState = extendedState or Frame.NORMAL
                toFront()
            }
        }
        if (window is java.awt.Window) {
            val w = window as java.awt.Window
            w.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent) {
                    DesktopNotifier.setWindowActive(true)
                }
                override fun windowLostFocus(e: java.awt.event.WindowEvent) {
                    DesktopNotifier.setWindowActive(false)
                }
            })
            w.addWindowStateListener { e ->
                val iconified = e.newState and java.awt.Frame.ICONIFIED != 0
                DesktopNotifier.setWindowActive(!iconified && w.isFocused)
            }
            DesktopNotifier.setWindowActive(w.isFocused)
        }
    }

    // v0.3.8 — SONNERIE ENTRANTE : la fenêtre se RAMÈNE AU PREMIER PLAN et se
    // pose TOUJOURS AU-DESSUS pendant la sonnerie (session 78 : « pouvoir
    // décrocher ou rejeter un appel quand l'ordinateur est verrouillé sans
    // devoir tout déverrouiller »). NB : l'écran de verrouillage WINDOWS
    // lui-même ne peut pas afficher d'UI d'app tierce (restriction OS) — la
    // SONNERIE est le signal pendant le verrouillage système, la fenêtre
    // d'appel est là dès le déverrouillage ; le verrou de L'APP est couvert
    // par l'overlay ci-dessous. v0.3.9 : la CARTE DÉDIÉE
    // [IncomingCallWindow] (racine application{}) montre les icônes
    // Décrocher/Refuser pendant la sonnerie.
    // v0.3.11 — au DÉCROCHÉ aussi (ACTIVE) : la fenêtre principale revient AU
    // PREMIER PLAN une fois (terrain 28/10 : décroché depuis la carte dédiée,
    // fenêtre principale restée enterrée — l'appel semblait absent).
    val callUi by net.nyx.messenger.p2p.DesktopCallManager.ui.collectAsState()
    LaunchedEffect(callUi.phase) {
        val f = window as? Frame ?: return@LaunchedEffect
        if (callUi.phase == net.nyx.messenger.p2p.DesktopCallManager.Phase.INCOMING) {
            // v0.3.13 — PLUS JAMAIS alwaysOnTop SUR LA FENÊTRE PRINCIPALE
            // pendant l'INCOMING (terrain 29/10 : « la fenêtre pour décrocher
            // du pc se met derrière l'appli de messagerie » — la fenêtre
            // principale en alwaysOnTop + toFront() recouvrait la CARTE
            // DÉDIÉE IncomingCallWindow). On se contente de la DÉ-MINIMISER ;
            // la carte dédiée (alwaysOnTop + toFront propre) reste AU-DESSUS.
            f.isVisible = true
            // dé-minimisation (préserve l'état maximisé) : retire le bit ICONIFIED
            f.extendedState = f.extendedState and java.awt.Frame.ICONIFIED.inv()
        } else if (callUi.phase == net.nyx.messenger.p2p.DesktopCallManager.Phase.ACTIVE) {
            // v0.3.11 — au DÉCROCHÉ : ramenée AU PREMIER PLAN UNE FOIS (terrain
            // 28/10 : décroché depuis la carte dédiée, fenêtre principale
            // restée enterrée — l'appel semblait absent). Elle ne RESTE pas
            // always-on-top : la CARTE FLOTTANTE [CallFloatWindow] assure le
            // « raccrocher toujours au premier plan jusqu'à la fin de l'appel »
            // sans empêcher d'utiliser les autres applications.
            f.isVisible = true
            f.extendedState = f.extendedState and java.awt.Frame.ICONIFIED.inv()
            f.toFront()
        } else if (f.isAlwaysOnTop) {
            f.isAlwaysOnTop = false
        }
    }

    // Verrou au démarrage (miroir AppLock : armé si le coffre existe).
    LaunchedEffect(identityVersion) {
        if (hasIdentity.value && DesktopVault.anySet()) ServiceLocator.lock()
    }

    // ---------- onboarding ----------
    if (!hasIdentity.value) {
        OnboardingScreen(
            onCreate = { alias, password ->
                ServiceLocator.createIdentity(alias, password)
                hasIdentity.value = true
                identityVersion++
            },
            onRestore = { backupText, backupPassword, identityPassword ->
                ServiceLocator.restoreFromBackup(backupText, backupPassword, identityPassword)
                hasIdentity.value = true
                identityVersion++
            }
        )
        return
    }

    // ---------- passe-portail ----------
    if (!gateDone) {
        PasswordGate(onDone = { gateDone = true })
        return
    }

    // ---------- écran de verrou ----------
    if (locked) {
        LockScreen(onDestroyed = {
            // DESTRUCTION : tout est déjà effacé — l'app se referme (miroir killProcess).
            onQuit()
        })
        // v0.3.8 — L'OVERLAY D'APPEL RESTE VISIBLE AU-DESSUS DU VERROU DE L'APP
        // (demande session 78) : décrocher/refuser agissent directement sur
        // DesktopCallManager SANS déverrouiller l'app ; seul l'alias de
        // l'appelant est visible — jamais de contenu de message.
        net.nyx.messenger.ui.CallOverlay()
        return
    }

    // ---------- démarrage du backend ----------
    LaunchedEffect(identityVersion) {
        if (!backendStarted) {
            backendStarted = true
            runCatching { ServiceLocator.startBackend() }
                .onFailure {
                    DesktopLog.w("nyx-tor", "backend en échec au démarrage : ${it.message}")
                }
        }
    }

    // ---------- écran de connexion initial ----------
    if (!startupDone) {
        net.nyx.messenger.ui.StartupConnectionScreen(
            onConnected = { startupDone = true },
            onSkip = { startupDone = true },
        )
        return
    }

    // v0.3.6 — OVERLAY D'APPEL par-dessus TOUT (sonnerie entrante/sortante,
    // appel actif, motif de fin) tant que la phase ≠ IDLE (miroir Android).
    net.nyx.messenger.ui.CallOverlay()

    // Sélection d'un contact → conversation ouverte (accusé « lu »).
    LaunchedEffect(selectedPeer) {
        selectedPeer?.let {
            runCatching { ServiceLocator.repository.markConversationRead(it) }
            ServiceLocator.pollNow()
        }
    }

    // ---------- structure principale (miroir Android : liste ↔ conversation) ----------
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxSize()) {
            // — barre latérale : compte + navigation (miroir en-tête/⋮ Android) —
            Column(Modifier.width(280.dp).fillMaxHeight()) {
                Sidebar(
                    tab = tab,
                    onTab = { t ->
                        tab = t
                        if (t != SidebarTab.CONVERSATIONS) { selectedPeer = null; profilePeer = null }
                    },
                    selectedPeer = selectedPeer,
                    tick = tick,
                    onOpenMyProfile = { tab = SidebarTab.MON_PROFIL },
                    onOpenSettings = { tab = SidebarTab.REGLAGES },
                    onOpenMyOnion = { tab = SidebarTab.MON_ADRESSE },
                )
            }
            // — zone principale —
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                // Fiche du contact ouverte (miroir ContactProfileScreen).
                profilePeer?.let { peer ->
                    ContactProfileScreen(
                        peer = peer,
                        onBack = { profilePeer = null },
                        onMessage = { p -> profilePeer = null; selectedPeer = p; tab = SidebarTab.CONVERSATIONS },
                    )
                } ?: when (tab) {
                    SidebarTab.CONVERSATIONS -> selectedPeer?.let { peer ->
                        ChatPanel(
                            peer = peer,
                            tick = tick,
                            onClose = { selectedPeer = null },
                            onOpenProfile = { profPeer -> profilePeer = profPeer },
                        )
                    } ?: MainScreen(
                        tick = tick,
                        selectedPeer = selectedPeer,
                        onSelectPeer = { selectedPeer = it },
                        onOpenProfile = { profilePeer = it },
                        onOpenMyProfile = { tab = SidebarTab.MON_PROFIL },
                        onOpenSettings = { tab = SidebarTab.REGLAGES },
                        onOpenMyOnion = { tab = SidebarTab.MON_ADRESSE },
                    )
                    SidebarTab.CONNEXIONS -> net.nyx.messenger.ui.ConnexionsPanel(tick = tick)
                    SidebarTab.MON_ADRESSE -> MyOnionPanel(onOpenMyProfile = { tab = SidebarTab.MON_PROFIL })
                    SidebarTab.MON_PROFIL -> ProfileEditScreen(onDone = { tab = SidebarTab.CONVERSATIONS })
                    SidebarTab.REGLAGES -> SettingsPanel(
                        onAccountDisconnected = {
                            hasIdentity.value = false
                            identityVersion++
                            backendStarted = false
                            startupDone = false
                            selectedPeer = null
                            gateDone = DesktopVault.anySet()
                        }
                    )
                }
            }
        }
    }

    // ---------- dialogue de sortie (miroir ExitDialog Android) ----------
    // v0.3.11 — état OBSERVÉ : « Annuler » referme le dialogue IMMÉDIATEMENT
    // (terrain 28/10 : « le bouton annuler ne fait rien » — l'ancien @Volatile
    // n'était pas observé par Compose, le dialogue ne partait qu'au tick
    // de poll suivant, ≤ 15 s plus tard).
    val exitDialogOpen by exitDialogOpenFlow.collectAsState()
    if (exitDialogOpen) {
        AlertDialog(
            onDismissRequest = { exitDialogOpenFlow.value = false },
            title = { Text("Fermer Nyx ?") },
            text = {
                Text(
                    "« Réduire » garde Tor actif et les notifications vivantes en arrière-plan. " +
                        "« Quitter » arrête proprement Tor et ferme l'application."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    exitDialogOpenFlow.value = false
                    onHide()
                }) { Text("Réduire") }
            },
            dismissButton = {
                Row {
                    Button(onClick = {
                        exitDialogOpenFlow.value = false
                        onQuit()
                    }) { Text("Quitter") }
                    TextButton(onClick = { exitDialogOpenFlow.value = false }) { Text("Annuler") }
                }
            }
        )
    }
}
