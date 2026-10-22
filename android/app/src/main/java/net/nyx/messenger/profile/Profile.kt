package net.nyx.messenger.profile

import kotlinx.serialization.Serializable

/**
 * Carte de profil — wire-format.md §7 (v1.2).
 *
 * Chaque champ est PUBLIC (diffusé dans le journal via un événement `profile`
 * en diffusion) ou PRIVÉ (ne quitte JAMAIS l'appareil — stockage local
 * uniquement ; ADR-010 : publication = choix explicite par champ, rien n'est
 * public par défaut).
 *
 * Ce fichier est volontairement sans dépendance Android : testable en JVM
 * (tests unitaires) et miroir de la validation serveur (journal.ProfileFromEvent).
 */

/** Clés de champs fermées + limites (miroir exact de ProfileKeys côté serveur Go). */
object ProfileKeys {
    const val DISPLAY_NAME = "display_name"
    const val BIO = "bio"
    const val WEBSITE = "website"
    const val LOCATION = "location"
    const val AVATAR = "avatar"
    /** v0.2.25 : adresse du réplica temporaire embarqué « <onion>:80 » —
     *  auto-déclarée à la publication (test 2), agrégée par le serveur
     *  (/admin/replicas) pour l'auto-ajout à la console admin. */
    const val REPLICA = "replica"

    /** Limite de caractères par clé (avatar : base64 — §7.2). */
    val LIMITS: Map<String, Int> = mapOf(
        DISPLAY_NAME to 64,
        BIO to 280,
        WEBSITE to 128,
        LOCATION to 64,
        AVATAR to 65536,
        REPLICA to 128,
    )

}

/** Un champ : valeur + visibilité. */
@Serializable
data class ProfileField(val value: String, val public: Boolean)

/** Corps d'un événement `profile` en diffusion (base64 du JSON). */
@Serializable
data class ProfileBody(val fields: Map<String, ProfileField> = emptyMap())

/** Carte de profil complète côté appareil (champs publics ET privés). */
@Serializable
data class OwnProfile(val fields: Map<String, ProfileField> = emptyMap()) {
    /** Sous-ensemble PUBLIC — seul ce qui part sur le réseau (invariant §7.2). */
    fun publicOnly(): OwnProfile =
        OwnProfile(fields.filterValues { it.public })

    fun valueOf(key: String): String = fields[key]?.value.orEmpty()
    fun isPublic(key: String): Boolean = fields[key]?.public ?: false
}

/**
 * Validation côté client (miroir de journal.ProfileFromEvent) — retourne null
 * si la carte est publiable, sinon le motif. Ne s'applique qu'à la partie
 * DIFFUSÉE : la couche envoi ne transmet que [OwnProfile.publicOnly].
 */
object ProfileValidator {
    fun validatePublicCard(fields: Map<String, ProfileField>): String? {
        if (fields.size > ProfileKeys.LIMITS.size) return "trop de champs"
        for ((k, f) in fields) {
            val max = ProfileKeys.LIMITS[k] ?: return "champ inconnu: $k"
            if (f.value.length > max) return "champ $k trop long (${f.value.length} > $max)"
            if (!f.public) return "champ $k privé dans une carte DIFFUSÉE (§7.2)"
        }
        return null
    }
}

/**
 * Codec du profil — base64 UTF-8 du JSON (format §7.2, aligné serveur).
 */
object ProfileCodec {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(body: ProfileBody): String =
        json.encodeToString(ProfileBody.serializer(), body)

    fun decode(raw: String): ProfileBody? = runCatching {
        json.decodeFromString(ProfileBody.serializer(), raw)
    }.getOrNull()
}
