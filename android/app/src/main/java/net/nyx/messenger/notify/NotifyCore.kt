package net.nyx.messenger.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import net.nyx.messenger.MainActivity

/**
 * v0.2.26 — CŒUR COMMUN des notificateurs ([ContactNotifier], [MessageNotifier]) :
 * contexte d'application, garde de permission Android 13+, PendingIntent de
 * contenu et publication. Extrait pour l'audit RED-DUP — UNE seule copie de
 * chaque mécanisme (canPost / contentIntent / notify).
 */
internal object NotifyCore {

    @Volatile
    var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Android 13+ : sans autorisation runtime, tout post est silencieux. */
    fun canPost(): Boolean {
        val ctx = appContext ?: return false
        return NotificationManagerCompat.from(ctx)
            .areNotificationsEnabled() &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Appui sur la notification → ouvre l'app ([MainActivity], tag par pair). */
    fun contentIntent(ctx: Context, onion: String, tag: String): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            (onion + tag).hashCode(),
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(ContactNotifier.EXTRA_ONION, onion)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Publication protégée (l'OS peut avoir révoqué entre-temps). */
    fun notify(id: Int, n: Notification) {
        val ctx = appContext ?: return
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}
