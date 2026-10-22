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

    // v0.2.10 : handler explicite — une exception résiduelle des coroutines
    // d'infrastructure (drain, watcher) ne doit JAMAIS tuer le process
    // (FATAL « read interrupted by close() » constaté en v0.2.9 à la fermeture).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                android.util.Log.w("nyx-tor", "coroutine d'infrastructure terminée : ${e.message}")
            }
    )

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
            // Service redémarré (ex. changement de config écran, écran revisté,
            // retour dans l'app) : ne pas dupliquer le démon. v0.2.9 : on
            // RE-ATTACHE le manager au démon existant — sinon l'état reste
            // coincé (Starting(0) posé par TorManager.start(), jamais remplacé
            // puisque personne ne re-notifie) et les écrans gatés (bouton
            // « Créer mon identité », sonde réplica) restent bloqués.
            TorManager.get(this).reattachRunning()
            return START_STICKY
        }

        // v0.2.9 — Filet de sécurité : un démon d'un process PRÉCÉDENT peut
        // survivre quelques secondes (SIGKILL du process : pas de onDestroy,
        // tor attend __OwningControllerProcess). S'il tient encore le verrou
        // DataDirectory, le nouveau démon meurt immédiatement (« usually
        // controlled by another process ») → « TOR ne veut plus se connecter ».
        // On tue tout tor orphelin de NOTRE uid avant de repartir.
        killLeftoverTor()

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
        // v0.2.26 — EMPREINTE DU BINAIRE EXÉCUTÉ (diagnostic terrain : un
        // export v0.2.25 montrait des bannières tor 0.4.6.5 historiques à
        // côté du 0.4.9.14 réel — le tor.log est APPEND et survit aux mises
        // à jour ; la version du démon qui tourne VRAIMENT doit être visible
        // dans les logs sans ambiguïté : chemin + taille + SHA-256).
        run {
            val size = runCatching { binary.length() }.getOrDefault(0L)
            val sha = runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                binary.inputStream().use { ins ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }.take(16)
                }
            }.getOrDefault("?")
            android.util.Log.i(
                "nyx-tor",
                "binaire tor : ${binary.name} (${size} o, sha256:${sha}) dans ${binary.parent}"
            )
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
            // v0.2.11 : drain sur THREAD DÉMON dédié — la version coroutine (v0.2.10)
            // laissait passer un FATAL « read interrupted by close() » quand l'annulation
            // du scope croisait la fermeture du pipe par process.destroy() (une coroutine
            // en état Cancelling qui lève une exception non-Cancellation remonte au fil
            // par défaut malgré le handler). Un thread n'est JAMAIS annulé : à la
            // fermeture le read lève, le runCatching l'avale, le thread meurt — point.
            // Le drain reste accessoire (les notices vont déjà dans tor.log via torrc).
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            android.util.Log.d("nyx-tor", line.take(400))
                        }
                    }
                } // fin de flux = arrêt du démon : cas normal, rien à faire
            }.apply {
                name = "nyx-tor-drain"
                isDaemon = true
                start()
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
        RUNNING_PROCESS.getAndSet(null)?.let { killBounded(it) }
        scope.cancel()
        TorManager.get(this).reportStopped()
        super.onDestroy()
    }

    /**
     * v0.2.9 — Kill borné : SIGTERM puis, si le démon ne meurt pas dans les
     * 3 s, SIGKILL. Lancé sur un thread dédié (onDestroy est sur le thread
     * principal — waitFor y est interdit) ; le thread survit à scope.cancel
     * et garantit que le verrou DataDirectory + les ports sont libérés même
     * si tor traîne à s'arrêter (bootstrap en cours). Rien n'est attendu sur
     * le thread appelant — le nouveau démon, lui, est protégé par
     * [killLeftoverTor] au cas où le SIGTERM traînerait.
     */
    private fun killBounded(process: Process) {
        process.destroy()  // SIGTERM — tor flush et libère le verrou proprement
        Thread {
            try {
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    android.util.Log.w("nyx-tor", "SIGTERM sans effet — SIGKILL du démon")
                    process.destroyForcibly()
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (e: InterruptedException) {
                process.destroyForcibly()
            }
        }.apply { name = "nyx-tor-kill" }.start()
    }

    /**
     * v0.2.9 — Tue tout démon tor ORPHELIN de notre uid (process précédent
     * tué par le système sans onDestroy : tor ignore encore la mort du
     * propriétaire). Recherche par cmdline dans /proc (même uid ⇒ lisibles).
     * Idempotent, silencieux s'il n'y a rien à faire.
     */
    private fun killLeftoverTor() {
        val myPid = android.os.Process.myPid()
        val procDir = java.io.File("/proc")
        val candidates = procDir.listFiles { f -> f.name.all { it.isDigit() } } ?: return
        for (p in candidates) {
            val pid = p.name.toIntOrNull() ?: continue
            if (pid == myPid) continue
            val cmdline = runCatching {
                java.io.File(p, "cmdline").readText()
            }.getOrNull() ?: continue
            if (cmdline.contains("libTor.so") || cmdline.contains("libtor.so")) {
                android.util.Log.w("nyx-tor", "démon tor orphelin détecté (pid $pid) — arrêt")
                runCatching { android.os.Process.killProcess(pid) }
            }
        }
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

        /**
         * v0.2.9 — Kill SYNCHRONE et borné du démon courant (hors thread UI) :
         * SIGTERM → 3 s → SIGKILL → 2 s. Utilisé par TorManager.stopNow()
         * (fermeture « Quitter complètement » et premier pas d'onboarding) :
         * au retour de cet appel, le verrou DataDirectory et les ports sont
         * libérés — un redémarrage immédiat de tor ne peut plus échouer sur
         * « usually controlled by another process ».
         */
        fun stopDaemonSync() {
            RUNNING_PROCESS.getAndSet(null)?.let { p ->
                p.destroy()
                try {
                    if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } catch (e: InterruptedException) {
                    p.destroyForcibly()
                    Thread.currentThread().interrupt()
                }
            }
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
