// Tests JVM du verrou d'accès (ADR-010 §3 + exception v0.2.1 « flux système »).
// AppLock est un object pur Kotlin (aucune API Android) — testable tel quel.
package net.nyx.messenger.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLockTest {

    @Before
    fun reset() {
        // État propre pour chaque test (object = singleton de test).
        AppLock.init(false)
        AppLock.unlock()
    }

    @Test
    fun `verrou à froid si l'opt-in est actif`() {
        AppLock.init(true)
        assertTrue(AppLock.locked.value)
    }

    @Test
    fun `pas de verrou sans opt-in`() {
        AppLock.init(false)
        assertFalse(AppLock.locked.value)
    }

    @Test
    fun `passage en arrière-plan re-verrouille`() {
        AppLock.init(true)
        AppLock.unlock()
        AppLock.onBackgrounded()
        assertTrue(AppLock.locked.value)
    }

    @Test
    fun `suspension - le prochain arrière-plan ne verrouille pas`() {
        AppLock.init(true)
        AppLock.unlock()
        AppLock.suspendNextBackground()
        AppLock.onBackgrounded() // picker système ouvert — attendu
        assertFalse("le flux système (picker) ne doit pas verrouiller", AppLock.locked.value)
    }

    @Test
    fun `suspension consommée une seule fois`() {
        AppLock.init(true)
        AppLock.unlock()
        AppLock.suspendNextBackground()
        AppLock.onBackgrounded() // consommée
        AppLock.onBackgrounded() // vrai passage en arrière-plan
        assertTrue("après consommation, l'arrière-plan re-verrouille", AppLock.locked.value)
    }

    @Test
    fun `suspension sans opt-in puis activation - pas de fuite du drapeau`() {
        AppLock.init(false)
        AppLock.suspendNextBackground()
        AppLock.enable(sessionActive = true) // reset du drapeau
        AppLock.onBackgrounded()
        assertTrue("enable() purge la suspension : l'arrière-plan re-verrouille", AppLock.locked.value)
    }

    @Test
    fun `déverrouillage puis re-verrouillage cyclique`() {
        AppLock.init(true)
        AppLock.unlock()
        assertFalse(AppLock.locked.value)
        AppLock.onBackgrounded()
        assertTrue(AppLock.locked.value)
        AppLock.unlock()
        assertFalse(AppLock.locked.value)
    }
}
