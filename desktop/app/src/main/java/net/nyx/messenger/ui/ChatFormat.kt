package net.nyx.messenger.ui

import net.nyx.messenger.desktop.DesktopStore

/**
 * v0.3.4 « parité Android » — aide-pure du panneau de conversation :
 * libellés EXACTS de l'Android (strings.xml + ChatViewModel) pour l'état
 * d'envoi, le compte à rebours éphémère et le cycle du minuteur. Pure +
 * internal : testées JVM (ChatFormatTest).
 */
internal object ChatFormat {

    /** Miroir outgoingStatusLabel Android : « Envoi via Tor… / Échec (réessaie) / ✓✓ Lu / ✓✓ Reçu / ✓ Envoyé ». */
    fun outgoingStatusLabel(m: DesktopStore.Msg): String = when {
        m.state == DesktopStore.STATE_PENDING -> "Envoi via Tor…"
        m.state == DesktopStore.STATE_FAILED -> "Échec (réessaie)"
        m.rcptRead > 0L -> "✓✓ Lu"
        m.rcptDelivered > 0L -> "✓✓ Reçu"
        else -> "✓ Envoyé"
    }

    /** Miroir ephemeralCountdownLabel Android v0.2.26 (libellés identiques). */
    fun ephemeralCountdownLabel(remainingMs: Long): String = when {
        remainingMs <= 0L -> "éphémère : expiration imminente"
        remainingMs < 60_000L -> "éphémère : moins d'1 min"
        remainingMs < 3_600_000L -> "éphémère : ${remainingMs / 60_000L} min restantes"
        remainingMs < 86_400_000L -> "éphémère : ${remainingMs / 3_600_000L} h restantes"
        else -> "éphémère : ${remainingMs / 86_400_000L} j restants"
    }

    /** Miroir cycleEphemeralTtl Android : OFF → 1 h → 24 h → 7 j → OFF. */
    fun ttlNext(current: Long): Long = when (current) {
        0L -> 3_600L
        3_600L -> 86_400L
        86_400L -> 604_800L
        else -> 0L
    }

    /** Libellé du minuteur (toast Android « Messages éphémères : %s »). */
    fun ttlLabel(ttl: Long): String = when (ttl) {
        3_600L -> "1 h"
        86_400L -> "24 h"
        604_800L -> "7 j"
        else -> "désactivés"
    }

    /** Politique de vue → libellé du badge (miroir Android). */
    fun viewBadge(viewPolicy: String): String? = when (viewPolicy) {
        DesktopStore.VIEW_ONCE -> "vue unique"
        DesktopStore.VIEW_TWICE -> "vue unique + 1 relecture"
        else -> null
    }
}
