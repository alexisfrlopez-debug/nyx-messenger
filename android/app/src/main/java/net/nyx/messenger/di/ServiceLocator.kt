package net.nyx.messenger.di

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import net.nyx.messenger.crypto.IdentityManager
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.data.db.NyxDatabase
import net.nyx.messenger.repo.NyxRepository
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * Localisateur de services — DI manuelle (volontairement simple à ce stade).
 * La clé SQLCipher est un secret aléatoire scellé par le Keystore : la base
 * n'est lisible que sur cet appareil déverrouillé.
 */
object ServiceLocator {

    lateinit var sodium: LazySodiumAndroid
        private set
    lateinit var cipher: MessageCipher
        private set
    lateinit var identity: IdentityManager
        private set
    lateinit var database: NyxDatabase
        private set
    lateinit var repository: NyxRepository
        private set

    @Volatile var initialized = false; private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            sodium = LazySodiumAndroid(SodiumAndroid())
            cipher = MessageCipher(sodium)
            identity = IdentityManager.get(context)

            SQLiteDatabase.loadLibs(context)
            val dbPassphrase = dbPassphrase(context)
            database = Room.databaseBuilder(context, NyxDatabase::class.java, NyxDatabase.NAME)
                .openHelperFactory(SupportFactory(dbPassphrase, null, false))
                .build()

            // Repository unique partagé par tous les écrans (supprime la construction
            // redondante dans Onboarding/ConversationList/Chat — audit cycle 1).
            repository = NyxRepository(
                identity, cipher,
                database.messageDao(), database.contactDao(), database.pollStateDao()
            )

            initialized = true
        }
    }

    /** Clé de base SQLCipher : 32 octets aléatoires, scellés via KeystoreBox dès le 1er lancement. */
    private fun dbPassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("nyx_dbkey", Context.MODE_PRIVATE)
        val stored = prefs.getString("sealed", null)
        return if (stored != null) {
            net.nyx.messenger.crypto.KeystoreBox.open(Base64.decode(stored, Base64.DEFAULT))
        } else {
            val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            prefs.edit().putString(
                "sealed",
                Base64.encodeToString(net.nyx.messenger.crypto.KeystoreBox.seal(fresh), Base64.DEFAULT)
            ).apply()
            fresh
        }
    }
}
