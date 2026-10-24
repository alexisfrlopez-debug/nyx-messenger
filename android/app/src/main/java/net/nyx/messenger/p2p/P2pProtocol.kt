package net.nyx.messenger.p2p

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * v0.2.18 — PROTOCOLE DU CANAL DIRECT P2P (ADR-015 + demande opérateur
 * « pour les envois de fichiers, rien ne soit stocké sur serveur »).
 *
 * Les DEUX clients publient déjà un service onion (leur identité). Ce canal
 * ajoute un SECOND port à ce service (47471) mappé sur un écouteur local :
 *  - l'appelant/expéditeur ouvre une connexion SOCKS → tor → onion du pair ;
 *  - le destinataire reçoit la connexion sur son écouteur local.
 *
 * Tor fournit le chiffrement du transport (onion v3 = E2E jusqu'à la clé de
 * l'identité du pair) ; l'authentification applicative repose sur une
 * signature ed25519 du HELLO par la CLÉ D'IDENTITÉ de l'expéditeur — le
 * destinataire vérifie onion ↔ signature ↔ contact accepté.
 *
 * Trame : [type:1][longueur:4 BE][payload] — la longueur 4 octets couvre les
 * fragments filaires v0.2.17 (~175 Ko) et les échantillons audio (≪ 1 Ko).
 */
object P2pProtocol {

    /** Port onion dédié au canal direct (2ᵉ directive Port= de ADD_ONION). */
    const val MEDIA_ONION_PORT = 47471

    const val F_HELLO = 1        // authentification + intention (call|file|events)
    const val F_FILE = 0x10      // fragment de fichier (corps chiffré E2E, b64)
    const val F_EVENT = 0x18     // v0.2.41 — événement journal signé (JSON WireEvent)
    const val F_AUDIO = 0x14     // trame audio (AAC access unit brute)
    const val F_VIDEO = 0x16     // v0.2.20 — trame vidéo (access unit H.264 brute)
    const val F_CALL_ACCEPT = 0x12  // payload = csd de l'appelé (voir AcceptPayload)
    const val F_CALL_REJECT = 0x13
    const val F_BYE = 0x15       // raccroché
    const val F_ACK = 0x7F       // payload = [reçus:4 BE]

    data class Frame(val type: Int, val payload: ByteArray)

    fun write(out: OutputStream, type: Int, payload: ByteArray = ByteArray(0)) {
        val d = DataOutputStream(out)
        d.writeByte(type)
        d.writeInt(payload.size)
        d.write(payload)
        d.flush()
    }

    /** Lit une trame — null = flux fermé proprement (EOF avant en-tête). */
    fun read(input: InputStream): Frame? {
        val d = DataInputStream(input)
        val type = try { d.readUnsignedByte() } catch (_: EOFException) { return null }
        val len = d.readInt()
        require(len >= 0 && len <= 4 * 1024 * 1024) { "trame P2P aberrante ($len octets)" }
        val payload = ByteArray(len)
        d.readFully(payload)
        return Frame(type, payload)
    }

    /**
     * v0.2.48 — LECTURE DU PREMIER HELLO avec distinction de la connexion
     * MUETTE (terrain 24/10 : l'auto-sonde média et les sondes console testent
     * la joignabilité TCP du port 47471 SANS envoyer de trame → « connexion
     * sans HELLO valide — fermée » ×toutes les 20-40 s dans chaque export,
     * noyant les vraies anomalies). [HelloFirst.mute] = EOF avant le premier
     * octet (rien reçu du tout) ; [HelloFirst.bad] = octets reçus mais trame
     * cassée ; [HelloFirst.hello] = Hello parsé (null = trame valide mais pas
     * un HELLO). Le format lu est le même que [read] (type u8 + len i32 +
     * payload) — figé depuis la v0.2.18 et verrouillé par P2pProtocolTest.
     */
    data class HelloFirst(val mute: Boolean, val bad: Boolean, val hello: Hello?)

    fun readHello(input: InputStream): HelloFirst {
        val d = DataInputStream(input)
        val type = try { d.read() } catch (_: Exception) { return HelloFirst(false, true, null) }
        if (type == -1) return HelloFirst(true, false, null)   // EOF : rien envoyé
        if (type != F_HELLO) return HelloFirst(false, false, null)
        return try {
            val len = d.readInt()
            if (len < 0 || len > 4 * 1024 * 1024) return HelloFirst(false, true, null)
            val payload = ByteArray(len)
            d.readFully(payload)
            HelloFirst(false, false, parseHello(payload))
        } catch (_: Exception) {
            HelloFirst(false, true, null)
        }
    }

    // ---------- HELLO ----------

    /**
     * Corps du HELLO (JSON) — `sig` couvre la chaîne
     * « nyx-p2p|<t>|<onion>|<sel> » (sel = cid pour un appel, count pour un
     * fichier) ; vérification : onionToPubKey(onion) + contact accepté.
     */
    data class Hello(
        val kind: String,      // "call" | "file" | "events" (v0.2.41)
        val onion: String,     // onion de l'EXPÉDITEUR (authentification)
        val alias: String,     // alias déclaré (cosmétique)
        val cid: String,       // appel : identifiant de session
        val csd: String,       // appel : csd-0 AAC de l'appelant (b64)
        val count: Int,        // fichier/events : nombre d'unités annoncées
        val sig: String,       // signature ed25519 (b64) de [helloSignedString]
        val vcsd: String = "", // v0.2.20 : csd H.264 (SPS+PPS b64) si visio, "" sinon
    )

    fun encodeHello(h: Hello, sigB64: String): ByteArray {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val sb = StringBuilder("{\"v\":1,\"t\":\"").append(esc(h.kind)).append('"')
            .append(",\"onion\":\"").append(esc(h.onion)).append('"')
            .append(",\"alias\":\"").append(esc(h.alias)).append('"')
            .append(",\"cid\":\"").append(esc(h.cid)).append('"')
            .append(",\"csd\":\"").append(esc(h.csd)).append('"')
            .append(",\"count\":").append(h.count)
        if (h.vcsd.isNotEmpty()) {
            sb.append(",\"vcsd\":\"").append(esc(h.vcsd)).append('"')
        }
        sb.append(",\"sig\":\"").append(esc(sigB64)).append("\"}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun parseHello(payload: ByteArray): Hello? {
        val s = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return null
        val kind = MiniJson.str(s, "t")
        if (kind != "call" && kind != "file" && kind != "events") return null
        val onion = MiniJson.str(s, "onion")?.lowercase()
        if (onion == null || !onion.endsWith(".onion") || onion.length < 50) return null
        return Hello(
            kind, onion, MiniJson.str(s, "alias") ?: "", MiniJson.str(s, "cid") ?: "",
            MiniJson.str(s, "csd") ?: "", MiniJson.num(s, "count")?.toInt() ?: 0,
            MiniJson.str(s, "sig") ?: "",
            MiniJson.str(s, "vcsd") ?: ""
        )
    }

    /**
     * v0.2.20 — PAYLOAD du F_CALL_ACCEPT : JSON {"csd":…,"vcsd":…} quand de
     * la vidéo est négociée, sinon le b64 AAC brut de v0.2.18/19 (compat : un
     * pair v0.2.19 qui n'a PAS annoncé de vcsd reçoit l'ancien format).
     */
    object AcceptPayload {
        fun encode(audioCsdB64: String, videoCsdB64: String): String {
            if (videoCsdB64.isEmpty()) return audioCsdB64 // format historique
            fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
            return "{\"csd\":\"${esc(audioCsdB64)}\",\"vcsd\":\"${esc(videoCsdB64)}\"}"
        }

        /** (csd audio, csd vidéo ou "") — tolère l'ancien format brut. */
        fun parse(payload: ByteArray): Pair<String, String> {
            val s = runCatching { String(payload, Charsets.UTF_8) }.getOrDefault("")
            if (s.startsWith("{")) {
                val audio = MiniJson.str(s, "csd") ?: ""
                val video = MiniJson.str(s, "vcsd") ?: ""
                if (audio.isNotEmpty()) return audio to video
            }
            return s to ""
        }
    }

    /** Chaîne signée du HELLO — miroir exact chez le vérificateur. */
    fun helloSignedString(kind: String, onion: String, selector: String): String =
        "nyx-p2p|$kind|$onion|$selector"
}
