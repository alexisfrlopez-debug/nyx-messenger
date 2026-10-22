package net.nyx.messenger.net

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.2.25 — registre des réplicas découverts : ingestion, persistance (fake
 * SharedPreferences via Proxy — même approche minimaliste que le projet),
 * TTL 7 jours, tri par annonce la plus récente, garde-fou de format.
 */
class ReplicaRegistryTest {

    private val onionA = "a".repeat(56) + ".onion:80"
    private val onionB = "b".repeat(56) + ".onion:80"

    @Before
    fun reset() {
        // Réinitialise le singleton entre les tests (champ privé prefs/entries).
        val f = ReplicaRegistry::class.java.getDeclaredField("prefs")
        f.isAccessible = true
        f.set(ReplicaRegistry, null)
        val e = ReplicaRegistry::class.java.getDeclaredField("entries")
        e.isAccessible = true
        (e.get(ReplicaRegistry) as LinkedHashMap<*, *>).clear()
    }

    /** Fake SharedPreferences (HashMap + Proxy — suffit pour getString/putString). */
    private fun fakePrefs(): SharedPreferences {
        val store = ConcurrentHashMap<String, Any>()
        val editorRef = java.util.concurrent.atomic.AtomicReference<SharedPreferences.Editor>()
        val editor = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { _, m2, a2 ->
            when (m2.name) {
                "putString" -> { store[a2?.get(0) as String] = a2?.get(1)!!; editorRef.get() }
                "putBoolean", "putInt", "putLong", "remove", "clear" -> editorRef.get()
                else -> null
            }
        } as SharedPreferences.Editor
        editorRef.set(editor)
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> store[args?.get(0) as String] as? String
                "edit" -> editor
                else -> when (method.returnType.name) {
                    "boolean" -> false
                    "int" -> 0
                    else -> null
                }
            }
        } as SharedPreferences
    }

    @Test
    fun `ingestion et liste triée par récence`() {
        ReplicaRegistry.init(fakePrefs())
        assertTrue(ReplicaRegistry.ingest(onionA, 1000L))
        assertTrue(ReplicaRegistry.ingest(onionB, 2000L))
        // Re-ingestion A (plus récente) → A en tête.
        assertFalse(ReplicaRegistry.ingest(onionA, 3000L)) // maj, pas « nouvelle »
        val list = ReplicaRegistry.list(now = 4000L)
        assertEquals(listOf(onionA, onionB), list)
    }

    @Test
    fun `TTL 7 jours — entrée périmée retirée`() {
        ReplicaRegistry.init(fakePrefs())
        val ttl = 7L * 24 * 3600 * 1000
        // 1. Entrée fraîche → visible ; périmée au-delà du TTL.
        ReplicaRegistry.ingest(onionA, 0L)
        assertTrue(ReplicaRegistry.list(now = ttl - 1).contains(onionA))
        assertTrue(ReplicaRegistry.list(now = ttl + 1).isEmpty())
        // 2. L'épuration est à la volée : re-annonce → re-visible.
        ReplicaRegistry.ingest(onionA, ttl)
        assertTrue(ReplicaRegistry.list(now = 2 * ttl).contains(onionA))
    }

    @Test
    fun `adresse invalide — rejet`() {
        ReplicaRegistry.init(fakePrefs())
        assertFalse(ReplicaRegistry.ingest("garbage", 0L))
        assertFalse(ReplicaRegistry.ingest("b".repeat(10) + ".onion", 0L))
        assertTrue(ReplicaRegistry.list().isEmpty())
    }

    @Test
    fun `persistance — réinitialisation recharge depuis prefs`() {
        val prefs = fakePrefs()
        ReplicaRegistry.init(prefs)
        ReplicaRegistry.ingest(onionA, 1234L)
        // Nouveau process : réinit sur le MÊME prefs → la liste revient.
        reset()
        ReplicaRegistry.init(prefs)
        assertEquals(listOf(onionA), ReplicaRegistry.list(now = 2000L))
    }

    @Test
    fun `clear vide tout`() {
        ReplicaRegistry.init(fakePrefs())
        ReplicaRegistry.ingest(onionA, 0L)
        ReplicaRegistry.clear()
        assertTrue(ReplicaRegistry.list().isEmpty())
    }
}
