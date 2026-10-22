package net.nyx.messenger.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * v0.2.20 — MISE À JOUR AUTOMATIQUE de la version NORMALE (demande opérateur :
 * « je veux que la version "normale" soit capable de se mettre à jour toute
 * seule, quand la dernière release est validée pour la production »).
 *
 * RÈGLE DE VALIDATION : chaque version est publiée en PRÉ-RELEASE tant qu'elle
 * n'est pas validée sur le terrain ; la passer en non-pré-release (« latest »)
 * EST l'acte de validation. Cette classe ne considère QUE la release « latest »
 * non-pré-release de GitHub — les versions de test sont donc ignorées
 * automatiquement. (Concerne UNIQUEMENT le build standard : le build test a
 * son propre canal de validation, cf. TestPlan.)
 *
 * FLUX : vérification (au démarrage puis toutes les 6 h) via l'API GitHub —
 * TOUT passe par le SOCKS de Tor embarqué ; si une version validée est plus
 * récente → téléchargement AUTOMATIQUE de l'APK « …-release.apk » dans le
 * cache → état prêt → notification + carte Paramètres → l'installation
 * déclenche l'installateur système (confirmation Android obligatoire).
 *
 * [parseLatest] et [isNewer] sont PURES → testables JVM (UpdaterTest).
 */
object Updater {

    const val RELEASES_URL =
        "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases/latest"

    /** Version installée (injectée par ServiceLocator — BuildConfig.VERSION_NAME). */
    @Volatile var currentVersion: String = ""

    /** Contexte application (notification + installateur). */
    @Volatile var appContext: android.content.Context? = null

    sealed class State {
        data object Idle : State()
        data class Checking(val silent: Boolean = true) : State()
        data class UpToDate(val tag: String) : State()
        data class Available(val release: ReleaseInfo) : State()
        data class Downloading(val release: ReleaseInfo, val bytes: Long, val total: Long) : State()
        data class Ready(val release: ReleaseInfo, val apk: File) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Une release GitHub pertinente pour la mise à jour. */
    data class ReleaseInfo(val tag: String, val version: String, val apkUrl: String)

    /**
     * Parse la réponse `GET /releases/latest` (le endpoint ne renvoie QUE des
     * non-pré-release — garde double : « prerelease":true » rejeté). Retourne
     * l'APK STANDARD (« …-release.apk ») — le variant test est ignoré.
     */
    fun parseLatest(json: String): ReleaseInfo? {
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: return null
        val prerelease = Regex("\"prerelease\"\\s*:\\s*true").containsMatchIn(json)
        if (prerelease) return null
        val apkUrl = Regex(
            "\\{[^{}]*?\"name\"\\s*:\\s*\"([^\"]*?)-release\\.apk\"[^{}]*?" +
                "\"browser_download_url\"\\s*:\\s*\"([^\"]+)\""
        ).findAll(json).lastOrNull()?.groupValues?.get(2) ?: return null
        return ReleaseInfo(tag, tag.removePrefix("v"), apkUrl)
    }

    /** Comparaison sémantique simple (X.Y.Z) — vrai si [tag] > [current]. */
    fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String): List<Int> =
            v.removePrefix("v").split("-").first().split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun client(socksPort: Int): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS) // 23 Mo via Tor — patience
        .build()

    private fun apkFile(cacheDir: File, version: String) =
        File(cacheDir, "update/nyx-v$version-release.apk")

    /**
     * Vérifie la dernière release VALIDÉE. [alreadyDownloaded] évite de
     * re-télécharger une APK déjà prête (relance de l'app).
     */
    suspend fun check(
        socksPort: Int,
        cacheDir: File,
        alreadyDownloaded: (String) -> File? = { v -> apkFile(cacheDir, v).takeIf { it.isFile } },
    ): State = withContext(Dispatchers.IO) {
        if (currentVersion.isBlank()) return@withContext State.Idle
        try {
            val req = Request.Builder().url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Nyx-Messenger")
                .build()
            client(socksPort).newCall(req).execute().use { resp ->
                check(resp.code == 200) { "HTTP ${resp.code}" }
                val release = parseLatest(resp.body!!.string())
                    ?: return@withContext State.UpToDate(currentVersion)
                if (!isNewer(release.version, currentVersion)) {
                    return@withContext State.UpToDate(release.tag)
                }
                val existing = alreadyDownloaded(release.version)
                if (existing != null && existing.isFile && existing.length() > 1_000_000) {
                    return@withContext State.Ready(release, existing)
                }
                State.Available(release)
            }
        } catch (e: Exception) {
            State.Failed(e.message ?: "réseau indisponible")
        }
    }

    /** Télécharge l'APK de la release dans le cache (via Tor), avec progression. */
    suspend fun download(
        release: ReleaseInfo,
        socksPort: Int,
        dest: File,
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val req = Request.Builder().url(release.apkUrl)
            .header("User-Agent", "Nyx-Messenger")
            .build()
        client(socksPort).newCall(req).execute().use { resp ->
            check(resp.code == 200) { "HTTP ${resp.code}" }
            val total = resp.body?.contentLength() ?: -1L
            val tmp = File(dest.absolutePath + ".part")
            resp.body!!.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _state.value = State.Downloading(release, done, total)
                    }
                }
            }
            check(tmp.length() > 1_000_000) { "APK tronqué (${tmp.length()} o)" }
            check(tmp.renameTo(dest)) { "renommage impossible" }
            dest
        }
    }

    /**
     * Boucle automatique : vérification à l'appel puis toutes les 6 h —
     * téléchargement immédiat dès qu'une version validée est disponible.
     * Appelée par ServiceLocator (build standard uniquement, mode NORMAL).
     */
    suspend fun autoLoop(socksPortProvider: () -> Int, cacheDir: File) {
        while (true) {
            if (_state.value !is State.Ready) {
                runCatching {
                    val st = check(socksPortProvider(), cacheDir)
                    _state.value = st
                    if (st is State.Available) {
                        val apk = apkFile(cacheDir, st.release.version)
                        val downloaded = download(st.release, socksPortProvider(), apk)
                        _state.value = State.Ready(st.release, downloaded)
                        notifyReady(st.release.version)
                        android.util.Log.i(
                            "nyx-update", "mise à jour ${st.release.version} prête à installer"
                        )
                    }
                }.onFailure { e ->
                    if (_state.value !is State.Ready) {
                        _state.value = State.Failed(e.message ?: "réseau indisponible")
                    }
                }
            }
            kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
        }
    }

    /** Notification « mise à jour prête » (canal contacts — non bloquant). */
    private fun notifyReady(version: String) {
        val ctx = appContext ?: return
        runCatching {
            val n = androidx.core.app.NotificationCompat.Builder(
                ctx, net.nyx.messenger.notify.ContactNotifier.CHANNEL_CONTACTS
            )
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(ctx.getString(net.nyx.messenger.R.string.update_notification_title))
                .setContentText(ctx.getString(net.nyx.messenger.R.string.update_notification_text, version))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        ctx, 42,
                        android.content.Intent(ctx, net.nyx.messenger.MainActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(42042, n)
        }
    }

    /** Déclenche l'installateur système (confirmation Android obligatoire). */
    fun install(apk: File): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".update", apk
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** Vérification manuelle (carte Paramètres) — rafraîchit l'état. */
    suspend fun checkNow(socksPort: Int, cacheDir: File) {
        _state.value = State.Checking(silent = false)
        _state.value = check(socksPort, cacheDir)
    }

    private const val CHECK_INTERVAL_MS = 6L * 3600 * 1000
}
