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
 * v0.2.40 — CANAL TEST (demande opérateur : « j'aimerais que la version test
 * se mette à jour automatiquement dès qu'une nouvelle version est poussée sur
 * github ») : le build TEST suit la release la PLUS RÉCENTE publiée sur
 * GitHub, PRÉ-RELEASES INCLUSES (c'est justement le canal de test — chaque
 * livraison y est d'abord publiée en pré-release en attendant la validation
 * terrain, §9.6). Deux canaux disjoint :
 *  - STANDARD (TEST_BUILD=false) : endpoint /releases/latest → NON-pré-release
 *    uniquement, APK « …-release.apk » (règle de validation inchangée) ;
 *  - TEST (TEST_BUILD=true) : endpoint /releases (liste) → première release
 *    possédant un asset « …-test.apk », pré-release admise, APK « …-test.apk ».
 * Les deux partagent le même moteur (download/install/notification) et la
 * même règle anti-downgrade ([isNewer] compare X.Y.Z, suffixes ignorés).
 *
 * RÈGLE DE VALIDATION (canal standard) : chaque version est publiée en
 * PRÉ-RELEASE tant qu'elle n'est pas validée sur le terrain ; la passer en
 * non-pré-release (« latest ») EST l'acte de validation.
 *
 * FLUX : vérification (au démarrage puis périodiquement — 6 h standard,
 * 30 min test) via l'API GitHub — TOUT passe par le SOCKS de Tor embarqué ;
 * si une version plus récente existe → téléchargement AUTOMATIQUE de l'APK
 * dans le cache → état prêt → notification + bannière conversations + carte
 * Paramètres → l'installation déclenche l'installateur système (confirmation
 * Android obligatoire).
 *
 * [parseLatest], [parseLatestTest] et [isNewer] sont PURES → testables JVM
 * (UpdaterTest).
 */
object Updater {

    const val RELEASES_URL =
        "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases/latest"

    /** Canal test : liste des releases (plus récentes d'abord, pré-releases admises). */
    const val RELEASES_LIST_URL =
        "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases?per_page=20"

    /** Version installée (injectée par ServiceLocator — BuildConfig.VERSION_NAME). */
    @Volatile var currentVersion: String = ""

    /** Canal TEST ? (injecté par ServiceLocator — BuildConfig.TEST_BUILD). */
    @Volatile var testChannel: Boolean = false

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

    /**
     * v0.2.40 — CANAL TEST : parse la réponse `GET /releases` (TABLEAU trié par
     * l'API GitHub de la plus récente à la plus ancienne). Retourne la PREMIÈRE
     * release possédant un asset « …-test.apk » — PRÉ-RELEASES ADMISES (le
     * canal test suit chaque livraison dès sa publication, c'est sa raison
     * d'être) ; les DRAFTS sont rejetés par sécurité (l'API publique ne les
     * liste normalement pas). L'ordre d'un asset est sans importance : on
     * cherche « …-test.apk » jamais « …-release.apk ». La règle anti-downgrade
     * est appliquée APRÈS par [isNewer] (suffixe « -test » de versionName
     * ignoré par la comparaison) — une liste entièrement plus ancienne ne
     * déclenche donc jamais de rétrogradation.
     */
    fun parseLatestTest(json: String): ReleaseInfo? {
        val tagRegex = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
        val tags = tagRegex.findAll(json).toList()
        if (tags.isEmpty()) return null
        for ((i, m) in tags.withIndex()) {
            // Section = cette release (tag_name, prerelease, assets…) jusqu'au
            // tag_name suivant. Le début de la release suivante (url, id…) ne
            // contient ni « draft » ni asset → aucun faux positif possible.
            val end = tags.getOrNull(i + 1)?.range?.first ?: json.length
            val section = json.substring(m.range.first, end)
            if (Regex("\"draft\"\\s*:\\s*true").containsMatchIn(section)) continue
            val tag = m.groupValues[1]
            val apkUrl = Regex(
                "\\{[^{}]*?\"name\"\\s*:\\s*\"([^\"]*?-test\\.apk)\"[^{}]*?" +
                    "\"browser_download_url\"\\s*:\\s*\"([^\"]+)\""
            ).find(section)?.groupValues?.get(2) ?: continue
            return ReleaseInfo(tag, tag.removePrefix("v"), apkUrl)
        }
        return null
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
        File(cacheDir, if (testChannel) "update/nyx-v$version-test.apk"
                       else "update/nyx-v$version-release.apk")

    /**
     * Vérifie la dernière release pertinente DU CANAL (standard : dernière
     * VALIDÉE non-pré-release ; test : dernière PUBLIÉE, pré-releases
     * incluses). [alreadyDownloaded] évite de re-télécharger une APK déjà
     * prête (relance de l'app).
     */
    suspend fun check(
        socksPort: Int,
        cacheDir: File,
        alreadyDownloaded: (String) -> File? = { v -> apkFile(cacheDir, v).takeIf { it.isFile } },
    ): State = withContext(Dispatchers.IO) {
        if (currentVersion.isBlank()) return@withContext State.Idle
        try {
            val req = Request.Builder().url(if (testChannel) RELEASES_LIST_URL else RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Nyx-Messenger")
                .build()
            client(socksPort).newCall(req).execute().use { resp ->
                check(resp.code == 200) { "HTTP ${resp.code}" }
                val body = resp.body!!.string()
                val release = (if (testChannel) parseLatestTest(body) else parseLatest(body))
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
     * Boucle automatique : vérification à l'appel puis périodiquement — 6 h
     * (canal standard) / 30 min (canal test : chaque livraison doit être
     * proposée « dès qu'une nouvelle version est poussée sur github », sans
     * attendre un cycle long) — téléchargement immédiat dès qu'une version
     * pertinente est disponible. Appelée par ServiceLocator (les DEUX builds
     * depuis v0.2.40 — canal sélectionné par [testChannel]).
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
                            "nyx-update", "mise à jour ${st.release.version}" +
                                (if (testChannel) " (canal test)" else "") +
                                " prête à installer"
                        )
                    }
                }.onFailure { e ->
                    if (_state.value !is State.Ready) {
                        _state.value = State.Failed(e.message ?: "réseau indisponible")
                    }
                }
            }
            kotlinx.coroutines.delay(if (testChannel) CHECK_INTERVAL_TEST_MS else CHECK_INTERVAL_MS)
        }
    }

    /** Notification « mise à jour prête » (canal contacts — non bloquant).
     * v0.2.40 : titre distinct pour le canal TEST (l'opérateur voit
     * immédiatement QUEL Nyx demande l'installation). */
    private fun notifyReady(version: String) {
        val ctx = appContext ?: return
        runCatching {
            val n = androidx.core.app.NotificationCompat.Builder(
                ctx, net.nyx.messenger.notify.ContactNotifier.CHANNEL_CONTACTS
            )
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(
                    ctx.getString(
                        if (testChannel) net.nyx.messenger.R.string.update_notification_title_test
                        else net.nyx.messenger.R.string.update_notification_title
                    )
                )
                .setContentText(
                    ctx.getString(
                        net.nyx.messenger.R.string.update_notification_text,
                        if (testChannel) "$version (test)" else version
                    )
                )
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

    /** Canal test : réactivité (30 min) — chaque livraison GitHub doit être
     *  proposée rapidement (demande v0.2.40). */
    private const val CHECK_INTERVAL_TEST_MS = 30L * 60 * 1000
}
