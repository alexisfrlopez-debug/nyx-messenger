package net.nyx.messenger.notify

import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * v0.2.52 — SONNERIE D'APPEL POSSÉDÉE (terrain 25/10 : « Xiaomi continue de
 * sonner alors que l'appel est décroché »).
 *
 * CAUSE RACINE : la sonnerie était le SON DU CANAL de notification
 * (nyx_calls, IMPORTANCE_HIGH + URI sonnerie par défaut) et la notification
 * n'était annulée qu'à la FIN de l'appel ([CallManager.end]) — jamais au
 * décroché. Résultat : la sonnerie système jouait PAR-DESSUS l'appel en
 * cours (19 s mesurées terrain sur l'appel n°1 du 25/10) et MIUI peut la
 * boucler tant que la notification vit. Un son de canal n'est PAS
 * contrôlable : aucun code applicatif ne peut l'arrêter.
 *
 * CORRECTIF : le canal v0.2.52 ([ContactNotifier.CHANNEL_CALLS_V2]) est
 * SILENTIEUX (notification visuelle heads-up + vibration uniquement) et
 * CET objet joue la sonnerie en BOUCLE avec un MediaPlayer possédé —
 * stoppé DÉTERMINISTEMENT au décroché, au refus et à toute fin d'appel.
 * Attributs USAGE_NOTIFICATION_RINGTONE : le mode Ne pas déranger la
 * traite exactement comme l'ancien son de canal.
 *
 * Sécurité de repli : si le MediaPlayer ne démarre pas (URI absente,
 * lecture impossible), le marqueur « sonnerie IMPOSSIBLE » part dans les
 * logs et la notification visuelle reste le seul signal (jamais un crash
 * pour une sonnerie).
 */
object CallRinger {

    private val lock = Any()
    private var player: MediaPlayer? = null

    /** Démarre la sonnerie en boucle (idempotent — déjà active = no-op). */
    fun start() {
        synchronized(lock) {
            if (player != null) return
            val ctx = NotifyCore.appContext ?: return
            try {
                val uri = android.media.RingtoneManager
                    .getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                p.setDataSource(ctx, uri)
                p.isLooping = true
                p.prepare()
                p.start()
                player = p
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-ring",
                    "sonnerie DÉMARRÉE (boucle possédée — stop déterministe au décroché/refus/fin)"
                )
            } catch (e: Exception) {
                runCatching { player?.release() }
                player = null
                net.nyx.messenger.diag.DiagLog.w(
                    "nyx-ring",
                    "sonnerie IMPOSSIBLE (${e::class.simpleName} : ${e.message?.take(60)}) — " +
                        "notification visuelle seule"
                )
            }
        }
    }

    /** Stoppe la sonnerie (idempotent — déjà silencieuse = no-op). */
    fun stop() {
        synchronized(lock) {
            val p = player ?: return
            player = null
            runCatching { p.stop() }
            runCatching { p.release() }
            net.nyx.messenger.diag.DiagLog.i("nyx-ring", "sonnerie STOPPÉE")
        }
    }
}
