package net.nyx.messenger.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * v0.2.20 — MISE À JOUR AUTOMATIQUE de la version NORMALE (demande opérateur :
 * « je veux que la version "normale" soit capable de se mettre à jour toute
 * seule, quand la dernière release est validée pour la production »).
 *
 * v0.2.40 — CANAL TEST (demande opérateur : « j'aimerais que la version test
 * se mette à jour automatiquement dès qu'une nouvelle version est poussée sur
 * github ») : le build TEST suit la release la PLUS RÉCENTE publiée sur
 * GitHub, PRÉ-RELEASES INCLUSES. Deux canaux disjoint :
 *  - STANDARD (TEST_BUILD=false) : dernière NON-pré-release, APK « …-release.apk » ;
 *  - TEST (TEST_BUILD=true) : dernière PUBLIÉE (pré-release admise), APK « …-test.apk ».
 *
 * v0.2.42 — PROTOCOLE DE SECOURS RÉSEAU (retour terrain : « logs envoyés /
 * l'application ne voit pas la mise à jour ») : CAUSE RACINE — l'API REST
 * api.github.com limite les requêtes NON authentifiées à 60/h PAR IP ; via Tor
 * les IPs de sortie sont PARTAGÉES par des milliers d'utilisateurs → quota
 * épuisé quasi en permanence → HTTP 403 → l'app ne voyait JAMAIS les nouvelles
 * releases. Chaîne de résolution à TROIS maillons (tous via le SOCKS Tor) :
 *  1. API REST (officielle, structurée — quand le quota le permet) ;
 *  2. Atom feed `releases.atom` (canal test) / redirection `releases/latest`
 *     (canal standard) — servis par l'infrastructure WEB github.com, NON
 *     soumis au quota API ;
 * 3. `releases/expanded_assets/<tag>` — fragment HTML listant les assets de
 *    la release → URL exacte de l'APK (le nom contient un horodatage, il est
 *    donc impossible de la construire à partir du seul tag).
 * La stratégie est ADAPTATIVE : le dernier maillon qui a réussi est tenté en
 * premier au cycle suivant (moins de latence Tor inutile).
 *
 * v0.2.42 — INTÉGRITÉ RENFORCÉE (instruction session 61 : « vérification
 * SHA-256 (SHA256SUMS) ») : après téléchargement, le digest de l'APK est
 * comparé à celui publié dans SHA256SUMS-vX.Y.Z.txt de la MÊME release —
 * defense-in-depth par-dessus TLS (corruption partielle, erreur de release).
 * Le téléchargement lui-même gagne REPRISE (HTTP Range) + 3 tentatives —
 * les circuits Tor peuvent se rompre en cours de flux (~23-40 Mo).
 *
 * FLUX : vérification (au démarrage puis périodiquement — 6 h standard,
 * 30 min test ; en cas d'échec réseau : nouvelle tentative après 2 min)
 * → téléchargement AUTOMATIQUE → vérification SHA-256 → notification +
 * bannière conversations + carte Paramètres → installation via l'installateur
 * système (confirmation Android obligatoire).
 *
 * [parseLatest], [parseLatestTest], [parseAtomTags], [tagFromRedirect],
 * [assetUrlFromExpanded], [parseSha256Sums] et [isNewer] sont PURES →
 * testables JVM (UpdaterTest).
 */
object Updater {

    const val RELEASES_URL =
        "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases/latest"

    /** Canal test : liste des releases (plus récentes d'abord, pré-releases admises). */
    const val RELEASES_LIST_URL =
        "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases?per_page=20"

    /**
     * v0.2.42 — ENDPOINTS WEB DE REPLI (aucune limite de type API : servis par
     * le site github.com, pas le REST API — vérifiés opérationnels via Tor).
     */
    const val ATOM_FEED_URL =
        "https://github.com/alexisfrlopez-debug/nyx-messenger/releases.atom"
    const val LATEST_RELEASE_URL =
        "https://github.com/alexisfrlopez-debug/nyx-messenger/releases/latest"
    const val EXPANDED_ASSETS_URL =
        "https://github.com/alexisfrlopez-debug/nyx-messenger/releases/expanded_assets/"

    /** Version installée (injectée par ServiceLocator — BuildConfig.VERSION_NAME). */
    @Volatile var currentVersion: String = ""

    /** Canal TEST ? (injecté par ServiceLocator — BuildConfig.TEST_BUILD). */
    @Volatile var testChannel: Boolean = false

    /** Contexte application (notification + installateur). */
    @Volatile var appContext: android.content.Context? = null

    /**
     * Stratégie préférée pour le PROCHAIN cycle (adaptatif) : true = API REST
     * d'abord, false = endpoints web d'abord. Bascule automatiquement vers le
     * dernier maillon qui a réussi — via Tor l'API échoue si souvent que le
     * repli web devient la voie rapide.
     */
    @Volatile private var preferApi: Boolean = true

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
    data class ReleaseInfo(
        val tag: String,
        val version: String,
        val apkUrl: String,
        val sumsUrl: String? = null,
    )

    // =====================================================================
    // PARSEURS PURS (testables JVM) — API REST
    // =====================================================================

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
        val apkUrl = assetUrlFromApiJson(json, "-release.apk") ?: return null
        return ReleaseInfo(tag, tag.removePrefix("v"), apkUrl, assetUrlFromApiJson(json, "SHA256SUMS"))
    }

    /**
     * v0.2.40 — CANAL TEST : parse la réponse `GET /releases` (TABLEAU trié par
     * l'API GitHub de la plus récente à la plus ancienne). Retourne la PREMIÈRE
     * release possédant un asset « …-test.apk » — PRÉ-RELEASES ADMISES (le
     * canal test suit chaque livraison dès sa publication, c'est sa raison
     * d'être) ; les DRAFTS sont rejetés par sécurité. La règle anti-downgrade
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
            val apkUrl = assetUrlFromApiJson(section, "-test.apk") ?: continue
            return ReleaseInfo(
                tag, tag.removePrefix("v"), apkUrl, assetUrlFromApiJson(section, "SHA256SUMS")
            )
        }
        return null
    }

    /** URL d'un asset de nom contenant [needle] dans un objet asset du JSON GitHub. */
    private fun assetUrlFromApiJson(json: String, needle: String): String? {
        val m = Regex(
            "\\{[^{}]*?\"name\"\\s*:\\s*\"([^\"]*?)\"[^{}]*?" +
                "\"browser_download_url\"\\s*:\\s*\"([^\"]+)\""
        ).findAll(json).firstOrNull { it.groupValues[1].contains(needle) } ?: return null
        return m.groupValues[2]
    }

    // =====================================================================
    // PARSEURS PURS (testables JVM) — ENDPOINTS WEB DE REPLI (v0.2.42)
    // =====================================================================

    /**
     * Tags des releases du flux Atom, ORDRE CHRONOLOGIQUE INVERSE (la plus
     * récente d'abord — c'est l'ordre du flux). Le flux liste les releases
     * PUBLIÉES (pré-releases incluses, jamais les drafts). Format des entrées :
     * `<id>tag:github.com,2008:Repository/1415030332/v0.2.41</id>`.
     */
    fun parseAtomTags(xml: String): List<String> =
        Regex("<id>tag:github\\.com[^<]*?/(v[0-9A-Za-z.\\-]+)</id>")
            .findAll(xml).map { it.groupValues[1] }.distinct().toList()

    /**
     * Tag extrait de l'URL finale après la redirection 302 de
     * `releases/latest` (…/releases/tag/<tag>) — canal standard uniquement.
     */
    fun tagFromRedirect(url: String): String? =
        Regex("/releases/tag/([^?#]+)").find(url)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    /**
     * URL absolue de l'asset dont le NOM contient [needle], depuis le fragment
     * HTML de `releases/expanded_assets/<tag>`. Les liens sont RELATIFS
     * (`href="/<owner>/<repo>/releases/download/<tag>/<fichier>"`) → préfixe
     * https://github.com. needle est disjoint par canal (« -test.apk » /
     * « -release.apk » / « SHA256SUMS ») : aucun croisement possible.
     */
    fun assetUrlFromExpanded(html: String, needle: String): String? =
        Regex("href=\"(/[^\"]+/releases/download/[^\"]+)\"")
            .findAll(html).map { it.groupValues[1] }
            .firstOrNull { it.substringAfterLast('/').contains(needle) }
            ?.let { "https://github.com" + it }

    /**
     * Digest SHA-256 attendu du fichier [fileName] dans le contenu d'un
     * SHA256SUMS (format GNU : `<hex64>␣[*]<nom>`, une entrée par ligne —
     * l'astérisque binaire possible). Retourne null si le fichier n'y figure
     * pas (vérification simplement ignorée, pas un échec).
     */
    fun parseSha256Sums(content: String, fileName: String): String? =
        content.lineSequence().mapNotNull { line ->
            val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(\\S+)\\s*$").find(line) ?: return@mapNotNull null
            if (m.groupValues[2] == fileName) m.groupValues[1].lowercase() else null
        }.firstOrNull()

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

    // =====================================================================
    // RÉSOLUTION — chaîne API → repli web (v0.2.42)
    // =====================================================================

    private fun client(socksPort: Int): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS) // ~23-40 Mo via Tor — patience
        .build()

    private fun get(url: String, socksPort: Int, accept: String? = null): okhttp3.Response =
        client(socksPort).newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Nyx-Messenger")
                .apply { if (accept != null) header("Accept", accept) }
                .build()
        ).execute()

    private fun apkFile(cacheDir: File, version: String) =
        File(cacheDir, if (testChannel) "update/nyx-v$version-test.apk"
                       else "update/nyx-v$version-release.apk")

    /** Maillon 1 — API REST officielle. */
    private fun viaApi(socksPort: Int): ReleaseInfo? =
        get(if (testChannel) RELEASES_LIST_URL else RELEASES_URL,
            socksPort, accept = "application/vnd.github+json").use { resp ->
            if (resp.code == 403 || resp.code == 429) {
                // Quota épuisé (IP de sortie Tor partagée) — trace exploitable
                // dans les exports de logs (LogCollector embarque le logcat).
                android.util.Log.i("nyx-update", "API GitHub HTTP ${resp.code} (quota IP partagée via Tor) → repli web")
                throw IOException("API GitHub HTTP ${resp.code} (quota)")
            }
            check(resp.code == 200) { "API GitHub HTTP ${resp.code}" }
            val body = resp.body!!.string()
            (if (testChannel) parseLatestTest(body) else parseLatest(body))
        }

    /** Maillon 2+3 — endpoints web (Atom/redirection, puis expanded_assets). */
    private fun viaWeb(socksPort: Int): ReleaseInfo? =
        if (testChannel) viaWebTest(socksPort) else viaWebStandard(socksPort)

    /**
     * Canal TEST via le web : Atom feed (releases les plus récentes d'abord,
     * pré-releases admises — le flux ne contient jamais les drafts), puis au
     * plus 3 tags sondés sur expanded_assets jusqu'à trouver l'APK « -test ».
     */
    private fun viaWebTest(socksPort: Int): ReleaseInfo? {
        val tags = get(ATOM_FEED_URL, socksPort).use { resp ->
            check(resp.code == 200) { "Atom HTTP ${resp.code}" }
            parseAtomTags(resp.body!!.string())
        }
        val first = tags.firstOrNull() ?: return null
        if (!isNewer(first, currentVersion)) return null // rien de plus récent que nous
        var tried = 0
        for (tag in tags) {
            if (tried++ >= 3) break // latence Tor : raisonnable
            if (!isNewer(tag, currentVersion)) break // ordre chronologique inverse
            releaseFromAssets(socksPort, tag)?.let { return it }
        }
        return null
    }

    /**
     * Canal STANDARD via le web : `releases/latest` suit une redirection 302
     * vers la dernière release VALIDÉE (non-pré-release) — le tag est lu dans
     * l'URL finale. Un 404 signifie « aucune release validée » → à jour.
     */
    private fun viaWebStandard(socksPort: Int): ReleaseInfo? {
        val tag = get(LATEST_RELEASE_URL, socksPort).use { resp ->
            if (resp.code == 404) return null // aucune release validée → à jour
            check(resp.code == 200) { "latest HTTP ${resp.code}" }
            tagFromRedirect(resp.request.url.toString())
        } ?: return null
        if (!isNewer(tag, currentVersion)) return null
        return releaseFromAssets(socksPort, tag)
    }

    /** Assets d'une release (fragment HTML) → ReleaseInfo (APK du canal + SHA256SUMS). */
    private fun releaseFromAssets(socksPort: Int, tag: String): ReleaseInfo? {
        val html = get(EXPANDED_ASSETS_URL + tag, socksPort).use { resp ->
            check(resp.code == 200) { "assets HTTP ${resp.code} pour $tag" }
            resp.body!!.string()
        }
        val apk = assetUrlFromExpanded(html, if (testChannel) "-test.apk" else "-release.apk")
            ?: return null
        return ReleaseInfo(tag, tag.removePrefix("v"), apk, assetUrlFromExpanded(html, "SHA256SUMS"))
    }

    /**
     * Chaîne de résolution ADAPTATIVE : la stratégie qui a réussi au cycle
     * précédent est tentée en premier ; en cas d'échec l'autre prend le
     * relais. Retourne null si une stratégie a répondu de manière DÉFINITIVE
     * (« rien de plus récent que la version installée »).
     */
    private fun resolveRelease(socksPort: Int): ReleaseInfo? {
        val strategies: List<() -> ReleaseInfo?> =
            if (preferApi) listOf({ viaApi(socksPort) }, { viaWeb(socksPort) })
            else listOf({ viaWeb(socksPort) }, { viaApi(socksPort) })
        var lastError: Exception? = null
        for ((i, strategy) in strategies.withIndex()) {
            try {
                val r = strategy()
                // La voie qui vient de fonctionner devient la voie rapide du
                // prochain cycle (i suit l'ordre essayé ci-dessus).
                val usedApi = if (preferApi) i == 0 else i == 1
                preferApi = usedApi
                return r
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("GitHub injoignable (API + repli web) : ${lastError?.message}")
    }

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
            val release = resolveRelease(socksPort)
                ?: return@withContext State.UpToDate(currentVersion)
            if (!isNewer(release.version, currentVersion)) {
                return@withContext State.UpToDate(release.tag)
            }
            val existing = alreadyDownloaded(release.version)
            if (existing != null && existing.isFile && existing.length() > 1_000_000) {
                return@withContext State.Ready(release, existing)
            }
            State.Available(release)
        } catch (e: Exception) {
            State.Failed(e.message ?: "réseau indisponible")
        }
    }

    // =====================================================================
    // TÉLÉCHARGEMENT — reprise (Range) + tentatives + SHA-256 (v0.2.42)
    // =====================================================================

    /**
     * Télécharge l'APK de la release dans le cache (via Tor), avec progression,
     * REPRISE sur coupure de circuit (HTTP Range — le .part est conservé entre
     * les tentatives), 3 tentatives, puis VÉRIFICATION SHA-256 contre le
     * SHA256SUMS de la même release si disponible (instruction session 61).
     */
    suspend fun download(
        release: ReleaseInfo,
        socksPort: Int,
        dest: File,
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        var attempt = 0
        while (true) {
            attempt++
            try {
                val resumeFrom = if (tmp.isFile) tmp.length() else 0L
                val req = Request.Builder().url(release.apkUrl)
                    .header("User-Agent", "Nyx-Messenger")
                    .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
                    .build()
                client(socksPort).newCall(req).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) throw IOException("HTTP ${resp.code}")
                    // 206 = le serveur honore la reprise → on APPEND au .part ;
                    // 200 = reprise ignorée → on repart de zéro.
                    val appending = resp.code == 206 && resumeFrom > 0
                    if (!appending && tmp.isFile) tmp.delete()
                    var total = resp.body?.contentLength() ?: -1L
                    if (appending && total >= 0) total += resumeFrom
                    resp.body!!.byteStream().use { input ->
                        FileOutputStream(tmp, appending).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = if (appending) resumeFrom else 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                _state.value = State.Downloading(release, done, total)
                            }
                        }
                    }
                    check(tmp.length() > 1_000_000) { "APK tronquée (${tmp.length()} o)" }
                    verifySha256(release, socksPort, tmp)
                    check(tmp.renameTo(dest)) { "renommage impossible" }
                    return@withContext dest
                }
            } catch (e: Exception) {
                // Le .part est conservé : la tentative suivante reprend de là
                // où le circuit s'est rompu (sauf erreur de vérification).
                if (attempt >= 3) throw e
                android.util.Log.i(
                    "nyx-update",
                    "téléchargement tentative $attempt échouée (${e.message}) — reprise dans 5 s"
                )
                delay(5_000)
            }
        }
        @Suppress("UNREACHABLE_CODE") dest
    }

    /**
     * Vérifie le SHA-256 de l'APK téléchargée contre le SHA256SUMS publié
     * dans la même release (defense-in-depth par-dessus TLS, instruction
     * session 61). Un échec de RÉCUPÉRATION du SHA256SUMS (réseau Tor) ne
     * bloque PAS la mise à jour — TLS protège déjà le transport ; seule une
     * divergence de DIGEST est fatale (APK corrompue → supprimée → échec).
     */
    private fun verifySha256(release: ReleaseInfo, socksPort: Int, apk: File) {
        val sumsUrl = release.sumsUrl ?: return
        val content = try {
            get(sumsUrl, socksPort).use { resp ->
                check(resp.code == 200) { "SHA256SUMS HTTP ${resp.code}" }
                resp.body!!.string()
            }
        } catch (e: Exception) {
            android.util.Log.i(
                "nyx-update", "SHA256SUMS injoignable — vérification ignorée (${e.message})"
            )
            return
        }
        val expected = parseSha256Sums(content, apk.name) ?: run {
            android.util.Log.i(
                "nyx-update", "SHA256SUMS sans entrée pour ${apk.name} — vérification ignorée"
            )
            return
        }
        val actual = sha256(apk)
        if (!expected.equals(actual, ignoreCase = true)) {
            apk.delete()
            throw IOException(
                "SHA-256 invalide — attendu ${expected.take(12)}…, obtenu ${actual.take(12)}…"
            )
        }
        android.util.Log.i("nyx-update", "SHA-256 vérifié (${actual.take(12)}…) pour ${apk.name}")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // =====================================================================
    // BOUCLE AUTOMATIQUE
    // =====================================================================

    /**
     * Boucle automatique : vérification à l'appel puis périodiquement — 6 h
     * (canal standard) / 30 min (canal test) — téléchargement immédiat dès
     * qu'une version pertinente est disponible. En cas d'échec réseau, la
     * nouvelle tentative arrive après 2 min (les coupures Tor sont souvent
     * brèves ; ne pas attendre un cycle complet pour proposer la mise à jour).
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
            val waitMs = when (_state.value) {
                is State.Failed -> RETRY_AFTER_FAILURE_MS
                else -> if (testChannel) CHECK_INTERVAL_TEST_MS else CHECK_INTERVAL_MS
            }
            kotlinx.coroutines.delay(waitMs)
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

    /** v0.2.42 — échec réseau : retente vite (2 min) sans marteler GitHub. */
    private const val RETRY_AFTER_FAILURE_MS = 2L * 60 * 1000
}
