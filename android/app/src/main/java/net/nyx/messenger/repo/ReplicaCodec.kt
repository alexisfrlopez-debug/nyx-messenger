package net.nyx.messenger.repo

/**
 * v0.2.25 — ANNONCE DE RÉPLICA TEMPORAIRE (demande opérateur : « les réplicas
 * temporaires ne se partagent pas tout seul entre contacts, et ne s'ajoutent
 * pas seuls à la console »).
 *
 * Un appareil qui sert un réplica temporaire embarqué (build « test 2 »)
 * annonce `{"alias":…,"replica":"<onion>:<port>","ver":"0.2.25"}` à CHACUN de
 * ses contacts acceptés — événement type « contact » chiffré E2E (type
 * standard du journal, ZÉRO changement serveur, corps opaque pour les
 * réplicas). Le destinataire ingère l'adresse, la PERSISTE (7 jours sans
 * ré-annonce) et l'insère dans sa liste de réplicas (avant le rendezvous) —
 * les connexions suivantes utilisent les réplicas de proximité, ce qui
 * accélère l'amorçage et survit à la panne du serveur central.
 *
 * COMPATIBILITÉ : un client plus ancien qui reçoit ce corps retombe dans la
 * branche « annonce déjà traitée » de l'ingestion contact (aucun « alias »,
 * aucun « ack » → aucun effet) — l'ajout du champ « alias » garde le label
 * lisible chez lui.
 *
 * GARDE-FOUS (validation à l'ingestion, jamais à l'émission) :
 *   1. l'onion annoncée DOIT être celle de l'AUTEUR de l'événement — on ne
 *      peut annoncer que SON PROPRE réplica (l'adresse est la clé : un
 *      pair ne peut pas nous forcer à utiliser l'onion d'un tiers) ;
 *   2. format strict « <56 base32>.onion[:port] » (même grammaire que
 *      BootstrapReplicas), port 1-65535 ;
 *   3. l'auteur doit être un contact connu (garanti par l'ingestion « contact »
 *      qui ne traite que des événements de pairs déjà liés).
 *
 * Objet PUR (aucune classe Android) → testable en JVM (ReplicaCodecTest).
 */
object ReplicaCodec {

    /** Clé du corps JSON — un corps « contact » qui la porte est une annonce. */
    const val KEY = "replica"

    /** TTL de la découverte côté destinataire (7 jours sans ré-annonce). */
    const val DISCOVERY_TTL_MS: Long = 7L * 24 * 3600 * 1000

    data class Announcement(val replica: String, val alias: String, val ver: String)

    /**
     * Parse un corps « contact » déchiffré — renvoie null si ce n'est PAS une
     * annonce réplica (autres corps : invitation, ack, accusé, suppression,
     * restauration…). Format attendu :
     * `{"alias":"…","replica":"<onion>[:port]","ver":"…"}` (alias/ver optionnels).
     */
    fun parse(plain: String): Announcement? {
        val rep = Regex("\"$KEY\"\\s*:\\s*\"([^\"]*)\"").find(plain)?.groupValues?.get(1) ?: return null
        if (!Regex("^[a-z2-7]{56}\\.onion(:\\d{1,5})?$").matches(rep)) return null
        val port = rep.substringAfter(':', "").toIntOrNull()
        if (rep.contains(':') && (port == null || port !in 1..65535)) return null
        val alias = Regex("\"alias\"\\s*:\\s*\"([^\"]*)\"").find(plain)?.groupValues?.get(1).orEmpty()
        val ver = Regex("\"ver\"\\s*:\\s*\"([^\"]*)\"").find(plain)?.groupValues?.get(1)
            .orEmpty().take(24)
        return Announcement(rep, alias, ver)
    }

    /**
     * Garde-fou d'identité : l'adresse annoncée doit être celle de l'auteur.
     * @return true si l'annonce est utilisable (auteur == onion annoncée).
     */
    fun isSelfAnnounced(authorOnion: String, ann: Announcement): Boolean =
        ann.replica.substringBefore(':') == authorOnion.trim().lowercase()

    /**
     * Corps de l'annonce à émettre (chiffré E2E par l'appelant, comme les
     * autres corps « contact »). L'alias et la version restent optionnels.
     */
    fun encodeBody(alias: String, replicaAddr: String, ver: String): String {
        require(Regex("^[a-z2-7]{56}\\.onion(:\\d{1,5})?$").matches(replicaAddr)) {
            "adresse réplica invalide : $replicaAddr"
        }
        val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"") }
        return buildString {
            append('{')
            append("\"alias\":\"").append(esc(alias)).append('"')
            append(',').append("\"$KEY\":\"").append(esc(replicaAddr)).append('"')
            if (ver.isNotBlank()) append(",\"ver\":\"").append(esc(ver.take(24))).append('"')
            append('}')
        }
    }
}
