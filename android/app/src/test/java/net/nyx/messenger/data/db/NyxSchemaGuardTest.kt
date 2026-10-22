package net.nyx.messenger.data.db

import androidx.room.RoomDatabase
import androidx.room.RoomOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * v0.2.22 — Test de GARDE du schéma Room, né d'un crash terrain
 * (« Migration didn't properly handle: replica_events », Blackview, montée
 * v0.2.18 → v0.2.21, FATAL en boucle).
 *
 * CAUSE : la MIGRATION_6_7 (v0.2.19) créait l'index inbox
 * index_replica_events_to_gid ALORS QUE l'entité ReplicaEventEntity ne le
 * déclarait pas. Toute base passée par la migration porte donc un index que
 * la validation Room rejette — et la transaction onUpgrade étant annulée à
 * chaque ouverture, l'appareil restait bloqué (crash-loop) jusqu'à un APK
 * réparé. Les installations fraîches (tables créées par Room depuis les
 * entités) étaient sans index — d'où deux populations divergentes.
 *
 * CORRECTIF v0.2.22 : l'entité déclare l'index (nom explicite = nom canonique
 * Room index_<table>_<colonnes>) et MIGRATION_7_8 le recrée IF NOT EXISTS
 * pour les bases fraîches v7. Tous les chemins convergent.
 *
 * MÉTHODE : les annotations Room sont en rétention CLASS (invisibles par
 * réflexion JVM) — on capte donc le VRAI code généré KSP :
 *  - le DDL de création fraîche (Delegate anonyme de NyxDatabase_Impl —
 *    Room ne produit un « CREATE INDEX » que pour un index DÉCLARÉ dans
 *    l'entité : si l'index disparaît de l'entité, ces tests échouent) ;
 *  - la version de la base (Delegate construit par createOpenHelper).
 * Un écart entité/migration devient ainsi impossible sans échec de build.
 */
class NyxSchemaGuardTest {

    /* ---------- DDL réel de Room (entités → SQL) ---------- */

    /**
     * Capture le DDL du Delegate généré. NB : Class.getDeclaredClasses()
     * renvoie VIDE au runtime de test (la classe anonyme ne figure pas dans
     * les attributs InnerClasses de la classe chargée) — on scanne donc les
     * noms \$1..\$9 (convention javac : le Delegate est la première classe
     * anonyme du fichier généré) et on retient celle qui étend
     * RoomOpenHelper.Delegate. La version passée au constructeur synthétique
     * ne change pas le DDL (elle n'est utilisée que par [delegateVersion]).
     */
    private fun runDelegateCapture(
        captureVersion: Boolean
    ): Pair<Int?, List<String>> {
        val executed = mutableListOf<String>()
        val handler = InvocationHandler { _, method, args ->
            when {
                method.name == "execSQL" && args != null && args.isNotEmpty() ->
                    executed.add(args[0] as String)
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == java.lang.Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        val fake = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler
        ) as SupportSQLiteDatabase

        val implCls = Class.forName("net.nyx.messenger.data.db.NyxDatabase_Impl")
        val delegateCls = (1..9).asSequence()
            .map {
                runCatching {
                    Class.forName("net.nyx.messenger.data.db.NyxDatabase_Impl\$$it")
                }.getOrNull()
            }
            .filterNotNull()
            .firstOrNull { it.superclass == RoomOpenHelper.Delegate::class.java }
        assertNotNull(
            "Delegate anonyme introuvable (scan \$1..\$9, superclass attendue " +
                "androidx.room.RoomOpenHelper\$Delegate)", delegateCls
        )
        val dCls = delegateCls!!
        // Classe anonyme NON statique : ctor = (NyxDatabase_Impl, int version).
        // La valeur version passée ici ne change pas le DDL (elle ne sert que
        // via [delegateVersion], qui rejoue createOpenHelper).
        val implInstance = implCls.getDeclaredConstructor().newInstance()
        val delegate = dCls.declaredConstructors.first { c -> c.parameterCount >= 1 }
            .apply { isAccessible = true }
            .let { c ->
                c.newInstance(*c.parameterTypes.map { t ->
                    if (t.isAssignableFrom(implCls)) implInstance
                    else if (t == java.lang.Integer.TYPE || t == java.lang.Long.TYPE) 0
                    else null
                }.toTypedArray())
            }
        dCls.methods.first { it.name == "createAllTables" }.invoke(delegate, fake)
        if (!captureVersion) return null to executed

        // Version RÉELLE : le Delegate portant la version est celui construit
        // PAR createOpenHelper (le paramètre du constructeur synthétique est
        // fourni par le site d'appel). On rejoue createOpenHelper avec une
        // configuration factice qui capte le RoomOpenHelper.
        // ContextWrapper (concret, stub android.jar, ctor sans vérification)
        // sert de Context factice : le Builder de Configuration exige un
        // non-null, mais RIEN de ce flux n'appelle de méthode du Context.
        val deadContext = android.content.ContextWrapper(null as android.content.Context?)

        var captured: Any? = null
        val fakeHelper = Proxy.newProxyInstance(
            SupportSQLiteOpenHelper::class.java.classLoader,
            arrayOf(SupportSQLiteOpenHelper::class.java),
            InvocationHandler { _, m, _ ->
                when {
                    m.returnType == java.lang.Boolean.TYPE -> false
                    m.returnType == java.lang.Integer.TYPE -> 0
                    m.returnType == java.lang.Long.TYPE -> 0L
                    else -> null
                }
            })
        val factory = Proxy.newProxyInstance(
            SupportSQLiteOpenHelper.Factory::class.java.classLoader,
            arrayOf(SupportSQLiteOpenHelper.Factory::class.java),
            InvocationHandler { _, method, args ->
                when {
                    method.name == "create" -> { captured = args?.get(0); fakeHelper }
                    method.returnType == java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }) as SupportSQLiteOpenHelper.Factory

        // DatabaseConfiguration : on choisit le constructeur public au plus
        // grand nombre de paramètres et on remplit CHAQUE paramètre selon son
        // type (l'ordre varie entre surcharges Kotlin).
        val cfgCls = Class.forName("androidx.room.DatabaseConfiguration")
        val ctor = cfgCls.constructors.maxByOrNull { it.parameterCount }!!
        val ctorArgs = ctor.parameterTypes.map { t ->
            when {
                t == android.content.Context::class.java -> deadContext
                t == String::class.java -> null
                SupportSQLiteOpenHelper.Factory::class.java.isAssignableFrom(t) -> factory
                t.name == "androidx.room.RoomDatabase\$MigrationContainer" ->
                    t.getDeclaredConstructor().newInstance()
                t == java.util.List::class.java -> emptyList<Any>()
                t == java.util.Set::class.java -> emptySet<Any>()
                t == java.util.Map::class.java -> emptyMap<Any, Any>()
                t == java.lang.Boolean.TYPE -> false
                t == java.lang.Integer.TYPE -> 0
                t == java.lang.Long.TYPE -> 0L
                t == java.lang.Float.TYPE -> 0f
                t == java.lang.Double.TYPE -> 0.0
                t.isEnum -> t.enumConstants[0]
                java.util.concurrent.Executor::class.java.isAssignableFrom(t) ->
                    java.util.concurrent.Executor { it.run() }
                else -> null
            }
        }
        val configInstance = ctor.newInstance(*ctorArgs.toTypedArray())

        // createOpenHelper(config) : c'est LUI qui construit le Delegate avec
        // la version réelle (constante au site d'appel du code généré).
        val impl = implCls.getDeclaredConstructor().newInstance()
        implCls.declaredMethods.first { it.name == "createOpenHelper" }
            .apply { isAccessible = true }
            .invoke(impl, configInstance)

        assertNotNull("Configuration non capturée par la factory factice", captured)
        val cfg = captured!!
        val callback = cfg.javaClass.declaredFields
            .firstOrNull { it.type.isAssignableFrom(RoomOpenHelper::class.java) ||
                it.name == "callback" }
            ?.apply { isAccessible = true }?.get(cfg)
            ?: error("champ callback introuvable sur ${cfg.javaClass.name} " +
                "(champs : ${cfg.javaClass.declaredFields.joinToString { it.name }})")
        val delegate2 = RoomOpenHelper::class.java
            .getDeclaredField("delegate").apply { isAccessible = true }.get(callback)
        return RoomOpenHelper.Delegate::class.java.getField("version")
            .getInt(delegate2) to executed
    }

    /** DDL complet d'une base FRAÎCHE (création Room depuis les entités). */
    private fun freshDatabaseDdl(): List<String> = runDelegateCapture(false).second

    /* ---------- Version de la base ---------- */

    @Test
    fun `la base est en version 11`() {
        assertEquals(
            "version de la base (Delegate Room généré) — une montée de version " +
                "SANS migration correspondante casse TOUS les appareils installés " +
                "(v11 v0.2.29 : contacts.blockedAt — blocage local, cf. MIGRATION_10_11)",
            11, runDelegateCapture(true).first
        )
    }

    /* ---------- v0.2.29 : colonne de blocage ---------- */

    /** Le DDL fraîche porte la colonne blockedAt (contacts) — sinon la
     *  validation Room rejetterait toute base fraîche dès la 1ʳᵉ ouverture. */
    @Test
    fun `le DDL Room fraîche contient contacts blockedAt`() {
        val ddl = freshDatabaseDdl()
        val contacts = ddl.single { it.contains("CREATE TABLE") && it.contains("`contacts`") }
        assertTrue("contacts : colonne blockedAt absente du DDL fraîche", contacts.contains("blockedAt"))
    }

    @Test
    fun `MIGRATION_10_11 couvre bien 10 vers 11`() {
        assertEquals(10, NyxDatabase.MIGRATION_10_11.startVersion)
        assertEquals(11, NyxDatabase.MIGRATION_10_11.endVersion)
    }

    /* ---------- L'entité déclare l'index (le DDL Room le prouve) ---------- */

    @Test
    fun `le DDL Room fraîche contient l'index inbox de replica_events`() {
        val ddl = freshDatabaseDdl()
        val indexDdl = ddl.filter { it.trimStart().startsWith("CREATE INDEX") }
        assertEquals(
            "la base ne doit déclarer EXACTEMENT un index (l'index inbox) — " +
                "s'il disparaît ici, l'entité ne le déclare plus et les bases " +
                "migrées crashent (« Migration didn't properly handle »)",
            1, indexDdl.size
        )
        val sql = indexDdl.single()
        assertTrue("l'index doit porter le nom canonique : $sql",
            sql.contains(NyxDatabase.INDEX_REPLICA_EVENTS_TO_GID))
        assertTrue("l'index doit porter sur replica_events : $sql",
            sql.contains("replica_events"))
        assertTrue("IF NOT EXISTS attendu (idempotence) : $sql",
            sql.contains("IF NOT EXISTS"))
    }

    /* ---------- Convergence migration ↔ entité ---------- */

    @Test
    fun `l'index créé par les migrations est le même que celui de l'entité`() {
        val ddl = freshDatabaseDdl()
        val indexDdl = ddl.filter { it.trimStart().startsWith("CREATE INDEX") }.single()
        // Même nom : la validation Room compare le nom exact.
        assertTrue(indexDdl.contains(NyxDatabase.INDEX_REPLICA_EVENTS_TO_GID))
        // Le SQL des migrations ne crée AUCUN index sous un autre nom.
        val migrationSql = NyxDatabase.SQL_INDEX_REPLICA_EVENTS_TO_GID
        assertTrue(migrationSql.contains(NyxDatabase.INDEX_REPLICA_EVENTS_TO_GID))
    }

    /* ---------- Tables v7 : les 3 tables restent complètes ---------- */

    /**
     * Après replica_events, la validation Room contrôle del_pending puis
     * replica_state — un écart là-dessus serait le crash suivant. NB : Room
     * backtick-quote TOUS les identifiants du DDL généré — les prédicats ne
     * doivent donc pas supposer « CREATE TABLE IF NOT EXISTS del_pending »
     * sans backticks.
     */
    @Test
    fun `les tables v7 sont complètes dans le DDL Room`() {
        val ddl = freshDatabaseDdl()
        val delPending = ddl.single { it.contains("CREATE TABLE") && it.contains("del_pending") }
        for (col in listOf("id", "peer", "ids", "createdAt")) {
            assertTrue("del_pending : colonne $col absente", delPending.contains(col))
        }
        assertTrue("del_pending : PK autoincrément attendue", delPending.contains("AUTOINCREMENT"))

        val replicaState = ddl.single { it.contains("CREATE TABLE") && it.contains("replica_state") }
        for (col in listOf("id", "lastPurgeAt")) {
            assertTrue("replica_state : colonne $col absente", replicaState.contains(col))
        }

        val replicaEvents = ddl.single { it.contains("CREATE TABLE") && it.contains("replica_events") }
        for (col in listOf("eventId", "gid", "type", "author", "seq", "ts", "ttl",
                "body", "sig", "bytes", "insertedAt")) {
            assertTrue("replica_events : colonne $col absente", replicaEvents.contains(col))
        }
        // to est un mot-clé SQL : Room le backtick-quote — le token `to` cité.
        assertTrue("replica_events : colonne to absente", replicaEvents.contains("`to`"))
        assertTrue("replica_events : PK eventId attendue",
            replicaEvents.contains("PRIMARY KEY"))
    }

    /* ---------- SQL partagé & nom canonique ---------- */

    @Test
    fun `le nom de l'index inbox est le nom canonique Room`() {
        // Room nomme un index déclaré sans nom : index_<table>_<col1>_<col2>.
        val canonical = listOf("index", "replica_events", "to", "gid").joinToString("_")
        assertEquals(canonical, NyxDatabase.INDEX_REPLICA_EVENTS_TO_GID)
        assertEquals("index_replica_events_to_gid", NyxDatabase.INDEX_REPLICA_EVENTS_TO_GID)
    }

    @Test
    fun `le SQL de l'index recrée exactement l'index déclaré par l'entité`() {
        val sql = NyxDatabase.SQL_INDEX_REPLICA_EVENTS_TO_GID
        assertTrue("SQL : CREATE INDEX IF NOT EXISTS attendu",
            sql.startsWith("CREATE INDEX IF NOT EXISTS "))
        assertTrue("SQL : nom d'index attendu (${NyxDatabase.INDEX_REPLICA_EVENTS_TO_GID})",
            sql.contains(NyxDatabase.INDEX_REPLICA_EVENTS_TO_GID))
        assertTrue("SQL : table replica_events attendue", sql.contains("replica_events"))
        // La DDL ne doit JAMAIS porter un nom d'index différent de l'entité :
        // c'est exactement la divergence qui a causé le crash terrain.
        Regex("""CREATE INDEX (IF NOT EXISTS )?(\w+)""").find(sql)!!.let { m ->
            assertEquals(NyxDatabase.INDEX_REPLICA_EVENTS_TO_GID, m.groupValues[2])
        }
    }

    /* ---------- Migrations ---------- */

    @Test
    fun `MIGRATION_7_8 couvre bien 7 vers 8`() {
        assertEquals(7, NyxDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, NyxDatabase.MIGRATION_7_8.endVersion)
    }

    @Test
    fun `MIGRATION_6_7 couvre bien 6 vers 7`() {
        assertEquals(6, NyxDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, NyxDatabase.MIGRATION_6_7.endVersion)
    }
}
