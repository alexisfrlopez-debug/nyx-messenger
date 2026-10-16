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

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val onion: String,       // adresse onion du contact (= clé publique)
    val alias: String,                   // nom local (jamais transmis)
    val addedAt: Long,
    // v0.2.10 : 1 = la demande de contact (événement « contact » chiffré) n'a pas
    // encore pu être déposée sur le réplica (injoignable à l'ajout) — elle repart
    // dès la prochaine entrée sur la liste ([NyxRepository.ensureContactNotified]).
    @ColumnInfo(defaultValue = "0") val notifyPending: Int = 0
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val eventId: String,     // sha256 de la représentation canonique
    val peerOnion: String,               // interlocuteur
    val outgoing: Boolean,               // true = envoyé par nous
    val text: String,                    // déchiffré (jamais synchronisé)
    val sentAt: Long,                    // horodatage déclaré
    val receivedAt: Long,
    val state: Int                       // 0=pending, 1=sent, 2=failed, 3=received
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

    @Query("SELECT * FROM contacts WHERE notifyPending = 1")
    suspend fun pendingNotify(): List<ContactEntity>

    @Query("UPDATE contacts SET notifyPending = :pending WHERE onion = :onion")
    suspend fun setNotifyPending(onion: String, pending: Int)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerOnion = :peer ORDER BY sentAt ASC")
    fun conversation(peer: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET state = :state WHERE eventId = :eventId")
    suspend fun updateState(eventId: String, state: Int)
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
    entities = [ContactEntity::class, MessageEntity::class, PollStateEntity::class, SeqStateEntity::class],
    version = 2,
    exportSchema = false
)
abstract class NyxDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
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
    }
}
