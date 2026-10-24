package net.nyx.messenger.net

import net.nyx.messenger.p2p.MiniJson
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * v0.2.46 — TRAMES DU LIEN INVERSÉ NYX-LINK (ADR-018, spec protocol/specs/link.md).
 *
 * Miroir EXACT de server/internal/api/link.go — les canons de signature sont
 * signés octet par octet à l'identique côté téléphone (LinkChannel) et
 * vérifiés par le routeur (dérivation journal.OnionToPubKey). Objet PUR
 * (aucune dépendance Android) → testable JVM.
 *
 * Le lien inverse le sens de connexion : le téléphone ne reçoit PLUS par son
 * onion entrant (chroniquement injoignable — terrain sessions 41 → 67 : même
 * publié, descripteur non propagé / points d'introduction morts / cache
 * client périmé 24 h), il TIRE ses requêtes par un long-poll permanent vers
 * un réplica de flotte (chemin sortant prouvé à 100 % sur le terrain) et y
 * redépose ses réponses signées.
 */
object LinkWire {

    const val PULL_PREFIX = "NYX-LINK-PULL"
    const val REPLY_PREFIX = "NYX-LINK-REPLY"

    /** Une requête livrée au téléphone par /link/pull. */
    data class LinkReq(
        val id: String,
        val method: String,
        val path: String,
        val bodyB64: String,
    )

    /** La réponse d'un /link/wait (le code HTTP du réplica local + son corps). */
    data class LinkResp(val status: Int, val bodyB64: String)

    /** id hex 16-64 (32 car. ici) — le secret d'appariement du voyage. */
    fun newRequestId(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    // ---------- canons de signature (octets identiques côté Go) ----------

    fun pullCanonical(me: String, ts: Long): String = "$PULL_PREFIX\n$me\n$ts"

    fun replyCanonical(from: String, ts: Long, id: String, status: Int, sha256Hex: String): String =
        "$REPLY_PREFIX\n$from\n$ts\n$id\n$status\n$sha256Hex"

    /** sha256 HEXA minuscule du TEXTE (base64 tel qu'il transite — pas des octets décodés). */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---------- découpe du path (miroir ReplicaHttp.parseQuery) ----------

    /** « /client/inbox?to=x&after=3 » → (« /client/inbox », {to=x, after=3}). */
    fun splitPath(pathWithQuery: String): Pair<String, Map<String, String>> {
        val qm = pathWithQuery.indexOf('?')
        if (qm < 0) return pathWithQuery to emptyMap()
        val path = pathWithQuery.substring(0, qm)
        val query = HashMap<String, String>()
        for (pair in pathWithQuery.substring(qm + 1).split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                query[urlDecode(pair)] = ""
            } else {
                query[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
            }
        }
        return path to query
    }

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    // ---------- corps JSON ----------

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun buildSendBody(to: String, id: String, method: String, path: String, bodyB64: String): String =
        "{\"to\":\"${esc(to)}\",\"id\":\"$id\",\"method\":\"${esc(method)}\"," +
            "\"path\":\"${esc(path)}\",\"body_b64\":\"$bodyB64\"}"

    fun buildReplyBody(id: String, from: String, status: Int, bodyB64: String, auth: String): String =
        "{\"id\":\"$id\",\"from\":\"${esc(from)}\",\"status\":$status," +
            "\"body_b64\":\"$bodyB64\",\"auth\":\"${esc(auth)}\"}"

    // ---------- lecture des réponses du routeur ----------

    /** Réponse /link/pull : la liste des requêtes (vide = keepalive 45 s). */
    fun parsePull(json: String): List<LinkReq> {
        val out = ArrayList<LinkReq>()
        var i = json.indexOf("\"reqs\"")
        if (i < 0) return out
        i = json.indexOf('[', i)
        if (i < 0) return out
        var depth = 0
        var start = -1
        var j = i
        while (j < json.length) {
            when (json[j]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
                '{' -> if (depth == 1 && start < 0) start = j
                '}' -> if (depth == 1 && start >= 0) {
                    val obj = json.substring(start, j + 1)
                    val id = MiniJson.str(obj, "id")
                    val method = MiniJson.str(obj, "method")
                    val path = MiniJson.str(obj, "path")
                    val body = MiniJson.str(obj, "body_b64") ?: ""
                    if (id != null && method != null && path != null) {
                        out.add(LinkReq(id, method, path, body))
                    }
                    start = -1
                }
            }
            j++
        }
        return out
    }

    /** Réponse /link/wait : null si aucune réponse exploitable (408/erreur). */
    fun parseWait(json: String): LinkResp? {
        val status = MiniJson.num(json, "status")?.toInt() ?: return null
        return LinkResp(status, MiniJson.str(json, "body_b64") ?: "")
    }

    /** Champ « error » éventuel (diagnostic). */
    fun parseError(json: String): String? = MiniJson.str(json, "error")
}
