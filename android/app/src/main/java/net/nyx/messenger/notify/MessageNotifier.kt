package net.nyx.messenger.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import net.nyx.messenger.R

/**
 * v0.2.26 — NOTIFICATIONS DE MESSAGES REÇUS (demande opérateur : « pas de
 * notification à la réception d'un message » — jusqu'ici seuls le cycle de vie
 * des CONTACTS [ContactNotifier] et les APPELS étaient notifiés ; la réception
 * d'un message texte ou d'une pièce jointe n'affichait RIEN).
 *
 * Déclenché depuis [net.nyx.messenger.repo.NyxRepository] :
 *  - poll inbox (texte, après insertion réellement nouvelle — anti-doublon) ;
 *  - premier fragment d'une pièce jointe (journal OU direct P2P).
 *
 * Comportement :
 *  - canal dédié [CHANNEL_MESSAGES] importance HIGH (son + tête de liste) ;
 *  - UNE notification par pair (id = peer) — les messages suivants REMPLACENT
 *    la précédente avec l'aperçu le plus récent ;
 *  - PAS de notification si la conversation du pair est ouverte à l'écran
 *    ([suppressedPeer], posé par ChatViewModel) — sinon le marquage « lu »
 *    et la notification se battent à chaque poll ;
 *  - Android 13+ : sans POST_NOTIFICATIONS accordé, silence (garde [NotifyCore]).
 */
object MessageNotifier {

    const val CHANNEL_MESSAGES = "nyx_messages"

    /** Pair dont la conversation est OUVERTE à l'écran — pas de notification. */
    @Volatile
    var suppressedPeer: String? = null

    fun init(context: Context) {
        val mgr = NotificationManagerCompat_from(context)
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    /**
     * Message (texte ou libellé de pièce jointe) reçu de [onion].
     * [alias] = nom affiché du contact (alias local ou troncature d'onion).
     */
    fun messageReceived(onion: String, alias: String, text: String) {
        if (suppressedPeer == onion) return
        if (!NotifyCore.canPost()) return
        val ctx = NotifyCore.appContext ?: return
        val preview = text.replace('\n', ' ').trim().take(140)
        val n: Notification = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(alias)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(NotifyCore.contentIntent(ctx, onion, "msg"))
            .setAutoCancel(true)
            .build()
        NotifyCore.notify((onion + "msg").hashCode(), n)
    }
}

/** Alias local pour rester court sans dupliquer l'import. */
private fun NotificationManagerCompat_from(context: Context) =
    androidx.core.app.NotificationManagerCompat.from(context)
