package net.nyx.messenger.crypto

import java.util.Base64
import org.bouncycastle.crypto.digests.SHA3Digest

/**
 * Codage adresse onion v3 ↔ clé publique ed25519 — miroir exact de
 * server/internal/journal/event.go (spéc : base32(pubkey || checksum || 0x03),
 * checksum = SHA3-256(".onion checksum" || pubkey || 0x03)[:2]).
 */
object OnionCodec {

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun pubKeyToOnion(pub: ByteArray): String {
        require(pub.size == 32) { "clé publique ed25519 = 32 octets" }
        val cs = checksum(pub)
        val raw = pub + cs + byteArrayOf(0x03)
        return encodeBase32(raw) + ".onion"
    }

    fun onionToPubKey(onion: String): ByteArray {
        val clean = onion.trim().lowercase().removeSuffix(".onion")
        require(clean.length == 56) { "adresse onion invalide (56 caractères attendus)" }
        val raw = decodeBase32(clean)
        require(raw.size == 35) { "décodage onion: ${raw.size} octets ≠ 35" }
        val pub = raw.copyOfRange(0, 32)
        require(raw[34].toInt() == 0x03) { "version onion ≠ 3" }
        val cs = checksum(pub)
        require(cs[0] == raw[32] && cs[1] == raw[33]) { "checksum onion invalide" }
        return pub
    }

    private fun checksum(pub: ByteArray): ByteArray {
        // SHA3-256 (FIPS 202) via Bouncy Castle — PAS via java.security.MessageDigest :
        // Conscrypt ne fournit « SHA3-256 » qu'à partir d'Android 14 (crash
        // « SHA3-256 MessageDigest not available » sur Android ≤ 13 ; minSdk 26).
        // NB : SHA3Digest = SHA-3 standard (padding 0x06) — PAS KeccakDigest
        // (padding 0x01 historique) : Tor utilise le SHA-3 standardisé.
        val input = ".onion checksum".toByteArray(Charsets.US_ASCII) + pub + byteArrayOf(0x03)
        val h = SHA3Digest(256)
        h.update(input, 0, input.size)
        val full = ByteArray(h.digestSize)
        h.doFinal(full, 0)
        return full.copyOfRange(0, 2)
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    private fun decodeBase32(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "caractère base32 invalide: $c" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add(((buffer shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

/** Encodages partagés (base64 std, comme le serveur Go). */
object B64 {
    fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
}
