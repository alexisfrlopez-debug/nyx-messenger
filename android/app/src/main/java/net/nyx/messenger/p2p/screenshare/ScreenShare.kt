package net.nyx.messenger.p2p.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import net.nyx.messenger.diag.DiagLog

/**
 * v0.2.60 — CAPTURE D'ÉCRAN (MediaProjection) POUR LE PARTAGE VALIDÉ.
 *
 * LE PROTOCOLE (miroir bureau v0.3.12) : le téléphone qui veut remplacer SA
 * caméra par SON écran en visio demande D'ABORD la permission au PC
 * (F_SCREEN_REQ) — la capture ne démarre qu'après SON F_SCREEN_ACCEPT **ET**
 * le consentement du dialogue système Android (createScreenCaptureIntent).
 * Les DEUX validations, toujours (demande opérateur session 78 : « partage
 * d'écran dans les 2 sens UNIQUEMENT si validé des 2 côtés »).
 *
 * Android 14+ : un service au premier plan de type mediaProjection DOIT être
 * démarré AVANT createVirtualDisplay — [ScreenCaptureService] héberge la
 * projection ; [ScreenShare] est la façade (grant/start/stop) consommée par
 * le moteur d'appel. Les trames partent en JPEG (~480 px, ~5 fps — budget
 * Tor identique au MJPEG caméra) directement sur le canal média chiffré :
 * AUCUNE image ne touche un réplica, aucune image n'est stockée.
 */
object ScreenShare {

    /** Consommatrice de trames JPEG (moteur vidéo de l'appel). */
    @Volatile private var onFrame: ((ByteArray) -> Unit)? = null

    /** Service vivant (projection active). */
    @Volatile var running: Boolean = false
        internal set

    /** Contexte applicatif (pour l'arrêt explicite du service). */
    @Volatile internal var lastContext: Context? = null

    /**
     * Consentement système OBTENU (dialogue createScreenCaptureIntent) :
     * démarre le service de capture. Appelé par l'UI (résultat d'activité).
     */
    fun grant(context: Context, resultCode: Int, data: Intent) {
        val ctx = context.applicationContext
        val svc = Intent(ctx, ScreenCaptureService::class.java)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc)
        else ctx.startService(svc)
        DiagLog.i("nyx-screen", "capture d'écran autorisée par le système — service au premier plan demandé")
    }

    /** Branche la consommatrice — true si la projection produit des trames. */
    fun start(context: Context, consumer: (ByteArray) -> Unit): Boolean {
        lastContext = context.applicationContext
        onFrame = consumer
        return running
    }

    /** Arrêt complet (raccroché, révocation du pair, retour caméra). */
    fun stop() {
        onFrame = null
        running = false
        // Le service s'arrête de lui-même (projection stoppée) ; un ordre
        // explicite couvre le cas d'un service résiduel.
        runCatching { lastContext?.let { it.stopService(Intent(it, ScreenCaptureService::class.java)) } }
    }

    internal fun deliver(jpeg: ByteArray) {
        val cb = onFrame ?: return
        runCatching { cb(jpeg) }
    }
}
