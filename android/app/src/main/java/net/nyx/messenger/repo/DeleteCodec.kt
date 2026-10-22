package net.nyx.messenger.repo

/**
 * v0.2.19 — CODEC DE SUPPRESSION DES MESSAGES (demande opérateur : « possibilité
 * de supprimer les messages un par un, idem pour les fichiers et médias échangés,
 * avec possibilité de suppression coté destinataire quand on est expéditeur mais
 * pas de suppression coté expéditeur quand on est destinataire »).
 *
 * Corps d'un événement type « contact » (type DÉJÀ whitelisté par le réplica
 * v0.2.0 — corps chiffré OPAQUE au serveur, zéro changement wire), même canal
 * que les accusés v0.2.15 :
 *
 *     {"alias":"<alias de l'émetteur>","del":["<eventId>",…]}
 *
 * SÉMANTIQUE (asymétrie exigée) :
 *  - l'EXPÉDITEUR d'un message peut le supprimer chez le destinataire ;
 *  - le DESTINATAIRE ne supprime que SA copie locale (aucun effet chez
 *    l'expéditeur) — côté réception, la garde SQL « outgoing = 0 AND
 *    peerOnion = auteur » ne touche JAMAIS les messages du demandeur.
 *
 * Volontairement SANS dépendance Android : testable en JVM, miroir exact du
 * traitement dans [NyxRepository] (émission [encode], réception [parse]).
 * Un corps sans clé « del » ne parse PAS comme une suppression — les corps
 * {"alias":…} (invitation), {"alias":…,"ack":…} (cycle de vie) et
 * {"alias":…,"rcpt":…} (accusés) restent traités par leurs branches propres.
 */
object DeleteCodec {

    /** Lot max par événement (même borne que les accusés — corps raisonnable). */
    const val BATCH = 50

    /** Corps JSON chiffré ensuite par le canal « contact » (sealed box E2E). */
    fun encode(alias: String, ids: List<String>): String {
        val idsJson = ids.joinToString(",", "[", "]") { "\"$it\"" }
        return "{\"alias\":\"$alias\",\"del\":$idsJson}"
    }

    /**
     * Liste des eventId à supprimer si le corps déchiffré est une demande de
     * suppression VALIDE, sinon null (ce n'est PAS une suppression). Une liste
     * vide est valide (rien à faire) — comme pour les accusés.
     */
    fun parse(plain: String): List<String>? {
        // Clé « del » suivie d'un tableau JSON — les ids sont des hex sha256
        // (aucun échappement possible, Regex fiable ici).
        val m = Regex("\"del\"\\s*:\\s*\\[([^]]*)]").find(plain) ?: return null
        // Garde : un corps d'ACCUSÉ contient « rcpt » — il doit partir dans la
        // branche accusés même s'il porterait aussi un champ « del » (jamais
        // émis ainsi, mais le plus spécifique gagne).
        if (Regex("\"rcpt\"\\s*:").containsMatchIn(plain)) return null
        return m.groupValues[1]
            .split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
    }
}
