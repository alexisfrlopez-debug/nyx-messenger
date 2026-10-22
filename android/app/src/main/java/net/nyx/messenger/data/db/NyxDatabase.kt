package net.nyx.messenger.data.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/* ============================ Entités ============================ */

/**
 * v0.2.12 — statuts d'invitation partagés (DB + UI). Demande opérateur :
 * « indiquer à un client qui ajoute un contact si son invitation est toujours
 * en attente, acceptée ou déclinée ».
 *
 * v0.2.14 — cycle de vie complet (demande opérateur) : refus et suppression
 * PROPAGÉS au pair, conversations archivées accessibles, re-invitation.
 * AUCUN changement de schéma : les nouveaux états sont des VALEURS de la
 * colonne `status` (TEXT) — Room version inchangée, zéro migration.
 */
object ContactStatus {
    const val NONE = ""             // annonce pas encore déposée
    const val PENDING = "pending"       // annonce déposée, attente du pair
    const val INCOMING = "incoming"     // invitation reçue, à traiter
    const val ACCEPTED = "accepted"     // mutuel / ack accepté — SEUL état messageable
    const val DECLINED = "declined"     // le pair a refusé (archivé — conversation accessible)
    // v0.2.14 : le pair m'a supprimé (archivé — conversation accessible).
    const val REMOVED = "removed"
    // v0.2.14 : j'ai supprimé ce contact (archivé en attendant le dépôt de
    // l'avis « removed » au pair — reparti par la boucle 30 s comme les acks).
    const val REMOVED_BY_ME = "removed_by_me"

    /** Archivé = plus un contact actif : conversation accessible, re-invitable. */
    fun archived(status: String): Boolean =
        status == DECLINED || status == REMOVED || status == REMOVED_BY_ME
}

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val onion: String,       // adresse onion du contact (= clé publique)
    val alias: String,                   // nom local (jamais transmis)
    val addedAt: Long,
    // v0.2.10 : 1 = la demande de contact (événement « contact » chiffré) n'a pas
    // encore pu être déposée sur le réplica (injoignable à l'ajout) — elle repart
    // dès la prochaine entrée sur la liste ([NyxRepository.ensureContactNotified]).
    @ColumnInfo(defaultValue = "0") val notifyPending: Int = 0,
    // v0.2.11 : 0 = AUCUNE annonce déposée pour ce contact. Les contacts créés
    // avant v0.2.10 (ajout purement local) sortent de la migration avec 0 : ils
    // sont (ré-)annoncés automatiquement par [NyxRepository.ensureContactNotified]
    // — c'est ce qui débloque les paires bloquées depuis v0.2.9 (le pair n'avait
    // JAMAIS reçu d'invitation, l'événement n'ayant jamais été déposé).
    @ColumnInfo(defaultValue = "0") val notifiedAt: Long = 0,
    // v0.2.12 : STATUT DE L'INVITATION vu par CET appareil (demande opérateur :
    // « indiquer à un client qui ajoute un contact si son invitation est toujours
    // en attente, acceptée ou déclinée »). Valeurs :
    //   ""          = annonce pas encore déposée (notifyPending/notifiedAt) ;
    //   "pending"   = annonce déposée, attente de la réponse du pair ;
    //   "incoming"  = invitation reçue du pair, à accepter/refuser ;
    //   "accepted"  = mutuel (ou ack « accepted » reçu) — conversation ouverte ;
    //   "declined"  = le pair a décliné l'invitation.
    @ColumnInfo(defaultValue = "") val status: String = "",
    // v0.2.12 : 1 = mon acceptation/refus doit repartir au pair (ack « contact »
    // chiffré) ; reparti par [NyxRepository.ensureContactNotified] comme les
    // annonces (réplica injoignable au moment du choix).
    @ColumnInfo(defaultValue = "0") val ackPending: Int = 0,
    // v0.2.29 — BLOCAGE LOCAL (demande opérateur : « la possibilité de bloquer/
    // débloquer un contact, et un onglet pour gérer la liste de blocages ») :
    // 0 = contact normal ; > 0 = horodatage du blocage — ses messages/fragments/
    // signalisations d'appel sont DÉTRUITS à l'arrivée (jamais stockés, jamais
    // sonnés), il n'apparaît plus dans Conversations ni Contacts (onglet
    // « Blocages »), l'envoi vers lui est refusé. Le lien contact (statut,
    // historique) est PRÉSERVÉ : débloquer restaure l'état exact d'avant.
    // PUREMENT LOCAL : le pair n'est jamais informé (pas d'événement émis).
    @ColumnInfo(defaultValue = "0") val blockedAt: Long = 0
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val eventId: String,     // sha256 de la représentation canonique
    val peerOnion: String,               // interlocuteur
    val outgoing: Boolean,               // true = envoyé par nous
    val text: String,                    // déchiffré (jamais synchronisé)
    val sentAt: Long,                    // horodatage déclaré
    val receivedAt: Long,
    val state: Int,                      // 0=pending, 1=sent, 2=failed, 3=received
    // v0.2.15 — accusés de réception/lecture (demande opérateur). CÔTÉ
    // DESTINATAIRE : readLocal = 1 dès que la conversation est ouverte
    // (indicateur « non lu » de la liste) ; rcptPending = 1 (accusé
    // « delivered » à déposer) ou 2 (« read » à déposer — remplace le 1).
    // CÔTÉ EXPÉDITEUR : deliveredAt/readAt = horodatage de l'accusé reçu
    // du pair (0 = pas encore) — affichés ✓ Envoyé / ✓✓ Reçu / ✓✓ Lu.
    @ColumnInfo(defaultValue = "0") val readLocal: Int = 0,
    @ColumnInfo(defaultValue = "0") val rcptPending: Int = 0,
    @ColumnInfo(defaultValue = "0") val deliveredAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val readAt: Long = 0,
    // v0.2.17 — pièces jointes : kind 0 = texte, 1 = fichier (photo/vidéo/audio/
    // document) référencé par fid dans la table attachments. Le champ text
    // d'un message fichier porte un LIBELLÉ ("📷 photo.jpg") — aperçu de liste
    // et notifications fonctionnent sans jointure ; la bulle, elle, rend la
    // carte média depuis [AttachmentEntity] (aperçu/progression/taille).
    @ColumnInfo(defaultValue = "0") val kind: Int = 0,
    @ColumnInfo(defaultValue = "") val fid: String = "",
    // v0.2.26 — ÉPHÉMÈRE : époque (ms) de péremption LOCALE du message
    // (destinataire ET expéditeur) ; 0 = permanent. Le TTL voyage signé dans
    // l'événement (wire-format.md — ttl) ; le serveur garde l'événement
    // chiffré (limitation documentée), l'effacement est garanti côté client.
    @ColumnInfo(defaultValue = "0") val expireAt: Long = 0L
)

/**
 * v0.2.17 — méta d'un TRANSFERT de fichier (photo/vidéo/audio/document).
 * Un enregistrement par fichier partagé (sortant ou entrant) ; les fragments
 * binaires en vol vivent dans [AttachmentChunkEntity] et sont EFFACÉS dès
 * l'assemblage (sha256 vérifié) — le fichier assemblé vit sur le disque
 * (filesDir/media/<fid>/<nom>) et [localPath] le désigne.
 * states : 0 = transfert en cours, 1 = prêt (fichier assemblé/écrit),
 * 2 = échec (incomplet, sha256 en défaut, trop volumineux…).
 */
@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val fid: String,         // identifiant du transfert (32 hex aléatoires)
    val peerOnion: String,               // interlocuteur
    val outgoing: Boolean,               // true = nous l'avons envoyé
    val name: String,                    // nom sûr (séparateurs neutralisés)
    val mime: String,                    // image/jpeg, video/mp4, audio/mp4…
    val size: Long,                      // taille totale déclarée (octets)
    val sha256: String,                  // empreinte du fichier COMPLET (64 hex)
    val totalChunks: Int,                // nombre de fragments annoncés
    val state: Int,                      // AT_TRANSFER / AT_READY / AT_FAILED
    val localPath: String,               // rempli quand state = AT_READY
    val createdAt: Long,
    // v0.2.28 — politique d'affichage (style Instagram) : "forever" (défaut,
    // rétrocompatible avec les fragments v0.2.16-27 sans ce champ), "once"
    // (vue unique puis destruction locale) ou "twice" (1 relecture autorisée).
    @ColumnInfo(defaultValue = "forever") val viewPolicy: String = VIEW_FOREVER,
    // v0.2.28 — vues restantes côté récepteur : -1 = illimité (forever) ;
    // décrémenté à chaque fermeture du visualiseur ; 0 = média détruit.
    @ColumnInfo(defaultValue = "-1") val viewsLeft: Int = -1
) {
    companion object {
        const val VIEW_FOREVER = "forever"
        const val VIEW_ONCE = "once"
        const val VIEW_TWICE = "twice"
    }
}

/**
 * v0.2.17 — fragment binaire en vol (stocké par (fid, idx), REPLACE idempotent
 * — une re-livraison après crash avant avancée du curseur ré-écrit le même
 * fragment sans effet). Ligne effacée dès l'assemblage du fichier complet.
 */
@Entity(tableName = "attachment_chunks", primaryKeys = ["fid", "idx"])
data class AttachmentChunkEntity(
    val fid: String,
    val idx: Int,
    val data: ByteArray
)

/**
 * v0.2.15 — cache local de la carte publique D'UN PAIR (avatar + nom affiché).
 * Les événements `profile` en diffusion ne passent PAS par l'inbox (le serveur
 * ne livre que les événements adressés — journal.Inbox `ev.To == to`) : la
 * liste des conversations ne peut pas les découvrir en pollant. La carte est
 * donc récupérée à la demande (GET /client/profile) et mise en cache ici,
 * rafraîchie à l'entrée sur la liste (TTL 1 h — [NyxRepository.refreshPeerProfiles]).
 * displayName/avatar sont VIDES si le pair n'a rien publié (icône générique —
 * demande opérateur : « sa photo si elle est publique, une icône générique
 * si elle est privée »).
 */
@Entity(tableName = "peer_profiles")
data class PeerProfileEntity(
    @PrimaryKey val onion: String,
    val displayName: String,             // display_name public ("" si absent)
    val avatar: String,                  // avatar public base64 ("" si absent/privé)
    val fetchedAt: Long                  // horodatage du dernier fetch réussi
)

/**
 * v0.2.19 — SUPPRESSION À DISTANCE EN ATTENTE (demande opérateur : « possibilité
 * de suppression coté destinataire quand on est expéditeur »). Une demande de
 * suppression « pour tous » émise alors que le réplica est injoignable est
 * MÉMORISÉE ici et repartie par la boucle périodique ([NyxRepository.ensureDeletionsSent])
 * — même mécanique que les accusés (rcptPending) et les acks (ackPending).
 * ids = tableau JSON d'eventId (sha256 canonique — partagés par les deux côtés).
 */
@Entity(tableName = "del_pending")
data class DelPendingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peer: String,                    // destinataire de la demande (onion)
    val ids: String,                     // tableau JSON ["<eventId>",…]
    val createdAt: Long
)

/**
 * v0.2.19 → v0.2.35 — RÉPLICA TEMPORAIRE EMBARQUÉ (TOUTES les versions
 * depuis la v0.2.35 — BuildConfig.EMBEDDED_REPLICA toujours vrai) : le
 * téléphone héberge un réplica pendant que l'application tourne. Les
 * événements y sont stockés OPaques (corps chiffrés
 * E2E, jamais déchiffrés par le réplica — même rôle que le serveur Go), dans
 * la base SQLCipher, avec plafonds + purge 48 h ([net.nyx.messenger.replica.EmbeddedReplica]).
 * gid = curseur global 1-based (miroir de journal.maxIndex côté Go — sert de
 * curseur d'inbox). Cette table existe dans TOUTES les versions (schéma unique)
 * et reste vide quand l'interrupteur Paramètres est désactivé.
 */
@Entity(
    tableName = "replica_events",
    // v0.2.22 — CRASH TERRAIN (Blackview, montée v0.2.18 → v0.2.21) : la
    // MIGRATION_6_7 (v0.2.19) crée l'index inbox [INDEX_REPLICA_EVENTS_TO_GID]
    // alors que l'entité ne le déclarait pas → toute base passée par la
    // migration porte un index que la validation Room rejette
    // (« Migration didn't properly handle: replica_events », FATAL en boucle —
    // la transaction onUpgrade est annulée à chaque ouverture). L'entité
    // déclare DÉSORMAIS l'index (nom explicite = nom canonique Room
    // index_<table>_<colonnes>) ; les bases d'INSTALLATION FRAÎCHE v7 (sans
    // index) sont alignées par MIGRATION_7_8. Constantes partagées avec la
    // migration et le test de garde [net.nyx.messenger.data.db.NyxSchemaGuardTest].
    indices = [Index(value = ["to", "gid"], name = NyxDatabase.INDEX_REPLICA_EVENTS_TO_GID)]
)
data class ReplicaEventEntity(
    @PrimaryKey val eventId: String,     // sha256 de la représentation canonique
    val gid: Long,                       // curseur global (insertion, 1-based)
    val type: String,
    val author: String,
    val seq: Long,
    val ts: Long,
    val ttl: Long,
    val to: String,                      // "" = diffusion (alias/profile)
    val body: String,                    // base64, OPAQUE (chiffré E2E)
    val sig: String,
    val bytes: Long,                     // taille du corps (plafond global)
    val insertedAt: Long                 // purge TTL 48 h
)

/** État du réplica embarqué (une ligne, id=1). */
@Entity(tableName = "replica_state")
data class ReplicaStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastPurgeAt: Long = 0
)

@Entity(tableName = "poll_state")
data class PollStateEntity(
    @PrimaryKey val replicaOnion: String,
    val cursor: Long                     // curseur global inbox sur ce réplica
)

/**
 * v0.2.10 — dernier numéro de séquence utilisé PAR AUTEUR (ed25519/onion).
 * Bug v0.1 : le compteur vivait en mémoire et repartait à 1 à chaque relance,
 * alors que le journal serveur exige seq = dernier+1 (strictement croissant) :
 * les premiers événements après un redémarrage étaient rejetés (400 « seq déjà
 * dépassée ») et les messages ne partaient jamais. La persistance Room survit
 * aux redémarrages ; une dérive résiduelle (restauration de sauvegarde…) est
 * auto-réparée depuis le message d'erreur du réplica ([NyxRepository.postSigned]).
 */
@Entity(tableName = "seq_state")
data class SeqStateEntity(
    @PrimaryKey val author: String,      // adresse onion de l'auteur
    val seq: Long                        // DERNIÈRE seq déposée avec succès
)

/* ============================ DAOs ============================ */

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY alias")
    fun all(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE onion = :onion")
    suspend fun get(onion: String): ContactEntity?

    /** v0.2.14 : contact observable (alias dans les titres, bannière non mutuel). */
    @Query("SELECT * FROM contacts WHERE onion = :onion")
    fun byOnion(onion: String): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE (notifyPending = 1 OR notifiedAt = 0) " +
        "AND status NOT IN ('declined', 'removed', 'removed_by_me')")
    suspend fun pendingNotify(): List<ContactEntity>

    @Query("UPDATE contacts SET notifyPending = :pending WHERE onion = :onion")
    suspend fun setNotifyPending(onion: String, pending: Int)

    /** v0.2.11 : horodate la dernière annonce « contact » déposée avec succès.
     *  v0.2.12 : le statut passe à « pending » — l'invitation attend la réponse
     *  du pair (demande opérateur : visibilité de l'état de l'invitation). */
    @Query("UPDATE contacts SET notifiedAt = :at, notifyPending = 0, status = 'pending' WHERE onion = :onion")
    suspend fun markNotified(onion: String, at: Long)

    /** v0.2.12 : statut d'invitation local (incoming/accepted/declined/pending). */
    @Query("UPDATE contacts SET status = :status WHERE onion = :onion")
    suspend fun setStatus(onion: String, status: String)

    /** v0.2.12 : ack acceptation/refus restant à déposer au pair. */
    @Query("SELECT * FROM contacts WHERE ackPending = 1")
    suspend fun pendingAcks(): List<ContactEntity>

    @Query("UPDATE contacts SET ackPending = :pending WHERE onion = :onion")
    suspend fun setAckPending(onion: String, pending: Int)

    /** v0.2.11 : suppression d'un contact (fonction demandée en test terrain).
     *  v0.2.14 : la suppression N'EFFACE PLUS la ligne (archivage) — cette
     *  requête ne sert plus qu'aux cas sans historique. */
    @Query("DELETE FROM contacts WHERE onion = :onion")
    suspend fun delete(onion: String)

    /** v0.2.14 — RE-INVITATION d'un contact archivé (refusé/supprimé) : remise
     *  à zéro du lien wire — une NOUVELLE annonce partira (inline ou boucle 30 s).
     *  Requête pure : AUCUN changement de schéma. */
    @Query("UPDATE contacts SET status = '', notifyPending = 1, notifiedAt = 0 WHERE onion = :onion")
    suspend fun resetForReinvite(onion: String)

    /** v0.2.14 : contacts ACTIFS (non archivés) — liste principale.
     *  v0.2.29 : les contacts BLOQUÉS sortent de la liste principale (onglet
     *  « Blocages » — [allBlocked]) ; le lien/statut reste inchangé. */
    @Query("SELECT * FROM contacts WHERE status NOT IN ('declined', 'removed', 'removed_by_me') " +
        "AND blockedAt = 0 ORDER BY alias")
    fun allActive(): Flow<List<ContactEntity>>

    /** v0.2.25 : contacts acceptés (émission des annonces de réplica temporaire).
     *  v0.2.29 : un contact bloqué ne reçoit plus nos annonces (ni E2E, ni
     *  auto-déclaration de réplica — il ne découvre plus rien de nous). */
    @Query("SELECT * FROM contacts WHERE status = 'accepted' AND blockedAt = 0 ORDER BY alias")
    suspend fun accepted(): List<ContactEntity>

    /** v0.2.29 — contacts BLOQUÉS (onglet « Blocages » de la liste principale). */
    @Query("SELECT * FROM contacts WHERE blockedAt > 0 ORDER BY alias")
    fun allBlocked(): Flow<List<ContactEntity>>

    /** v0.2.29 — pose/lève le blocage local (ts = 0 pour débloquer). */
    @Query("UPDATE contacts SET blockedAt = :ts WHERE onion = :onion")
    suspend fun setBlocked(onion: String, ts: Long)

    /** v0.2.14 : contacts archivés (refusés/supprimés) — anciennes conversations. */
    @Query("SELECT * FROM contacts WHERE status IN ('declined', 'removed', 'removed_by_me') ORDER BY alias")
    fun allArchived(): Flow<List<ContactEntity>>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerOnion = :peer ORDER BY sentAt ASC")
    fun conversation(peer: String): Flow<List<MessageEntity>>

    /** v0.2.26 : export de conversations — lecture one-shot (hors Flow). */
    @Query("SELECT * FROM messages WHERE peerOnion = :peer ORDER BY sentAt ASC")
    suspend fun conversationOnce(peer: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    /** v0.2.26 : lecture unitaire — anti-doublon de notification (polls
     *  concurrents) et vérification avant effacement éphémère. */
    @Query("SELECT * FROM messages WHERE eventId = :id LIMIT 1")
    suspend fun byId(id: String): MessageEntity?

    /** v0.2.26 : messages éphémères dont la péremption locale est passée. */
    @Query("SELECT * FROM messages WHERE expireAt > 0 AND expireAt <= :now LIMIT 200")
    suspend fun expired(now: Long): List<MessageEntity>

    @Query("UPDATE messages SET state = :state WHERE eventId = :eventId")
    suspend fun updateState(eventId: String, state: Int)

    /** v0.2.11 : suppression de TOUTE la conversation d'un contact (avec lui). */
    @Query("DELETE FROM messages WHERE peerOnion = :peer")
    suspend fun deleteConversation(peer: String)

    // ── v0.2.15 — accusés de réception/lecture + aperçus de liste ──

    /** Dernier message PAR PAIR (aperçu de la liste — demande opérateur :
     *  « à côté de chaque alias … le dernier message de la conversation
     *  (envoyé ou reçu) »). Sous-requête corrélée : portable SQLite. */
    @Query(
        "SELECT * FROM messages m WHERE m.eventId = (" +
        "SELECT m2.eventId FROM messages m2 WHERE m2.peerOnion = m.peerOnion " +
        "ORDER BY m2.sentAt DESC, m2.eventId DESC LIMIT 1)"
    )
    fun lastByPeer(): Flow<List<MessageEntity>>

    /** Nombre de messages reçus NON lus par pair (indicateur destinataire). */
    @Query(
        "SELECT peerOnion AS peer, COUNT(*) AS cnt FROM messages " +
        "WHERE outgoing = 0 AND readLocal = 0 GROUP BY peerOnion"
    )
    fun unreadByPeer(): Flow<List<UnreadCount>>

    /** Marque TOUTE la conversation d'un pair comme lue localement. */
    @Query("UPDATE messages SET readLocal = 1 WHERE peerOnion = :peer AND outgoing = 0 AND readLocal = 0")
    suspend fun markReadLocally(peer: String): Int

    /** Messages reçus non lus d'un pair — ids pour l'accusé « read ». */
    @Query("SELECT eventId FROM messages WHERE peerOnion = :peer AND outgoing = 0 AND readLocal = 1 AND rcptPending > 0")
    suspend fun readPendingIds(peer: String): List<String>

    /** Messages reçus non accusés-réceptionnés d'un pair (ids pour « delivered »). */
    @Query("SELECT eventId FROM messages WHERE peerOnion = :peer AND outgoing = 0 AND rcptPending = 1")
    suspend fun deliveredPendingIds(peer: String): List<String>

    /** Pairs ayant au moins un accusé en attente (rcptPending > 0). */
    @Query("SELECT DISTINCT peerOnion FROM messages WHERE rcptPending > 0")
    suspend fun peersWithPendingReceipts(): List<String>

    /** Règle rcptPending = 0 pour des ids donnés (accusé déposé). */
    @Query("UPDATE messages SET rcptPending = 0 WHERE eventId IN (:ids)")
    suspend fun clearRcptPending(ids: List<String>)

    /** Passe les accusés « delivered » (rcptPending=1) en « read » (2) d'un pair. */
    @Query("UPDATE messages SET rcptPending = 2 WHERE peerOnion = :peer AND rcptPending = 1")
    suspend fun promoteDeliveredToRead(peer: String)

    /** Accusé « delivered » reçu du pair — CÔTÉ EXPÉDITEUR. */
    @Query("UPDATE messages SET deliveredAt = :at WHERE eventId IN (:ids) AND peerOnion = :peer AND outgoing = 1 AND deliveredAt = 0")
    suspend fun markDelivered(peer: String, ids: List<String>, at: Long)

    /** Accusé « read » reçu du pair (implique delivered) — CÔTÉ EXPÉDITEUR. */
    @Query("UPDATE messages SET deliveredAt = CASE WHEN deliveredAt = 0 THEN :at ELSE deliveredAt END, readAt = :at WHERE eventId IN (:ids) AND peerOnion = :peer AND outgoing = 1 AND readAt = 0")
    suspend fun markRead(peer: String, ids: List<String>, at: Long)

    // ── v0.2.19 — suppression unitaire (locale + à distance) ──

    /** Lignes complètes d'un lot d'eventId (récupérer les fid AVANT effacement). */
    @Query("SELECT * FROM messages WHERE eventId IN (:ids)")
    suspend fun byEventIds(ids: List<String>): List<MessageEntity>

    /** Le message porteur d'une pièce jointe (onglet médias → suppression). */
    @Query("SELECT * FROM messages WHERE fid = :fid LIMIT 1")
    suspend fun byFid(fid: String): MessageEntity?

    /** Suppression locale SANS garde (usage interne après fetch des fid). */
    @Query("DELETE FROM messages WHERE eventId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** Application d'une demande de suppression À DISTANCE — GARDE DE
     *  L'ASYMÉTRIE : seuls les messages QUE NOUS AVONS REÇUS de cet auteur
     *  (outgoing = 0, peerOnion = auteur) sont touchés. L'auteur ne peut
     *  jamais effacer nos propres messages, ni ceux d'un tiers. */
    @Query("DELETE FROM messages WHERE eventId IN (:ids) AND peerOnion = :peer AND outgoing = 0")
    suspend fun deleteIncomingFrom(peer: String, ids: List<String>): Int
}

/** Projection du compteur de non-lus par pair (v0.2.15). */
data class UnreadCount(val peer: String, val cnt: Int)

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(a: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE fid = :fid")
    suspend fun get(fid: String): AttachmentEntity?

    /** v0.2.28 — décrémente les vues restantes (jamais sous 0) ; renvoie la valeur après. */
    @Query("UPDATE attachments SET viewsLeft = viewsLeft - 1 WHERE fid = :fid AND viewsLeft > 0")
    suspend fun decrementViews(fid: String)

    /** v0.2.28 — relecture de la ligne après décrément. */
    @Query("SELECT viewsLeft FROM attachments WHERE fid = :fid")
    suspend fun viewsLeftOf(fid: String): Int?

    /** Flux observable par la bulle média (progression/état en direct). */
    @Query("SELECT * FROM attachments WHERE fid = :fid")
    fun byFid(fid: String): Flow<AttachmentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(c: AttachmentChunkEntity)

    @Query("SELECT COUNT(*) FROM attachment_chunks WHERE fid = :fid")
    suspend fun countChunks(fid: String): Int

    /** Progression de réception observable (fragments reçus / total). */
    @Query("SELECT COUNT(*) FROM attachment_chunks WHERE fid = :fid")
    fun chunksFlow(fid: String): Flow<Int>

    @Query("SELECT * FROM attachment_chunks WHERE fid = :fid ORDER BY idx ASC")
    suspend fun chunks(fid: String): List<AttachmentChunkEntity>

    @Query("DELETE FROM attachment_chunks WHERE fid = :fid")
    suspend fun clearChunks(fid: String)

    @Query("UPDATE attachments SET state = :state, localPath = :path WHERE fid = :fid")
    suspend fun setState(fid: String, state: Int, path: String = "")

    /** Nettoyage avec la conversation (suppression de contact). */
    @Query("DELETE FROM attachment_chunks WHERE fid IN (SELECT fid FROM attachments WHERE peerOnion = :peer)")
    suspend fun deleteChunksByPeer(peer: String)

    @Query("DELETE FROM attachments WHERE peerOnion = :peer")
    suspend fun deleteByPeer(peer: String)

    // ── v0.2.19 — onglet médias + suppression unitaire ──

    /** Tous les médias échangés avec un pair (onglet « Fichiers & médias »). */
    @Query("SELECT * FROM attachments WHERE peerOnion = :peer ORDER BY createdAt DESC")
    fun byPeer(peer: String): Flow<List<AttachmentEntity>>

    @Query("DELETE FROM attachments WHERE fid = :fid")
    suspend fun deleteFid(fid: String)
}

@Dao
interface PeerProfileDao {
    @Query("SELECT * FROM peer_profiles WHERE onion = :onion")
    fun byOnion(onion: String): Flow<PeerProfileEntity?>

    @Query("SELECT * FROM peer_profiles WHERE onion = :onion")
    suspend fun get(onion: String): PeerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: PeerProfileEntity)

    @Query("DELETE FROM peer_profiles WHERE onion = :onion")
    suspend fun delete(onion: String)
}

/** v0.2.19 — demandes de suppression « pour tous » différées (réplica injoignable). */
@Dao
interface DelPendingDao {
    @Insert
    suspend fun insert(p: DelPendingEntity)

    @Query("SELECT * FROM del_pending ORDER BY id ASC")
    suspend fun all(): List<DelPendingEntity>

    @Query("DELETE FROM del_pending WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM del_pending")
    suspend fun clear()
}

/** v0.2.19 — stockage du réplica temporaire embarqué (voir [ReplicaEventEntity]). */
@Dao
interface ReplicaDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(e: ReplicaEventEntity)

    @Query("SELECT * FROM replica_events WHERE eventId = :id")
    suspend fun event(id: String): ReplicaEventEntity?

    /** Dernier seq accepté d'un auteur (miroir journal.lastSeq côté Go). */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM replica_events WHERE author = :author")
    suspend fun lastSeq(author: String): Long

    /** Curseur global courant (miroir journal.maxIndex — 0 si vide). */
    @Query("SELECT COALESCE(MAX(gid), 0) FROM replica_events")
    suspend fun maxGid(): Long

    /** Boîte d'un destinataire au-delà d'un curseur global (miroir journal.Inbox). */
    @Query("SELECT * FROM replica_events WHERE `to` = :to AND gid > :after ORDER BY gid ASC LIMIT :limit")
    suspend fun inbox(to: String, after: Long, limit: Int): List<ReplicaEventEntity>

    /** Carte de profil publique courante d'un auteur (dernier seq gagne). */
    @Query("SELECT * FROM replica_events WHERE type = 'profile' AND `to` = '' AND author = :author ORDER BY seq DESC LIMIT 1")
    suspend fun profileOf(author: String): ReplicaEventEntity?

    /** Claims d'alias (règle du plus petit id résolue en Kotlin). */
    @Query("SELECT * FROM replica_events WHERE type = 'alias' AND `to` = ''")
    suspend fun aliasEvents(): List<ReplicaEventEntity>

    @Query("SELECT COUNT(*) FROM replica_events")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM replica_events")
    suspend fun totalBytes(): Long

    /** Purge TTL (48 h). */
    @Query("DELETE FROM replica_events WHERE insertedAt < :before")
    suspend fun purgeOlderThan(before: Long): Int

    /** Plafond global d'événements : supprime les PLUS ANCIENS au-delà de :keep. */
    @Query("DELETE FROM replica_events WHERE eventId IN (SELECT eventId FROM replica_events ORDER BY gid ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int): Int

    /** Plafond par boîte : supprime les plus anciens au-delà de :keep pour :to. */
    @Query("DELETE FROM replica_events WHERE `to` = :to AND eventId IN (SELECT eventId FROM replica_events WHERE `to` = :to ORDER BY gid ASC LIMIT :n)")
    suspend fun deleteOldestForInbox(to: String, n: Int): Int

    /** Nombre d'événements dans la boîte d'un destinataire (plafond par inbox). */
    @Query("SELECT COUNT(*) FROM replica_events WHERE `to` = :to")
    suspend fun countForInbox(to: String): Int

    @Query("DELETE FROM replica_events")
    suspend fun clear()

    @Query("SELECT * FROM replica_state WHERE id = 1")
    suspend fun state(): ReplicaStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(s: ReplicaStateEntity)
}

@Dao
interface PollStateDao {
    @Query("SELECT * FROM poll_state WHERE replicaOnion = :replica")
    suspend fun get(replica: String): PollStateEntity?

    // v0.2.21 : instantané des curseurs pour la sauvegarde v2.
    @Query("SELECT * FROM poll_state")
    suspend fun all(): List<PollStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: PollStateEntity)
}

@Dao
interface SeqStateDao {
    @Query("SELECT * FROM seq_state WHERE author = :author")
    suspend fun get(author: String): SeqStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SeqStateEntity)
}

/* ============================ Base ============================ */

/**
 * Base locale — chiffrée au repos via SQLCipher (clé de base dérivée du Keystore,
 * cf. ServiceLocator). Les messages en clair n'existent QUE dans cette base scellée.
 */
@Database(
    entities = [ContactEntity::class, MessageEntity::class, PeerProfileEntity::class,
        AttachmentEntity::class, AttachmentChunkEntity::class,
        PollStateEntity::class, SeqStateEntity::class,
        DelPendingEntity::class, ReplicaEventEntity::class, ReplicaStateEntity::class],
    // v0.2.22 — version 8 : réparation du schéma replica_events (MIGRATION_7_8).
    // v0.2.26 — version 9 : colonne messages.expireAt (messages éphémères).
    // v0.2.28 — version 10 : politique d'affichage média (vue unique / 1
    //            relecture / permanent) — attachments.viewPolicy + viewsLeft.
    // v0.2.29 — version 11 : BLOCAGE LOCAL des contacts — contacts.blockedAt
    //            (horodatage ; 0 = non bloqué). Purement additif.
    version = 11,
    exportSchema = false
)
abstract class NyxDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun peerProfileDao(): PeerProfileDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun pollStateDao(): PollStateDao
    abstract fun seqStateDao(): SeqStateDao
    abstract fun delPendingDao(): DelPendingDao
    abstract fun replicaDao(): ReplicaDao

    companion object {
        const val NAME = "nyx.db"

        /**
         * v0.2.18 — Base LEURRE du mot de passe panique : même schéma, vide —
         * ouverte à la place de [NAME] quand la session démarre en mode panique
         * (contacts et historique invisibles ; le vrai compte est intact).
         */
        const val NAME_DECOY = "nyx_decoy.db"

        /**
         * v0.2.22 — Nom d'index CANONIQUE Room (index_<table>_<colonnes>) de
         * l'index inbox de replica_events. Déclaré dans l'entité
         * [ReplicaEventEntity] ET créé par [MIGRATION_6_7]/[MIGRATION_7_8] —
         * une seule source de vérité (test de garde inclus).
         */
        const val INDEX_REPLICA_EVENTS_TO_GID = "index_replica_events_to_gid"

        /** v0.2.22 — SQL de l'index inbox (partagé migration 6→7 / 7→8). */
        const val SQL_INDEX_REPLICA_EVENTS_TO_GID =
            "CREATE INDEX IF NOT EXISTS $INDEX_REPLICA_EVENTS_TO_GID " +
                "ON replica_events(`to`, gid)"

        /** v1 → v2 : persistance de la seq par auteur + notification de contact différée. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS seq_state (" +
                        "author TEXT NOT NULL PRIMARY KEY, seq INTEGER NOT NULL)"
                )
                db.execSQL(
                    "ALTER TABLE contacts ADD COLUMN notifyPending INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v2 → v3 (v0.2.11) : horodatage de l'annonce « contact » par contact.
         * Les lignes existantes sortent à 0 : leurs annonces seront déposées au
         * premier passage sur la liste (répare les contacts ajoutés sous v0.2.9,
         * dont l'annonce n'a JAMAIS pu partir — la fonctionnalité n'existait pas).
         * Au passage : normalisation des adresses (trim + minuscules) — un onion
         * collé avec des espaces était stocké tel quel et rendait le champ `to`
         * des événements illisible pour la inbox du pair (perte silencieuse,
         * reproduite en e2e — scripts/e2e_repro.py S3).
         */
        /**
         * v3 → v4 (v0.2.12) : statut d'invitation + ack en attente.
         * Les lignes existantes sortent avec status='' : leur sémantique est
         * conservée (annoncées → re-deviennent 'pending' au prochain markNotified
         * de la relance périodique ; jamais annoncées → announce repart déjà).
         * Les contacts « reçus » créés par v0.2.10/v0.2.11 chez le destinataire
         * (notifiedAt≠0 posé à la réception de l'annonce du PAIR) restent '' :
         * le pair devra renvoyer son annonce ou le contact resse sera requalifié
         * à la prochaine annonce reçue (pollInbox, v0.2.12 : requalifie toujours).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN status TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE contacts ADD COLUMN ackPending INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5 (v0.2.15) : accusés de réception/lecture + cache des profils
         * des pairs. MIGRATION ADDITIVE — aucune donnée détruite :
         *  - messages : 4 colonnes (readLocal/rcptPending côté destinataire,
         *    deliveredAt/readAt côté expéditeur) — sortent à 0 = jamais lu,
         *    aucun accusé en vol considéré (les accusés arrivés avant la
         *    migration sont perdus, tolérable à la livraison) ;
         *  - peer_profiles : cache neuf, rempli au premier passage sur la liste.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN readLocal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN rcptPending INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveredAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN readAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS peer_profiles (" +
                        "onion TEXT NOT NULL PRIMARY KEY, " +
                        "displayName TEXT NOT NULL, " +
                        "avatar TEXT NOT NULL, " +
                        "fetchedAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v5 → v6 (v0.2.17) : pièces jointes. MIGRATION ADDITIVE — aucune
         * donnée détruite : messages +2 colonnes (kind/fid, défaut = texte),
         * tables neuves attachments + attachment_chunks (vides à la création).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN fid TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS attachments (" +
                        "fid TEXT NOT NULL PRIMARY KEY, " +
                        "peerOnion TEXT NOT NULL, " +
                        "outgoing INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "mime TEXT NOT NULL, " +
                        "size INTEGER NOT NULL, " +
                        "sha256 TEXT NOT NULL, " +
                        "totalChunks INTEGER NOT NULL, " +
                        "state INTEGER NOT NULL, " +
                        "localPath TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS attachment_chunks (" +
                        "fid TEXT NOT NULL, " +
                        "idx INTEGER NOT NULL, " +
                        "data BLOB NOT NULL, " +
                        "PRIMARY KEY(fid, idx))"
                )
            }
        }

        /**
         * v6 → v7 (v0.2.19) : suppression unitaire + réplica temporaire embarqué.
         * MIGRATION ADDITIVE — aucune donnée détruite : trois tables NEUVES
         * (del_pending vide, replica_events vide hors test 2, replica_state vide).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS del_pending (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "peer TEXT NOT NULL, " +
                        "ids TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS replica_events (" +
                        "eventId TEXT NOT NULL PRIMARY KEY, " +
                        "gid INTEGER NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "author TEXT NOT NULL, " +
                        "seq INTEGER NOT NULL, " +
                        "ts INTEGER NOT NULL, " +
                        "ttl INTEGER NOT NULL, " +
                        "`to` TEXT NOT NULL, " +
                        "body TEXT NOT NULL, " +
                        "sig TEXT NOT NULL, " +
                        "bytes INTEGER NOT NULL, " +
                        "insertedAt INTEGER NOT NULL)"
                )
                db.execSQL(SQL_INDEX_REPLICA_EVENTS_TO_GID)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS replica_state (" +
                        "id INTEGER NOT NULL PRIMARY KEY, " +
                        "lastPurgeAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v7 → v8 (v0.2.22) : réparation du schéma replica_events — crash
         * terrain « Migration didn't properly handle: replica_events »
         * (Blackview, montée v0.2.18 → v0.2.21, FATAL en boucle). La
         * MIGRATION_6_7 (v0.2.19) crée l'index inbox [INDEX_REPLICA_EVENTS_TO_GID]
         * que l'entité ne déclarait PAS : les bases passées par la migration
         * sont rejetées par la validation Room (transaction onUpgrade annulée
         * à chaque ouverture — l'appareil reste bloqué sur l'ancienne version).
         * Correctif CONVERGENT, zéro donnée détruite : l'entité déclare
         * désormais l'index (schéma attendu = schéma migré) et cette migration
         * recrée l'index IF NOT EXISTS pour les bases d'installation FRAÎCHE
         * v7 (jamais migrées, donc sans index) — no-op pour les bases migrées
         * (index déjà présent). Tous les chemins convergent vers le même
         * schéma : migrés 6→7→8, fraîches v7→8, fraîches v8 (création Room).
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(SQL_INDEX_REPLICA_EVENTS_TO_GID)
            }
        }

        /** v0.2.26 — MIGRATION_8_9 : colonne éphémère messages.expireAt. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN expireAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v0.2.28 — MIGRATION_9_10 : politique d'affichage des médias
         * (demande opérateur : « afficher le média 1 fois puis destruction,
         * relecture autorisée 1 fois, ou affichage permanent »).
         * viewPolicy : "forever" (défaut, rétrocompatible) | "once" | "twice" ;
         * viewsLeft  : -1 = illimité (forever), sinon vues restantes.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachments ADD COLUMN viewPolicy TEXT NOT NULL DEFAULT 'forever'")
                db.execSQL("ALTER TABLE attachments ADD COLUMN viewsLeft INTEGER NOT NULL DEFAULT -1")
            }
        }

        /**
         * v0.2.29 — MIGRATION_10_11 : BLOCAGE LOCAL des contacts (demande
         * opérateur : « la possibilité de bloquer/débloquer un contact, et un
         * onglet pour gérer la liste de blocages »). Colonne contacts.blockedAt
         * (horodatage du blocage, 0 = non bloqué) — MIGRATION ADDITIVE, aucune
         * donnée détruite : les contacts existants ressortent non bloqués.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN blockedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN notifiedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE contacts SET onion = LOWER(TRIM(onion)) " +
                        "WHERE onion != LOWER(TRIM(onion))"
                )
                db.execSQL(
                    "UPDATE messages SET peerOnion = LOWER(TRIM(peerOnion)) " +
                        "WHERE peerOnion != LOWER(TRIM(peerOnion))"
                )
            }
        }
    }
}
