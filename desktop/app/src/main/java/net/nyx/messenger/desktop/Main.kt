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
                    else -> exitDialogOpen = true
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

/** État du dialogue de sortie (partagé main()/App()). */
@Volatile private var exitDialogOpen = false

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
    LaunchedEffect(window) {
        appWindow = window
        DesktopNotifier.init {
            (window as? Frame)?.apply {
                isVisible = true
                extendedState = extendedState or Frame.NORMAL
                toFront()
            }
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
    if (exitDialogOpen) {
        AlertDialog(
            onDismissRequest = { exitDialogOpen = false },
            title = { Text("Fermer Nyx ?") },
            text = {
                Text(
                    "« Réduire » garde Tor actif et les notifications vivantes en arrière-plan. " +
                        "« Quitter » arrête proprement Tor et ferme l'application."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    exitDialogOpen = false
                    onHide()
                }) { Text("Réduire") }
            },
            dismissButton = {
                Row {
                    Button(onClick = {
                        exitDialogOpen = false
                        onQuit()
                    }) { Text("Quitter") }
                    TextButton(onClick = { exitDialogOpen = false }) { Text("Annuler") }
                }
            }
        )
    }
}
