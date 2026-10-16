package net.nyx.messenger.di

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                .addMigrations(NyxDatabase.MIGRATION_1_2, NyxDatabase.MIGRATION_2_3)
                .build()

            // Repository unique partagé par tous les écrans (supprime la construction
            // redondante dans Onboarding/ConversationList/Chat — audit cycle 1).
            repository = NyxRepository(
                identity, cipher,
                database.messageDao(), database.contactDao(), database.pollStateDao(),
                database.seqStateDao()
            )

            startInboxPolling()

            initialized = true
        }
    }

    /**
     * v0.2.10 — POLL GLOBAL DE L'INBOX (30 s) — correctif de LIVRAISON.
     * Jusqu'ici le poll ne vivait QUE dans l'écran de conversation : un appareil
     * sans conversation ouverte (ex. destinataire d'une première demande de
     * contact) ne récupérait JAMAIS ses événements — messages et annonces de
     * contact n'arrivaient jamais. La boucle vit au niveau application :
     *  - tourne dès le lancement (no-op tant que Tor/l'identité ne sont pas
     *    prêts — pollInbox renvoie 0) ;
     *  - s'ajoute au poll fin (10 s) de l'écran de conversation ouvert ;
     *  - fonctionne au premier plan — aucune synchro en arrière-plan v0.1
     *    (process tué = pas de poll, cf. RESTART.md décision 17).
     */
    private fun startInboxPolling() {
        val handler = CoroutineExceptionHandler { _, e ->
            android.util.Log.w("nyx-poll", "boucle inbox interrompue : ${e.message}")
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + handler).launch {
            while (true) {
                runCatching { repository.pollInbox() }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private const val POLL_INTERVAL_MS = 30_000L

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
