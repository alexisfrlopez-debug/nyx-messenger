package net.nyx.messenger.desktop

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Réglages persistés desktop (miroir SettingsManager Android — étendu v0.3.4
 * « parité Android » : thème 3 états, comportement de fermeture, TTL éphémère
 * PAR conversation (« eph_ttl_<peer> » miroir Android), liste de réplicas
 * personnalisés, préférences de notification).
 */
class DesktopSettings(private val file: File) {

    @Serializable
    data class Data(
        val theme: String = "nuit",                 // "système" | "nuit" | "aube"
        val embeddedReplicaEnabled: Boolean = true, // interrupteur Paramètres (miroir Android)
        val lockEnabled: Boolean = false,           // verrou par mot de passe opt-in
        // v0.3.4 — 3 comportements miroir Android (« Fermeture ») : demain
        // « demander » (dialogue) / « reduire » (Tor actif, plateau système) /
        // « quitter » (arrêt propre). Remplace le booléen closeMinimizes
        // (les anciens fichiers restent lisibles — ignoreUnknownKeys).
        val closeBehavior: String = "demander",    // "demander" | "reduire" | "quitter"
        val customReplica: String = "",             // réplica saisi manuellement (Connexions)
        // v0.3.4 — LISTE de réplicas personnalisés (miroir ReplicasListCard Android).
        val customReplicas: List<String> = emptyList(),
        // v0.3.4 — TTL éphémère par conversation, secondes (clé = onion pair,
        // miroir « eph_ttl_<peer> » Android ; 0 = permanent).
        val ephTtl: Map<String, Long> = emptyMap(),
        // v0.3.4 — notifications plateau (messages/invitations) miroir Android.
        val notificationsEnabled: Boolean = true,
        // v0.3.4 — canal de mise à jour (miroir Android : test = préversions).
        val updateTestChannel: Boolean = false,
    )

    @Volatile private var cached: Data = Data()

    init { load() }

    fun get(): Data = cached

    fun update(mutate: (Data) -> Data) {
        cached = mutate(cached)
        JsonStore.write(file, Data.serializer(), cached)
    }

    /** v0.3.4 — TTL éphémère d'une conversation (secondes ; 0 = OFF), miroir Android. */
    fun ephemeralTtl(peer: String): Long =
        get().ephTtl[peer.trim().lowercase()] ?: 0L

    fun setEphemeralTtl(peer: String, ttlSeconds: Long) {
        update { it.copy(ephTtl = it.ephTtl + (peer.trim().lowercase() to ttlSeconds)) }
    }

    private fun load() {
        // Les anciens fichiers (booléen closeMinimizes, thème binaire) restent
        // lisibles : ignoreUnknownKeys + nouveaux champs avec défauts sûrs.
        cached = JsonStore.read(file, Data.serializer()) ?: Data()
    }
}
