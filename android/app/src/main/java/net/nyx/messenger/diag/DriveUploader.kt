package net.nyx.messenger.diag

import net.nyx.messenger.BuildConfig
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Dépôt du fichier de diagnostic dans le dossier de travail Drive (build test
 * uniquement) — Google Drive API v3, flow refresh-token OAuth.
 *
 * DÉVIATION VOLONTAIRE (ADR-007) : contrairement au trafic Nyx, CET appel ne
 * passe PAS par le Tor embarqué — il va en direct vers oauth2.googleapis.com /
 * www.googleapis.com. Justification : les sorties Tor sont massivement
 * bloquées/flaggées par Google (CAPTCHA, 403) ; c'est une fonction de débogage
 * du build test, le contenu est un journal système sans donnée utilisateur.
 * Le trafic Nyx (chiffré) reste 100 % Tor, inchangé.
 *
 * Credentials injectés à la compilation via BuildConfig (alimentés par le
 * fichier git-ignoré android/test-upload.properties — dossier « Tokens », §6).
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

    fun configFromBuild(): Config = Config(
        clientId = BuildConfig.DRIVE_CLIENT_ID,
        clientSecret = BuildConfig.DRIVE_CLIENT_SECRET,
        refreshToken = BuildConfig.DRIVE_REFRESH_TOKEN,
        folderId = BuildConfig.DRIVE_FOLDER_ID,
    )

    /** Dépose [content] sous [fileName] dans le dossier configuré. Bloquant (IO). */
    fun upload(fileName: String, content: ByteArray, cfg: Config): Result {
        if (!cfg.isConfigured) {
            return Result(false, fileName,
                error = "build non configuré (test-upload.properties absent du poste de build)")
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

    private fun refreshAccessToken(cfg: Config): String {
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

    private fun multipartUpload(fileName: String, content: ByteArray, folderId: String, token: String): String? {
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

    // ---------- utilitaires ----------

    private fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Extraction minimaliste d'un champ JSON de premier niveau (réponses contrôlées). */
    private fun extractField(json: String, field: String): String? {
        val rx = Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return rx.find(json)?.groupValues?.get(1)
    }

    /** Échappement JSON strict pour une chaîne insérée dans un corps de requête. */
    private fun jsonEsc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private fun truncate(s: String, max: Int = 400) =
        if (s.length <= max) s else s.take(max) + "…"
}
