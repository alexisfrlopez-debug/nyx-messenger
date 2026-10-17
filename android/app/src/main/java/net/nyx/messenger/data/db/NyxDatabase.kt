package net.nyx.messenger.data.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
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
    @ColumnInfo(defaultValue = "0") val ackPending: Int = 0
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
    @ColumnInfo(defaultValue = "") val fid: String = ""
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
    val createdAt: Long
)

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

    /** v0.2.14 : contacts ACTIFS (non archivés) — liste principale. */
    @Query("SELECT * FROM contacts WHERE status NOT IN ('declined', 'removed', 'removed_by_me') ORDER BY alias")
    fun allActive(): Flow<List<ContactEntity>>

    /** v0.2.14 : contacts archivés (refusés/supprimés) — anciennes conversations. */
    @Query("SELECT * FROM contacts WHERE status IN ('declined', 'removed', 'removed_by_me') ORDER BY alias")
    fun allArchived(): Flow<List<ContactEntity>>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerOnion = :peer ORDER BY sentAt ASC")
    fun conversation(peer: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

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
}

/** Projection du compteur de non-lus par pair (v0.2.15). */
data class UnreadCount(val peer: String, val cnt: Int)

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(a: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE fid = :fid")
    suspend fun get(fid: String): AttachmentEntity?

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

@Dao
interface PollStateDao {
    @Query("SELECT * FROM poll_state WHERE replicaOnion = :replica")
    suspend fun get(replica: String): PollStateEntity?

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
        PollStateEntity::class, SeqStateEntity::class],
    version = 6,
    exportSchema = false
)
abstract class NyxDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun peerProfileDao(): PeerProfileDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun pollStateDao(): PollStateDao
    abstract fun seqStateDao(): SeqStateDao

    companion object {
        const val NAME = "nyx.db"

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
