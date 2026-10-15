package net.nyx.messenger.crypto

import org.json.JSONObject

/**
 * Fichier de sauvegarde d'identité (v0.2.3) — format stable et lisible :
 *
 *     {
 *       "format": "nyx-identity-backup",
 *       "version": 1,
 *       "onion": "<adresse>…onion",
 *       "recovery_key": "nyx1.<base64>.<checksum>",
 *       "created_at": 1760000000
 *     }
 *
 * La restauration accepte soit ce JSON complet, soit la clé nue « nyx1.… »
 * (copiée-collée depuis un papier). La clé EST le secret : ce fichier ne doit
 * jamais transiter par un canal non maîtrisé.
 */
object IdentityBackup {

    private const val FORMAT = "nyx-identity-backup"

    fun toJson(onion: String, recoveryKey: String): String =
        JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("onion", onion)
            .put("recovery_key", recoveryKey)
            .put("created_at", System.currentTimeMillis() / 1000)
            .toString(2)

    /**
     * Extrait la clé de récupération d'un texte (JSON complet ou clé nue).
     * Retourne null avec raison si le contenu est méconnaissable.
     */
    fun extractKey(text: String): Pair<String, String?>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) {
            return runCatching {
                val o = JSONObject(t)
                val key = o.optString("recovery_key")
                if (!RecoveryKey.looksLikeKey(key)) null
                else key to o.optString("onion").takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
        return if (RecoveryKey.looksLikeKey(t)) t to null else null
    }
}
