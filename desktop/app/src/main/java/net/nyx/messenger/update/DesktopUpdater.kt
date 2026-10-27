package net.nyx.messenger.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.DesktopPaths
import net.nyx.messenger.desktop.ServiceLocator
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Portage desktop de update/Updater.kt (Android v0.2.20/40/42) — v0.3.4
 * « parité Android » : mise à jour AUTOMATIQUE.
 *
 *  - contrôle GitHub Releases (même dépôt que l'Android — la famille desktop
 *    vit en tags `vX.Y.Z-desktop`), via le SOCKS de Tor (anonymat préservé) ;
 *  - canal STABLE = dernière release non-préversion ; canal TEST =
 *    préversions admises (miroir TEST_BUILD Android) ;
 *  - comparaison de versions component-wise (miroir isNewer) ;
 *  - téléchargement de l'INSTALLATEUR `nyx-messenger-windows-vX.Y.Z-installer.exe`
 *    vers `<dataDir>/update/`, puis « Installer la mise à jour » lance
 *    l'installateur (le payload vérifie son propre SHA-256 — la garantie
 *    d'intégrité de bout en bout reste celle de l'installateur, miroir du
 *    contrôle SHA256SUMS Android) ;
 *  - cadence : au démarrage (dès Tor prêt) puis toutes les 6 h (30 min en
 *    canal test) — miroir Android.
 */
object DesktopUpdater {

    private const val RELEASES_URL =
        "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases?per_page=30"

    /** Réponse d'erreur → nouvelle tentative rapide (miroir retry 2 min Android). */
    private const val RETRY_MS = 2 * 60_000L

    data class ReleaseInfo(
        val tag: String,
        val version: String,           // "0.3.4"
        val installerUrl: String,
        val installerSize: Long,
        val prerelease: Boolean,
    )

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val tag: String) : State()
        data class Available(val release: ReleaseInfo) : State()
        data class Downloading(val version: String, val bytes: Long, val total: Long) : State()
        data class Ready(val version: String, val installer: File) : State()
        data class Failed(val reason: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    private var scope: CoroutineScope? = null
    @Volatile private var running = false

    /** Démarré quand Tor est prêt (miroir Android : update loop après bootstrap). */
    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        this.scope = scope
        scope.launch(Dispatchers.IO) {
            while (true) {
                // Session leurre : aucun contrôle réseau (miroir Android).
                if (ServiceLocator.sessionMode == ServiceLocator.SessionMode.PANIC) {
                    delay(60_000L)
                    continue
                }
                val ok = runCatching { checkNow() }.getOrDefault(false)
                delay(if (ok) intervalMs() else RETRY_MS)
            }
        }
    }

    private fun intervalMs(): Long =
        if (ServiceLocator.settings.get().updateTestChannel) 30 * 60_000L else 6 * 3_600_000L

    /** Contrôle manuel (« Vérifier maintenant » — Réglages). */
    suspend fun checkNow(): Boolean = withContext(Dispatchers.IO) {
        state = State.Checking
        val socks = socksPort()
        if (socks <= 0) {
            state = State.Failed("Tor n'est pas prêt")
            return@withContext false
        }
        val release = latestRelease(socks) ?: run {
            state = State.Failed("contrôle impossible (réseau/GitHub)")
            return@withContext false
        }
        val current = ServiceLocator.VERSION
        if (!isNewer(release.version, current)) {
            state = State.UpToDate(release.tag)
            return@withContext true
        }
        state = State.Available(release)
        DesktopLog.i("nyx-update", "mise à jour disponible : ${release.tag} (courant $current)")
        // Téléchargement immédiat (miroir Android).
        download(release, socks)
        true
    }

    private fun download(release: ReleaseInfo, socks: Int) {
        try {
            val dir = File(DesktopPaths.dataDir, "update").apply { mkdirs() }
            val out = File(dir, "nyx-windows-v${release.version}-installer.exe")
            val client = buildClient(socks, readTimeoutSec = 600)
            client.newCall(
                Request.Builder().url(release.installerUrl).header("User-Agent", "Nyx-Messenger").build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("corps vide")
                val total = body.contentLength()
                var written = 0L
                body.byteStream().use { input ->
                    java.io.FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            written += n
                            state = State.Downloading(release.version, written, total)
                        }
                    }
                }
                if (written < 1_000_000) {
                    out.delete()
                    state = State.Failed("téléchargement incomplet (${written} octets)")
                    return
                }
            }
            state = State.Ready(release.version, out)
            DesktopLog.i("nyx-update", "installateur ${release.tag} téléchargé (${out.length() / 1_048_576} Mo)")
            // v0.3.4 — notification plateau miroir Android (update_notification_title :
            // « Mise à jour Nyx prête — appuyez pour l'installer » ; ici l'installation
            // se fait depuis le bandeau/Réglages, le ballon l'annonce).
            net.nyx.messenger.notify.DesktopNotifier.updateReady(release.version)
        } catch (e: Exception) {
            state = State.Failed("téléchargement échoué : ${e.message?.take(80)}")
            DesktopLog.w("nyx-update", "téléchargement échoué : ${e.message?.take(80)}")
        }
    }

    /** « Installer la mise à jour » : lance l'installateur téléchargé. */
    fun install(): Boolean {
        val s = state
        if (s !is State.Ready) return false
        return runCatching {
            ProcessBuilder(listOf(s.installer.absolutePath)).start()
            DesktopLog.i("nyx-update", "installateur lancé (${s.installer.name})")
            true
        }.getOrElse {
            DesktopLog.w("nyx-update", "lancement de l'installateur impossible : ${it.message?.take(80)}")
            false
        }
    }

    // ---------------- pure / testable ----------------

    /** Comparaison component-wise (miroir isNewer Android : « v » retiré, préversion ignorée). */
    fun isNewer(candidate: String, current: String): Boolean {
        fun norm(v: String): List<Int> = v.trim()
            .removePrefix("v").substringBefore('-').split('.')
            .map { it.toIntOrNull() ?: 0 }
        val a = norm(candidate)
        val b = norm(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: 0
            val y = b.getOrNull(i) ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /** Extrait la release desktop applicable (miroir sélecteur de canal Android). */
    fun pickRelease(json: String, allowPrerelease: Boolean): ReleaseInfo? {
        val arr = org.json.JSONArray(json)
        var best: ReleaseInfo? = null
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            if (rel.optBoolean("draft")) continue
            val tag = rel.optString("tag_name") ?: continue
            if (!Regex("^v\\d+\\.\\d+\\.\\d+-desktop$").matches(tag)) continue
            if (rel.optBoolean("prerelease") && !allowPrerelease) continue
            val version = tag.removePrefix("v").removeSuffix("-desktop")
            var installerUrl = ""
            var installerSize = 0L
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").endsWith("-installer.exe")) {
                    installerUrl = a.optString("browser_download_url")
                    installerSize = a.optLong("size")
                    break
                }
            }
            if (installerUrl.isBlank()) continue
            val info = ReleaseInfo(tag, version, installerUrl, installerSize, rel.optBoolean("prerelease"))
            if (best == null || isNewer(info.version, best.version)) best = info
        }
        return best
    }

    // ---------------- réseau ----------------

    private fun socksPort(): Int =
        if (ServiceLocator.tor.bootstrapped) ServiceLocator.tor.socksPort else 0

    private fun buildClient(socks: Int, readTimeoutSec: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks)))
            .build()

    private fun latestRelease(socks: Int): ReleaseInfo? = runCatching {
        val client = buildClient(socks, 60)
        client.newCall(
            Request.Builder().url(RELEASES_URL).header("User-Agent", "Nyx-Messenger").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val text = resp.body?.string() ?: return@runCatching null
            pickRelease(text, ServiceLocator.settings.get().updateTestChannel)
        }
    }.getOrNull()
}
