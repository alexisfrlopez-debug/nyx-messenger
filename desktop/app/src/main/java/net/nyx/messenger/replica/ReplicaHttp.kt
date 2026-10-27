package net.nyx.messenger.replica

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder

/**
 * v0.2.19 — HTTP MINIMAL du réplica temporaire embarqué : parsing et sérialisation
 * des requêtes/réponses des 5 routes que le client sait parler (RelayClient :
 * /health, /client/event, /client/inbox, /client/alias, /client/profile — c'est
 * TOUT ce que le réplica Go expose aux clients, gossip exclu). Objet PUR
 * (aucune classe Android) → testable en JVM ([ReplicaHttpTest]).
 *
 * Volontairement sans dépendance HTTP (même philosophie zéro-dépendance que
 * P2pProtocol / TorControlClient) : les requêtes attendues sont bornées
 * (corps ≤ 256 Ko), HTTP/1.1 avec Connection: close — une requête, une réponse.
 */
object ReplicaHttp {

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    class Bad(val reason: String) : Exception(reason)

    /**
     * Parse UNE requête HTTP/1.1 depuis [input].
     * Retourne null si la connexion s'est fermée avant requête (sonde vide),
     * lève [Bad] sur requête malformée ou trop grosse (→ 400/413).
     */
    fun parse(input: InputStream, maxBody: Int = ReplicaValidate.MAX_BODY_SIZE): Request? {
        // 1. Ligne de requête
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(" ")
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) {
            throw Bad("ligne de requête invalide")
        }
        val method = parts[0].uppercase()
        val target = parts[1]
        val path: String
        val query: Map<String, String>
        val qm = target.indexOf('?')
        if (qm >= 0) {
            path = target.substring(0, qm)
            query = parseQuery(target.substring(qm + 1))
        } else {
            path = target
            query = emptyMap()
        }

        // 2. En-têtes
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw Bad("en-têtes tronqués")
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c <= 0) throw Bad("en-tête invalide")
            headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
        }

        // 3. Corps (Content-Length borné)
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0) throw Bad("content-length négatif")
        if (len > maxBody) throw Bad("corps trop volumineux ($len > $maxBody)")
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) throw Bad("corps tronqué")
            off += n
        }
        return Request(method, path, query, headers, body)
    }

    /** Réponse HTTP/1.1 complète (Content-Type JSON, Connection: close). */
    fun response(code: Int, json: String): ByteArray {
        val head = "HTTP/1.1 $code ${reasonOf(code)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${json.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + json.toByteArray(Charsets.UTF_8)
    }

    /** Réponse 400 avec message d'erreur JSON (miroir writeJSON côté Go). */
    fun errorJson(message: String): String {
        // Échappement minimal : le message est généré par nous (pas de quote sauf bascule).
        val safe = message.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"error\":\"$safe\"}"
    }

    private fun reasonOf(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> "Status $code"
    }

    /** Lit une ligne terminée par \n (accepte \r\n) — null si flux fermé d'abord. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var prevCr = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) {
                val s = out.toString("ISO-8859-1")
                return if (prevCr) s.dropLast(1) else s
            }
            prevCr = b == '\r'.code
            out.write(b)
            if (out.size() > 16 * 1024) throw Bad("ligne trop longue")
        }
    }

    /** Parse `a=1&b=2` avec décodage URL (miroir r.URL.Query() côté Go). */
    fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k: String
            val v: String
            if (eq >= 0) { k = pair.substring(0, eq); v = pair.substring(eq + 1) }
            else { k = pair; v = "" }
            runCatching {
                out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            }
        }
        return out
    }
}
