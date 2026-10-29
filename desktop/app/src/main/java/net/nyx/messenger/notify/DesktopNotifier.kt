package net.nyx.messenger.notify

import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.ServiceLocator
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Portage desktop de notify/MessageNotifier + ContactNotifier (Android) —
 * v0.3.4 « parité Android » : notifications du PLATEAU Windows.
 *
 *  - icône de plateau (lune dorée, miroir de l'icône de l'app) ;
 *  - message reçu → info-bulle « alias » + extrait 140 caractères (miroir
 *    BigText Android ; UNE notification par pair — le plateau remplace) ;
 *  - invitation → « Invitation de contact » (+ le pair, l'action
 *    Accepter/Refuser reste DANS l'application : les info-bulles Windows ne
 *    portent pas de boutons — équivalent fonctionnel, honnête) ;
 *  - invitation refusée / suppression → info-bulles discrètes ;
 *  - clic / double-clic sur l'icône → remonte la fenêtre (bringToFront
 *    injecté par Main.kt) ;
 *  - JAMAIS d'exception : plateau absent (Windows N, serveur headless…) =
 *    no-op silencieux — la messagerie ne dépend pas des notifications
 *    (miroir « no permission → silent » Android).
 *
 * Le pair dont la conversation est OUVERTE ne déclenche PAS de notification
 * (miroir suppressedPeer Android).
 */
object DesktopNotifier {

    @Volatile private var trayIcon: TrayIcon? = null
    @Volatile private var suppressedPeer: String? = null

    /** v0.3.11 — la fenêtre principale est-elle VISIBLE ET ACTIVE ? La
     *  suppression « conversation ouverte » ne s'applique QUE dans ce cas
     *  (terrain 28/10 : « pas de notification coté pc » — la conversation
     *  avait été ouverte plus tôt, la fenêtre était minimisée/dérrière, la
     *  bulle ne partait JAMAIS). */
    @Volatile private var windowActive = false

    @Volatile private var bringToFront: (() -> Unit)? = null

    /** À appeler une fois au démarrage (après la fenêtre) ; no-op si plateau absent. */
    fun init(onBringToFront: () -> Unit) {
        bringToFront = onBringToFront
        runCatching {
            if (!SystemTray.isSupported()) {
                DesktopLog.i("nyx-notify", "plateau système absent — notifications désactivées (l'app reste pleinement fonctionnelle)")
                return
            }
            val tray = SystemTray.getSystemTray()
            val icon = TrayIcon(moonIcon(16), "Nyx — messagerie de la nuit")
            icon.isImageAutoSize = true
            icon.addActionListener { bringToFront?.invoke() }
            tray.add(icon)
            trayIcon = icon
            DesktopLog.i("nyx-notify", "icône de plateau installée (notifications actives)")
        }.onFailure {
            DesktopLog.w("nyx-notify", "plateau indisponible : ${it.message?.take(60)} (ignoré)")
        }
    }

    /** La conversation ouverte ne notifie plus (miroir suppressedPeer) —
     *  v0.3.11 : UNIQUEMENT si la fenêtre est ACTIVE ([setWindowActive]). */
    fun suppress(peer: String?) {
        suppressedPeer = peer?.trim()?.lowercase()
    }

    /** v0.3.11 — visibilité/focus de la fenêtre principale (Main.kt) :
     *  conversation ouverte + fenêtre EN ARRIÈRE-PLAN = la bulle PART quand
     *  même (le message n'est pas sous les yeux de l'opérateur). */
    fun setWindowActive(active: Boolean) {
        windowActive = active
    }

    /** v0.3.14 — ramène la fenêtre principale devant (démimimisée + focus) —
     *  utilisé par la CARTE FLOTTANTE (clic sur l'aperçu vidéo) et l'icône
     *  du plateau. */
    fun activateWindow() {
        bringToFront?.invoke()
    }

    fun messageReceived(onion: String, alias: String, text: String) {
        if (!enabled() || (windowActive && suppressedPeer == onion.trim().lowercase())) return
        val preview = text.replace('\n', ' ').trim().take(140)
        balloon(alias.ifBlank { "Nyx" }, preview.ifBlank { "nouveau message" })
    }

    fun invitation(onion: String, alias: String) {
        if (!enabled()) return
        val label = alias.ifBlank { onion.take(10) + "…" }
        balloon("Invitation de contact", "$label souhaite vous ajouter comme contact.")
    }

    fun declined(alias: String) {
        if (!enabled()) return
        balloon("Invitation refusée", "Votre invitation a été refusée par ${alias.ifBlank { "un contact" }}.")
    }

    fun removed(alias: String) {
        if (!enabled()) return
        balloon("Supprimé des contacts", "Vous avez été supprimé de la liste de contacts de ${alias.ifBlank { "un contact" }}.")
    }

    fun updateReady(version: String) {
        if (!enabled()) return
        balloon("Mise à jour Nyx prête", "La version $version a été téléchargée — installez-la depuis les Réglages.")
    }

    /**
     * v0.3.6 — APPEL ENTRANT : info-bulle persistante (miroir
     * ContactNotifier.incomingCall Android). v0.3.9 — la CARTE DÉDIÉE
     * [net.nyx.messenger.ui.IncomingCallWindow] (toujours-au-dessus, icônes
     * ✓ Décrocher / ✕ Refuser) s'ouvre AUSSI — l'info-bulle ne fait
     * qu'annoncer ; terrain 28/10 : l'opérateur n'a pas trouvé l'overlay seul.
     */
    fun incomingCall(onion: String, alias: String) {
        if (!enabled()) return
        val label = alias.ifBlank { onion.take(10) + "…" }
        balloon(
            "Appel entrant — $label",
            "Décrocher (✓) ou Refuser (✕) dans la fenêtre d'appel qui vient de s'ouvrir."
        )
    }

    /** v0.3.6 — fin de sonnerie (décroché/refusé/manqué — miroir cancelCall). */
    fun cancelCall(onion: String) {
        // Pas d'annulation d'info-bulle portable en AWT — no-op volontaire
        // (l'overlay d'appel EST la source de vérité ; miroir des limites
        // documentées pour les invitations).
    }

    /**
     * v0.3.14 — DEMANDE DE PARTAGE D'ÉCRAN DU PAIR (demande terrain 29/10 :
     * « je n'ai pas non plus de notification pour le partage d'écran » — le
     * dialogue ✓/✕ vivait dans la fenêtre principale non focalisée). La
     * bulle annonce la demande ; le dialogue ✓/✕ vit sur la CARTE FLOTTANTE
     * (au premier plan). Cliquer l'icône du plateau ramène la fenêtre.
     */
    fun screenShareRequest(onion: String, alias: String) {
        if (!enabled()) return
        val label = alias.ifBlank { onion.take(10) + "…" }
        balloon(
            "$label demande à partager son écran",
            "Autoriser (✓) ou Refuser (✕) sur la carte d'appel flottante."
        )
    }

    private fun enabled(): Boolean = trayIcon != null &&
        runCatching { ServiceLocator.settings.get().notificationsEnabled }.getOrDefault(true)

    private fun balloon(title: String, text: String) {
        val icon = trayIcon ?: return
        runCatching {
            icon.displayMessage(title, text, TrayIcon.MessageType.INFO)
        }
    }

    /** Lune dorée sur fond nuit (miroir de nyx.ico / MoonHeader Android). */
    internal fun moonIcon(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val night = Color(0x11, 0x0D, 0x2B)
            val gold = Color(0xE8, 0xD5, 0xA8)
            g.color = night
            g.fillOval(0, 0, size - 1, size - 1)
            g.color = gold
            g.fillOval(0, 0, size - 1, size - 1)
            // Croissant : la lune dorée moins un cercle décalé couleur nuit.
            g.color = night
            val d = (size * 0.78).toInt()
            g.fillOval((size * 0.28).toInt(), (size * 0.02).toInt(), d, d)
        } finally {
            g.dispose()
        }
        return img
    }
}
