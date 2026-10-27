package net.nyx.messenger.desktop

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import net.nyx.messenger.ui.ChatPanel
import net.nyx.messenger.ui.ContactsPanel
import net.nyx.messenger.ui.MyOnionPanel
import net.nyx.messenger.ui.NyxTheme
import net.nyx.messenger.ui.OnboardingScreen
import net.nyx.messenger.ui.SettingsPanel
import net.nyx.messenger.ui.Sidebar
import net.nyx.messenger.ui.SidebarTab
import net.nyx.messenger.ui.TorBanner
import kotlin.system.exitProcess

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
        onCloseRequest = {
            // Fermeture = arrêt PROPRE (miroir ExitController Android : le démon
            // tor est tué, l'onion retiré, les stores déjà persistés à chaque
            // mutation).
            ServiceLocator.shutdownBackend()
            exitApplication()
        },
    ) {
        val themeSetting = ServiceLocator.settings.get().theme
        NyxTheme(themeSetting) {
            App()
        }
    }
    }
}

private enum class Tab { CONVERSATIONS, CONTACTS, CONNEXIONS, MON_ADRESSE, REGLAGES }

private fun Tab.toSidebar(): SidebarTab = when (this) {
    Tab.CONVERSATIONS -> SidebarTab.CONVERSATIONS
    Tab.CONTACTS -> SidebarTab.CONTACTS
    Tab.CONNEXIONS -> SidebarTab.CONNEXIONS
    Tab.MON_ADRESSE -> SidebarTab.MON_ADRESSE
    Tab.REGLAGES -> SidebarTab.REGLAGES
}

@Composable
fun App() {
    val hasIdentity = remember { mutableStateOf(ServiceLocator.identity.exists) }
    var backendStarted by remember { mutableStateOf(false) }
    var selectedPeer by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(Tab.CONVERSATIONS) }
    val tick by ServiceLocator.tick.collectAsState()

    if (!hasIdentity.value) {
        OnboardingScreen(
            onCreate = { alias, password ->
                ServiceLocator.createIdentity(alias, password)
                hasIdentity.value = true
            },
            // v0.3.2 — restauration par FICHIER DE SAUVEGARDE (enveloppe
            // chiffrée téléphone, JSON v1/v2 ou clé nue) : miroir de la
            // restauration Android (alias + profil + contacts + curseurs).
            onRestore = { backupText, backupPassword, identityPassword ->
                ServiceLocator.restoreFromBackup(backupText, backupPassword, identityPassword)
                hasIdentity.value = true
            }
        )
        return
    }

    // Démarrage du backend (Tor + réplique embarquée + boucle 30 s) une seule fois.
    // v0.3.3 — startBackend est NON BLOQUANT (travail sur Dispatchers.IO) :
    // AVANT, l'appel bloquait le thread UI AWT jusqu'à 4 minutes (cookie 60 s +
    // bootstrap 180 s) — fenêtre « Ne répond pas », progression jamais peinte
    // (cause racine du ressenti terrain « l'appli ne se connecte pas à tor »).
    LaunchedEffect(Unit) {
        if (!backendStarted) {
            backendStarted = true
            runCatching { ServiceLocator.startBackend() }
                .onFailure {
                    net.nyx.messenger.desktop.DesktopLog.w(
                        "nyx-tor", "backend en échec au démarrage : ${it.message}"
                    )
                }
        }
    }

    // Sélection d'un contact → conversation ouverte (accusé « lu »).
    LaunchedEffect(selectedPeer) {
        selectedPeer?.let {
            runCatching { ServiceLocator.repository.markConversationRead(it) }
            ServiceLocator.pollNow()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TorBanner()
            Row(Modifier.fillMaxSize()) {
                // — barre latérale (navigation + liste) —
                Column(Modifier.width(300.dp).fillMaxHeight()) {
                    Sidebar(
                        tab = tab.toSidebar(),
                        onTab = { t -> tab = when (t) {
                            SidebarTab.CONVERSATIONS -> Tab.CONVERSATIONS
                            SidebarTab.CONTACTS -> Tab.CONTACTS
                            SidebarTab.CONNEXIONS -> Tab.CONNEXIONS
                            SidebarTab.MON_ADRESSE -> Tab.MON_ADRESSE
                            SidebarTab.REGLAGES -> Tab.REGLAGES
                        }; if (t != SidebarTab.CONVERSATIONS) selectedPeer = null },
                        selectedPeer = selectedPeer,
                        onSelectPeer = { selectedPeer = it; tab = Tab.CONVERSATIONS },
                        tick = tick,
                    )
                }
                // — panneau principal —
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    when (tab) {
                        Tab.CONVERSATIONS -> selectedPeer?.let { peer ->
                            ChatPanel(peer = peer, tick = tick, onClose = { selectedPeer = null })
                        } ?: EmptyHint()
                        Tab.CONTACTS -> ContactsPanel(tick = tick)
                        Tab.CONNEXIONS -> net.nyx.messenger.ui.ConnexionsPanel(tick = tick)
                        Tab.MON_ADRESSE -> MyOnionPanel()
                        Tab.REGLAGES -> SettingsPanel()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Sélectionnez une conversation — ou ouvrez un contact pour commencer.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
    }
}
