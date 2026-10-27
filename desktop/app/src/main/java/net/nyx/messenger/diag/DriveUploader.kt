package net.nyx.messenger.diag

import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v0.3.3 (session 73 — demande opérateur : « je veux une version test avec le
 * bouton d'envoi de logs comme pour la version android ») — PORTAGE DESKTOP
 * de l'Android `diag/DriveUploader.kt` (v0.2.2) : dépôt du fichier de
 * diagnostic dans le dossier de travail Drive (bouton rouge du build test),
 * Google Drive API v3, flow refresh-token OAuth. ZÉRO dépendance ajoutée
 * (HttpURLConnection stdlib — même économie que l'Android).
 *
 * DÉVIATION VOLONTAIRE (ADR-007, inchangée depuis l'Android) : contrairement
 * au trafic Nyx, CET appel ne passe PAS par le Tor embarqué — il va en direct
 * vers oauth2.googleapis.com / www.googleapis.com. Justification : les sorties
 * Tor sont massivement bloquées/flaggées par Google (CAPTCHA, 403) ; c'est une
 * fonction de débogage du build test, le contenu est un journal système sans
 * donnée utilisateur. Le trafic Nyx (chiffré) reste 100 % Tor, inchangé.
 *
 * Credentials : l'Android les injecte via BuildConfig alimenté par le fichier
 * git-ignoré `android/test-upload.properties`. Le desktop n'a pas de
 * BuildConfig — la ressource `/drive-upload.properties` est EMBARQUÉE DANS LE
 * JAR au build (tâche Gradle alimentée par `desktop/test-upload.properties`,
 * git-ignoré — mêmes clés que l'Android : drive.clientId / drive.clientSecret
 * / drive.refreshToken / drive.folderId). **Filet de secours terrain** : un
 * fichier `<dataDir>/drive-upload.properties` posé à côté des données permet
 * de reconfigurer SANS reconstruire (refresh token révoqué/expiré — leçon
 * Android v0.2.9).
 */
object DriveUploader {

    class Config(
        val clientId: String,
        val clientSecret: String,
        val refreshToken: String,
        val folderId: String,
    ) {
        val isConfigured: Boolean
            get() = clientId.isNotBlank() && clientSecret.isNotBlank() &&
                    refreshToken.isNotBlank() && folderId.isNotBlank()
    }

    data class Result(
        val ok: Boolean,
        val fileName: String,
        val fileId: String? = null,
        val error: String? = null,
    )

    /**
     * Configuration effective : ressource embarquée au build, SURCHARGÉE par
     * `<dataDir>/drive-upload.properties` s'il existe (reconfiguration terrain
     * sans rebuild).
     */
    fun config(): Config {
        val props = linkedMapOf(
            "drive.clientId" to "",
            "drive.clientSecret" to "",
            "drive.refreshToken" to "",
            "drive.folderId" to "",
        )
        // 1. Ressource embarquée (build test).
        runCatching {
            DriveUploader::class.java.getResourceAsStream("/drive-upload.properties")
                ?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val eq = line.indexOf('=')
                        if (eq <= 0) continue
                        val k = line.substring(0, eq).trim()
                        val v = line.substring(eq + 1).trim()
                        if (k in props) props[k] = v
                    }
                }
        }
        // 2. Override terrain (données locales) — prioritaire s'il contient des clés.
        runCatching {
            val f = File(System.getProperty("nyx.data.dir")
                ?: File(System.getProperty("user.home"), ".nyx-messenger").absolutePath,
                "drive-upload.properties")
            if (f.isFile) {
                f.readLines().forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val eq = line.indexOf('=')
                    if (eq <= 0) return@forEach
                    val k = line.substring(0, eq).trim()
                    val v = line.substring(eq + 1).trim()
                    if (k in props && v.isNotBlank()) props[k] = v
                }
            }
        }
        return Config(
            clientId = props["drive.clientId"].orEmpty(),
            clientSecret = props["drive.clientSecret"].orEmpty(),
            refreshToken = props["drive.refreshToken"].orEmpty(),
            folderId = props["drive.folderId"].orEmpty(),
        )
    }

    /** Dépose [content] sous [fileName] dans le dossier configuré. Bloquant (IO). */
    fun upload(fileName: String, content: ByteArray, cfg: Config): Result {
        if (!cfg.isConfigured) {
            return Result(false, fileName,
                error = "build non configuré (drive-upload.properties absent du jar " +
                    "et des données locales)"
            )
        }
        return try {
            val token = refreshAccessToken(cfg)
            val fileId = multipartUpload(fileName, content, cfg.folderId, token)
            Result(true, fileName, fileId = fileId)
        } catch (e: Exception) {
            Result(false, fileName, error = e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- OAuth : refresh-token -> access-token ----------

    internal fun refreshAccessToken(cfg: Config): String {
        val body = "grant_type=refresh_token" +
                "&client_id=" + urlEnc(cfg.clientId) +
                "&client_secret=" + urlEnc(cfg.clientSecret) +
                "&refresh_token=" + urlEnc(cfg.refreshToken)
        val (code, resp) = post(TOKEN_URL, body,
            "application/x-www-form-urlencoded", auth = null)
        if (code != 200) throw IOException("OAuth HTTP $code : ${truncate(resp)}")
        return extractField(resp, "access_token")
            ?: throw IOException("OAuth : access_token absent de la réponse")
    }

    // ---------- Drive : upload multipart (métadonnées + contenu) ----------

    internal fun multipartUpload(fileName: String, content: ByteArray, folderId: String, token: String): String? {
        val boundary = "nyxdiag" + java.lang.Long.toHexString(System.currentTimeMillis())
        val meta = """{"name":"${jsonEsc(fileName)}","parents":["${jsonEsc(folderId)}"]}"""
        val pre = ("--$boundary\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                meta + "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\n").toByteArray(Charsets.UTF_8)
        val post = ("\r\n--$boundary--\r\n").toByteArray(Charsets.UTF_8)

        val conn = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setFixedLengthStreamingMode(pre.size + content.size + post.size)
        }
        try {
            DataOutputStream(conn.outputStream).use { o ->
                o.write(pre); o.write(content); o.write(post); o.flush()
            }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("Drive HTTP $code : ${truncate(resp)}")
            return extractField(resp, "id")
        } finally {
            conn.disconnect()
        }
    }

    // ---------- HTTP bas niveau (stdlib, pas de dépendance ajoutée) ----------

    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val UPLOAD_URL =
        "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true"

    private fun post(url: String, body: String, contentType: String, auth: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", contentType)
            if (auth != null) setRequestProperty("Authorization", auth)
        }
        try {
            DataOutputStream(conn.outputStream).use { o ->
                o.write(body.toByteArray(Charsets.UTF_8)); o.flush()
            }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to resp
        } finally {
            conn.disconnect()
        }
    }

    // ---------- utilitaires (purs — testés JVM) ----------

    internal fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Extraction minimaliste d'un champ JSON de premier niveau (réponses contrôlées). */
    internal fun extractField(json: String, field: String): String? {
        val rx = Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return rx.find(json)?.groupValues?.get(1)
    }

    /** Échappement JSON strict pour une chaîne insérée dans un corps de requête. */
    internal fun jsonEsc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    internal fun truncate(s: String, max: Int = 400) =
        if (s.length <= max) s else s.take(max) + "…"
}
