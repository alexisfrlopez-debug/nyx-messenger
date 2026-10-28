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
 * Service au premier plan (type mediaProjection — exigence Android 14+).
 * Produit des JPEG à ~5 fps depuis un ImageReader RGB mis à l'échelle ≤ 480 px.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: Thread? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (code == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = runCatching { mgr.getMediaProjection(code, data) }.getOrNull()
        if (proj == null) {
            DiagLog.w("nyx-screen", "MediaProjection refusée — service arrêté")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        ScreenShare.running = true
        ScreenShare.lastContext = applicationContext
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                DiagLog.i("nyx-screen", "projection arrêtée (système ou révocation)")
                finishCapture()
            }
        }, null)

        // Échelle ≤ 480 px de large (budget Tor identique au MJPEG caméra).
        val metrics = resources.displayMetrics
        val scale = (480.0 / metrics.widthPixels).coerceAtMost(1.0)
        val w = ((metrics.widthPixels * scale).toInt() / 2) * 2    // pair
        val h = ((metrics.heightPixels * scale).toInt() / 2) * 2
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = proj.createVirtualDisplay(
            "nyx-screen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
        DiagLog.i("nyx-screen", "capture d'écran ACTIVE (${w}x${h} @ ~5 fps — JPEG direct, aucun stockage)")

        worker = Thread({
            var lastSent = 0L
            while (!stopping) {
                try {
                    Thread.sleep(40)
                    val now = System.currentTimeMillis()
                    if (now - lastSent < 200) continue   // ~5 fps
                    val img: Image = reader?.acquireLatestImage() ?: continue
                    val jpeg = runCatching { imageToJpeg(img, w, h) }.getOrNull()
                    img.close()
                    if (jpeg != null) {
                        lastSent = now
                        ScreenShare.deliver(jpeg)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    DiagLog.w("nyx-screen", "trame échouée : ${e.message?.take(60)}")
                }
            }
        }, "nyx-screen-capture").also { it.isDaemon = true; it.start() }
        return START_NOT_STICKY
    }

    /** Image RGBA (rowStride padding éventuel) → JPEG. */
    private fun imageToJpeg(img: Image, w: Int, h: Int): ByteArray? {
        val plane = img.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val pixels = IntArray(w * h)
        if (pixelStride == 4 && rowStride == w * 4) {
            buffer.asIntBuffer().get(pixels)
        } else {
            var offset = 0
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                for (x in 0 until w) {
                    pixels[offset++] = buffer.int
                    // avance manuelle du stride (padding éventuel)
                    if (pixelStride > 4 && x < w - 1) {
                        buffer.position(buffer.position() + (pixelStride - 4))
                    }
                }
            }
        }
        val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream(24 * 1024)
        val ok = runCatching { bmp.compress(Bitmap.CompressFormat.JPEG, 62, out) }.getOrDefault(false)
        bmp.recycle()
        return if (ok) out.toByteArray() else null
    }

    private fun startInForeground() {
        val channelId = "nyx-screen"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Partage d'écran d'appel", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Nyx — partage d'écran actif")
            .setContentText("Votre écran est partagé pendant l'appel (validé par le pair).")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun finishCapture() {
        stopping = true
        ScreenShare.running = false
        runCatching { worker?.interrupt() }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null; reader = null; projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        DiagLog.i("nyx-screen", "capture d'écran ARRÊTÉE")
    }

    override fun onDestroy() {
        finishCapture()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIF_ID = 47472
    }
}
