package net.nyx.messenger.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Service au premier plan hébergeant le démon Tor embarqué (ADR-007).
 *
 * Il exécute le binaire tor embarqué (`libTor.so` fourni par tor-binary,
 * extrait dans nativeLibraryDir grâce à useLegacyPackaging) avec le torrc
 * généré par [NyxTorConfig], et draine la sortie du process vers tor.log.
 *
 * Invariant : si le service meurt, tor meurt avec lui (process enfant +
 * __OwningControllerProcess). Aucun trafic Nyx ne circule hors de ce démon.
 */
class NyxTorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.2.7 : Android 15/16 REFUSE startForeground quand le quota du type
        // FGS est épuisé (dataSync 6 h — logs device v0.2.6) → FATAL crash en
        // boucle à chaque redémarrage du service. On dégrade proprement : tor
        // continue en service ordinaire tant que le process vit (au premier
        // plan à cet instant), sans notification persistante. Le manifest est
        // par ailleurs passé à specialUse (pas de plafond de durée).
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Démarrage de Tor…", ready = false))
            foregroundActive.set(true)
        } catch (e: Exception) {
            android.util.Log.w(
                "nyx-tor",
                "startForeground refusé (${e.javaClass.simpleName}) — " +
                    "tor continue sans notification de premier plan"
            )
            foregroundActive.set(false)
        }

        val alreadyRunning = RUNNING_PROCESS.get() != null
        if (alreadyRunning) {
            // Service redémarré (ex. changement de config écran) : ne pas dupliquer le démon.
            return START_STICKY
        }

        val config = NyxTorConfig.create(this)
        val binary = resolveTorBinary()
        if (binary == null) {
            TorManager.get(this).reportFailure(
                "binaire tor introuvable ou non exécutable dans " +
                    "${applicationInfo.nativeLibraryDir} (attendu libTor.so/libtor.so — " +
                    "vérifier useLegacyPackaging — ADR-007)"
            )
            stopSelf()
            return START_NOT_STICKY
        }
        config.writeTorrc(android.os.Process.myPid())
        TorManager.get(this).attachConfig(config)

        try {
            val process = ProcessBuilder(binary.absolutePath, "-f", config.torrcFile.absolutePath)
                .directory(filesDir)
                .redirectErrorStream(true)
                .start()
            RUNNING_PROCESS.set(process)
            TorManager.get(this).reportStarted(config)

            // Draine la sortie du process (sinon le pipe se remplit et tor se bloque).
            scope.launch {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // Les logs de notice vont déjà dans tor.log (torrc) ; on garde
                        // un écho limité dans logcat pour le debug device.
                        android.util.Log.d("nyx-tor", line.take(400))
                    }
                }
            }

            // Surveillance de l'arrêt du process (crash tor) → état Failed.
            scope.launch {
                val code = process.waitFor()
                if (RUNNING_PROCESS.compareAndSet(process, null)) {
                    TorManager.get(this@NyxTorService)
                        .reportFailure("démon tor terminé (code $code) — voir tor.log")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            RUNNING_PROCESS.set(null)
            TorManager.get(this).reportFailure("lancement de tor impossible : ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        foregroundActive.set(false)
        RUNNING_PROCESS.getAndSet(null)?.destroy()
        scope.cancel()
        TorManager.get(this).reportStopped()
        super.onDestroy()
    }

    /**
     * Premier binaire tor exécutable trouvé dans nativeLibraryDir (l'extraction
     * sur disque est garantie par useLegacyPackaging — ADR-007), ou null si absent.
     */
    private fun resolveTorBinary(): File? =
        TOR_BINARY_CANDIDATES
            .map { File(applicationInfo.nativeLibraryDir, it) }
            .firstOrNull { it.exists() && it.canExecute() }

    private fun createChannel() {
        ensureChannel(this)
    }

    private fun buildNotification(text: String, ready: Boolean): Notification {
        return buildNotification(this, text, ready)
    }

    companion object {
        private const val CHANNEL_ID = "nyx_tor"
        private const val NOTIFICATION_ID = 0x4e59 // 'NY'

        /**
         * Noms possibles du binaire tor embarqué selon la source :
         * - topl tor-binary → libTor.so (majuscule)
         * - org.torproject.jni:tor-android → libtor.so (repli possible)
         */
        private val TOR_BINARY_CANDIDATES = listOf("libTor.so", "libtor.so")

        private val RUNNING_PROCESS = AtomicReference<Process?>(null)

        /**
         * v0.2.8 — true tant que la notification de premier plan est affichée.
         * startForeground peut être REFUSÉ (quota FGS épuisé, cf. onStartCommand) :
         * dans ce cas [pushState] ne doit PAS créer de notification fantôme.
         */
        private val foregroundActive = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Démarre le service au premier plan (safe : no-op si permission notification absente). */
        fun start(context: Context) {
            val intent = Intent(context, NyxTorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NyxTorService::class.java))
        }

        /** Canal idempotent (créé par le service et re-créé sans risque par [pushState]). */
        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Tor embarqué", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Démon Tor de Nyx (autonomie — ADR-007)" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context, text: String, ready: Boolean): Notification {
            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Nyx")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done.takeIf { ready }
                    ?: android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build()
        }

        /**
         * v0.2.8 — Statut RÉEL de la connexion Tor dans la notification de
         * premier plan (retour opérateur : « Démarrage de TOR » restait affiché
         * en permanence, même tor opérationnel — la notification n'était JAMAIS
         * mise à jour après startForeground). Appelé par TorManager à chaque
         * transition d'état : progression du bootstrap, succès, échec.
         *
         * No-op si le service n'est pas au premier plan (quota FGS épuisé —
         * mode dégradé v0.2.7) ou si le système refuse la mise à jour.
         */
        fun pushState(context: Context, text: String, ready: Boolean) {
            if (!foregroundActive.get()) return
            runCatching {
                val app = context.applicationContext
                ensureChannel(app)
                app.getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(app, text, ready))
            }.onFailure {
                android.util.Log.w("nyx-tor", "maj notification : ${it.message}")
            }
        }
    }
}
