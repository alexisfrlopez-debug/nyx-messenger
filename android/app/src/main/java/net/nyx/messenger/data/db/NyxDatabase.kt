package net.nyx.messenger.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/* ============================ Entités ============================ */

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val onion: String,       // adresse onion du contact (= clé publique)
    val alias: String,                   // nom local (jamais transmis)
    val addedAt: Long
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

/* ============================ DAOs ============================ */

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY alias")
    fun all(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE onion = :onion")
    suspend fun get(onion: String): ContactEntity?
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

/* ============================ Base ============================ */

/**
 * Base locale — chiffrée au repos via SQLCipher (clé de base dérivée du Keystore,
 * cf. ServiceLocator). Les messages en clair n'existent QUE dans cette base scellée.
 */
@Database(
    entities = [ContactEntity::class, MessageEntity::class, PollStateEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NyxDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun pollStateDao(): PollStateDao

    companion object {
        const val NAME = "nyx.db"
    }
}
