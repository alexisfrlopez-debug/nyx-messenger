package net.nyx.messenger.crypto

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fichier de sauvegarde d'identité — format stable et lisible :
 *
 *     {
 *       "format": "nyx-identity-backup",
 *       "version": 1,
 *       "onion": "<adresse>…onion",
 *       "recovery_key": "nyx1.<base64>.<checksum>",
 *       "created_at": 1760000000
 *     }
 *
 * v0.2.21 — VERSION 2 (retour terrain : « l'application me redemande de créer
 * un alias et je reçois l'invitation du contact que j'ai déjà accepté ») :
 * la restauration d'un compte ne doit pas repartir d'une base vide. Le format v2
 * embarque donc EN PLUS de la clé :
 *   - "alias"    : pseudonyme unique (pré-remplit la section alias, ré-claim
 *                  automatique via ensureAliasClaimed — idempotent) ;
 *   - "profile"  : carte de profil PROPRE (champs publics ET privés — les
 *                  privés n'existent que localement, la sauvegarde est le seul
 *                  endroit qui les préserve) ;
 *   - "contacts" : liste des contacts {onion, alias, status, addedAt,
 *                  notifiedAt, notifyPending, ackPending} — la re-fusion évite
 *                  de recréer une invitation « incoming » pour un contact déjà
 *                  accepté quand la boîte réplica re-livre ses anciens événements ;
 *   - "cursors"  : curseurs inbox par réplica — le poll reprend où il s'était
 *                  arrêté au moment de l'export (pas de re-lecture d'anciens
 *                  événements, pas de re-notification).
 *
 * La restauration accepte : ce JSON v2, le JSON v1 (identité seule) ou la clé
 * nue « nyx1.… » (copiée-collée depuis un papier). La clé EST le secret : ce
 * fichier ne doit jamais transiter par un canal non maîtrisé — il contient
 * désormais aussi des données personnelles (contacts, profil privé).
 */
object IdentityBackup {

    private const val FORMAT = "nyx-identity-backup"

    /** Un contact tel qu'embarqué dans la sauvegarde v2 (données minimales). */
    data class BackupContact(
        val onion: String,
        val alias: String,
        val status: String,
        val addedAt: Long,
        val notifiedAt: Long,
        val notifyPending: Int,
        val ackPending: Int,
    )

    /** Contenu reconnu d'un texte de sauvegarde (v1 ou v2, ou clé nue). */
    data class RestoreData(
        val recoveryKey: String,
        val onion: String? = null,
        val alias: String? = null,
        val profileJson: String? = null,   // carte propre — JSON ProfileBody
        val contacts: List<BackupContact> = emptyList(),
        val cursors: Map<String, Long> = emptyMap(),
    )

    /** v1 (compatibilité) : JSON réduit à la clé. */
    fun toJson(onion: String, recoveryKey: String): String =
        toJson(onion, recoveryKey, null, null, "[]", "{}")

    /**
     * Sauvegarde v2 complète. [profileJson] = encode(ProfileBody) de la carte
     * PROPRE ; [contactsJson] / [cursorsJson] = JSONArray/JSONObject déjà
     * sérialisés par l'appelant ([net.nyx.messenger.repo.NyxRepository.exportBackupJson]).
     */
    fun toJson(
        onion: String,
        recoveryKey: String,
        alias: String?,
        profileJson: String?,
        contactsJson: String,
        cursorsJson: String,
    ): String {
        val o = JSONObject()
            .put("format", FORMAT)
            .put("version", 2)
            .put("onion", onion)
            .put("recovery_key", recoveryKey)
            .put("created_at", System.currentTimeMillis() / 1000)
        if (!alias.isNullOrBlank()) o.put("alias", alias)
        if (!profileJson.isNullOrBlank()) o.put("profile", JSONObject(profileJson))
        o.put("contacts", JSONArray(contactsJson))
        o.put("cursors", JSONObject(cursorsJson))
        return o.toString(2)
    }

    /**
     * Extrait les données restaurables d'un texte (JSON v2/v1 complet ou clé
     * nue). Retourne null avec raison loggée si le contenu est méconnaissable.
     */
    fun extract(text: String): RestoreData? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) {
            return runCatching {
                val o = JSONObject(t)
                val key = o.optString("recovery_key")
                if (!RecoveryKey.looksLikeKey(key)) return@runCatching null

                val contacts = mutableListOf<BackupContact>()
                val arr = o.optJSONArray("contacts")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val onion = c.optString("onion").trim().lowercase()
                        if (!onion.endsWith(".onion")) continue
                        contacts.add(
                            BackupContact(
                                onion = onion,
                                alias = c.optString("alias").ifBlank { onion.take(10) + "…" },
                                status = c.optString("status"),
                                addedAt = c.optLong("addedAt", 0L),
                                notifiedAt = c.optLong("notifiedAt", 0L),
                                notifyPending = c.optInt("notifyPending", 0),
                                ackPending = c.optInt("ackPending", 0),
                            )
                        )
                    }
                }
                val cursors = mutableMapOf<String, Long>()
                val co = o.optJSONObject("cursors")
                if (co != null) {
                    for (k in co.keys()) {
                        val v = co.optLong(k, 0L)
                        if (v > 0) cursors[k.trim().lowercase()] = v
                    }
                }
                RestoreData(
                    recoveryKey = key,
                    onion = o.optString("onion").takeIf { it.isNotBlank() },
                    alias = o.optString("alias").takeIf { it.isNotBlank() },
                    profileJson = o.optJSONObject("profile")?.toString(),
                    contacts = contacts,
                    cursors = cursors,
                )
            }.getOrNull()
        }
        return if (RecoveryKey.looksLikeKey(t)) RestoreData(recoveryKey = t) else null
    }
}
