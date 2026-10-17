package net.nyx.messenger.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.nyx.messenger.MainActivity
import net.nyx.messenger.R

/**
 * v0.2.14 — NOTIFICATIONS SYSTÈME du cycle de vie des contacts (demande
 * opérateur : « il faut que le deuxième téléphone ait une notification avec
 * le choix d'accepter ou refuser »).
 *
 * Trois cas notifiés (déclenchés par le poll 30 s — même moteur que la
 * réception) :
 *  1. INVITATION reçue → notification AVEC ACTIONS « Accepter / Refuser »
 *     ([ContactActionReceiver] — le choix part sans ouvrir l'app) ;
 *  2. notre invitation a été REFUSÉE par le pair ;
 *  3. nous avons été SUPPRIMÉS des contacts du pair.
 *
 * Permission POST_NOTIFICATIONS (Android 13+) : déclarée dans le manifest,
 * demandée À L'EXÉCUTION par l'écran des conversations (les deux téléphones
 * de test sont Android 15/16 — sans demande runtime, tout est silencieux).
 * Les notifications s'affichent aussi quand l'app est en arrière-plan : le
 * service Tor au premier plan + la boucle de poll au niveau application
 * maintiennent le processus vivant (v0.2.10).
 */
object ContactNotifier {

    const val CHANNEL_CONTACTS = "nyx_contacts"
    const val ACTION_ACCEPT = "net.nyx.messenger.notify.ACCEPT"
    const val ACTION_DECLINE = "net.nyx.messenger.notify.DECLINE"
    const val EXTRA_ONION = "onion"
    const val EXTRA_ALIAS = "alias"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val mgr = NotificationManagerCompat.from(context)
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONTACTS,
                context.getString(R.string.notif_channel_contacts),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    /** Android 13+ : sans autorisation runtime, tout post est silencieux. */
    private fun canPost(): Boolean {
        val ctx = appContext ?: return false
        return NotificationManagerCompat.from(ctx)
            .areNotificationsEnabled() &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun contentIntent(ctx: Context, onion: String): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            onion.hashCode(),
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_ONION, onion)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionIntent(ctx: Context, action: String, onion: String, alias: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            (action + onion).hashCode(),
            Intent(ctx, ContactActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_ONION, onion)
                putExtra(EXTRA_ALIAS, alias)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun simple(
        ctx: Context, titleRes: Int, textRes: Int,
        alias: String, onion: String
    ): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_CONTACTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(ctx.getString(titleRes))
            .setContentText(ctx.getString(textRes, alias))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(ctx.getString(textRes, alias))
            )
            .setContentIntent(contentIntent(ctx, onion))
            .setAutoCancel(true)
            .build()

    /** 1. Invitation reçue — AVEC les deux actions (Accepter / Refuser). */
    fun invitation(onion: String, alias: String) {
        val ctx = appContext ?: return
        if (!canPost()) return
        val n = NotificationCompat.Builder(ctx, CHANNEL_CONTACTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(ctx.getString(R.string.notif_invitation_title))
            .setContentText(ctx.getString(R.string.notif_invitation_text, alias))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(ctx.getString(R.string.notif_invitation_text, alias))
            )
            .setContentIntent(contentIntent(ctx, onion))
            .setAutoCancel(true)
            .addAction(
                0, ctx.getString(R.string.invitation_accept),
                actionIntent(ctx, ACTION_ACCEPT, onion, alias)
            )
            .addAction(
                0, ctx.getString(R.string.invitation_decline),
                actionIntent(ctx, ACTION_DECLINE, onion, alias)
            )
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(onion.hashCode(), n) }
    }

    /** 2. Notre invitation a été refusée par le pair. */
    fun declined(onion: String, alias: String) {
        val ctx = appContext ?: return
        if (!canPost()) return
        val n = simple(ctx, R.string.notif_declined_title, R.string.notif_declined_text, alias, onion)
        runCatching { NotificationManagerCompat.from(ctx).notify((onion + "d").hashCode(), n) }
    }

    /**
     * v0.2.18 — Appel entrant (ADR-015) : notification prioritaire pendant la
     * sonnerie — un appui ouvre l'app (l'écran d'appel est composé globalement).
     */
    fun incomingCall(onion: String, alias: String) {
        val ctx = appContext ?: return
        if (!canPost()) return
        val n = NotificationCompat.Builder(ctx, CHANNEL_CONTACTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(ctx.getString(R.string.notif_call_title))
            .setContentText(ctx.getString(R.string.notif_call_text, alias))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentIntent(ctx, onion))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify((onion + "call").hashCode(), n) }
    }

    /** 3. Nous avons été supprimé des contacts du pair. */
    fun removed(onion: String, alias: String) {
        val ctx = appContext ?: return
        if (!canPost()) return
        val n = simple(ctx, R.string.notif_removed_title, R.string.notif_removed_text, alias, onion)
        runCatching { NotificationManagerCompat.from(ctx).notify((onion + "r").hashCode(), n) }
    }

    /** Annule la notification d'une invitation (traitée depuis l'app ou les actions). */
    fun cancelInvitation(onion: String) {
        val ctx = appContext ?: return
        NotificationManagerCompat.from(ctx).cancel(onion.hashCode())
    }
}
