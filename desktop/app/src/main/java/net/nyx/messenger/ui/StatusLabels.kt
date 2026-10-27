package net.nyx.messenger.ui

import net.nyx.messenger.desktop.DesktopStore

/**
 * v0.3.4 « parité Android » — libellés de statut PARTAGÉS (miroir
 * strings.xml Android, utilisés par l'écran principal, le carnet et les
 * blocages). Pure + internal : testés JVM.
 */
internal object StatusLabels {

    /** InvitationStatusLine Android — libellé selon le statut du lien. */
    fun invitationStatus(s: DesktopStore.ContactStatus): String? = when (s) {
        DesktopStore.ContactStatus.PENDING -> "Invitation en attente de réponse."
        DesktopStore.ContactStatus.INCOMING -> "Vous a ajouté comme contact."
        DesktopStore.ContactStatus.DECLINED -> "Invitation déclinée."
        DesktopStore.ContactStatus.REMOVED -> "supprimé par ce contact"
        DesktopStore.ContactStatus.REMOVED_BY_ME -> "contact supprimé"
        else -> null
    }

    /** Couleur sémantique (index de palette Material) : 0=tertiaire, 1=primaire, 2=erreur, 3=contour. */
    fun invitationStatusTone(s: DesktopStore.ContactStatus): Int = when (s) {
        DesktopStore.ContactStatus.PENDING -> 0
        DesktopStore.ContactStatus.INCOMING -> 1
        DesktopStore.ContactStatus.DECLINED -> 2
        else -> 3
    }

    /** Statut de blocage (« bloqué le jj/MM/aaaa » — miroir blocked_since). */
    fun blockedSince(blockedAt: Long): String =
        "bloqué le " + java.time.Instant.ofEpochMilli(blockedAt)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))

    /** « Moi : … » (miroir list_me_prefix) + intitulé de prévisualisation. */
    fun previewLabel(m: DesktopStore.Msg): String {
        val text = if (m.kind == DesktopStore.KIND_FILE) {
            m.text.take(40)
        } else m.text.replace('\n', ' ').take(40)
        return if (m.outgoing) "Moi : $text" else text
    }
}
