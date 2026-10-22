package net.nyx.messenger.crypto

import java.security.MessageDigest
import java.util.Base64

/**
 * Clé de récupération d'identité (v0.2.3 — « sauvegarde » ADR-003).
 *
 * La graine ed25519 (32 octets) est le COMPTE : qui la possède recrée
 * exactement la même identité (même adresse onion, même clé de signature).
 * Encodage portable et typable :
 *
 *     nyx1.<base64(32 octets)>.<8 hex = SHA-256(graine)[0..4]>
 *
 * Le suffixe est un checksum : une faute de frappe est rejetée AVANT
 * l'écrasement de l'identité locale (aucun risque de restaurer un mauvais
 * compte par erreur). Aucune donnée n'est chiffrée ici — la clé EST le secret,
 * à traiter comme un mot de passe maître (jamais journalisée, jamais transmise).
 */
object RecoveryKey {

    private const val PREFIX = "nyx1"

    /** Encode une graine (32 octets) en clé de récupération. */
    fun encode(seed: ByteArray): String {
        require(seed.size == 32) { "graine ed25519 : ${seed.size} octets ≠ 32" }
        val b64 = Base64.getEncoder().encodeToString(seed)
        return "$PREFIX.$b64.${checksum(seed)}"
    }

    /**
     * Décode une clé de récupération → graine. Lève une exception explicite
     * sur format invalide ou checksum erroné (faute de frappe).
     */
    fun decode(key: String): ByteArray {
        val parts = key.trim().replace("\n", "").split('.')
        require(parts.size == 3 && parts[0] == PREFIX) {
            "format attendu : $PREFIX.<clé base64>.<checksum>"
        }
        val seed = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrElse {
            throw IllegalArgumentException("clé base64 invalide")
        }
        require(seed.size == 32) { "clé décodée : ${seed.size} octets ≠ 32" }
        require(checksum(seed) == parts[2].lowercase()) {
            "checksum invalide — vérifiez la saisie de la clé"
        }
        return seed
    }

    /** True si la chaîne RESSEMBLE à une clé de récupération (aide à l'UI). */
    fun looksLikeKey(s: String): Boolean = s.trim().startsWith("$PREFIX.")

    private fun checksum(seed: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(seed)
            .take(4)
            .joinToString("") { "%02x".format(it) }
}
