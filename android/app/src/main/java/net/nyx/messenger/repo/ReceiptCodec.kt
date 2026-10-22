package net.nyx.messenger.repo

/**
 * v0.2.15 — CODEC DES ACCUSÉS DE RÉCEPTION/LECTURE (demande opérateur :
 * « un accusé de réception et un accusé de lecture côté expéditeur »).
 *
 * Corps d'un événement type « contact » (type DÉJÀ whitelisté par le réplica
 * v0.2.0 — corps chiffré OPAQUE au serveur, zéro changement wire) :
 *
 *     {"alias":"<alias de l'émetteur>","rcpt":"delivered|read","ids":["<eventId>",…]}
 *
 * Volontairement SANS dépendance Android : testable en JVM, miroir exact du
 * traitement dans [NyxRepository] (émission [encode], réception [parse]).
 * Un corps sans clé « rcpt » (invitation {"alias":…} ou ack {"alias":…,
 * "ack":…}) ne parse PAS comme un accusé — les clients v0.2.14 l'ignorent
 * de leur côté (branche « annonce déjà traitée »).
 */
object ReceiptCodec {
    const val KIND_DELIVERED = "delivered" // le pair a reçu (inbox poll chez lui)
    const val KIND_READ = "read"           // le pair a ouvert la conversation

    fun isValidKind(kind: String): Boolean =
        kind == KIND_DELIVERED || kind == KIND_READ

    /** Corps JSON chiffré ensuite par le canal « contact » (sealed box E2E). */
    fun encode(alias: String, kind: String, ids: List<String>): String {
        val idsJson = ids.joinToString(",", "[", "]") { "\"$it\"" }
        return "{\"alias\":\"$alias\",\"rcpt\":\"$kind\",\"ids\":$idsJson}"
    }

    /**
     * Retourne (kind, ids) si le corps déchiffré est un accusé VALIDE, sinon
     * null (ce n'est PAS un accusé — invitation/ack : traiter comme tel).
     * ids peut être vide (accusé sans cible — rien à faire).
     */
    fun parse(plain: String): Pair<String, List<String>>? {
        val kind = Regex("\"rcpt\"\\s*:\\s*\"([^\"]*)\"").find(plain)
            ?.groupValues?.get(1) ?: return null
        if (!isValidKind(kind)) return null
        val ids = Regex("\"ids\"\\s*:\\s*\\[([^]]*)]").find(plain)
            ?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return kind to ids
    }
}
