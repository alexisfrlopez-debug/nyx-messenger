package net.nyx.messenger.diag

import net.nyx.messenger.desktop.DesktopLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v0.3.4 — tests du plan de tests de validation desktop (portage miroir
 * diag/TestPlan.kt Android v0.2.20) : items stables, ids uniques,
 * persistance round-trip, compteur, réinitialisation, journalisation nyx-test.
 */
class TestPlanDesktopTest {

    @Test
    fun `items v0_3_4 non vides et ids uniques`() {
        val items = TestPlan.items()
        assertTrue(items.isNotEmpty(), "la liste de validation ne doit jamais être vide")
        assertEquals(items.size, items.map { it.id }.toSet().size, "ids dupliqués")
        assertTrue(items.all { it.label.length > 10 }, "labels trop courts (illisibles)")
    }

    @Test
    fun `setDone puis doneAt et doneCount persistes`() {
        val dir = Files.createTempDirectory("nyx-testplan").toFile()
        TestPlan.init(dir)
        try {
            val id = TestPlan.items().first().id
            assertEquals(0, TestPlan.doneCount())
            TestPlan.setDone(id, true)
            assertTrue(TestPlan.doneAt(id) > 0L, "horodatage manquant")
            assertEquals(1, TestPlan.doneCount())
            // Round-trip persistance : une NOUVELLE instance relit le fichier.
            TestPlan.init(dir)
            assertTrue(TestPlan.doneAt(id) > 0L, "marque perdue après ré-init (persistance cassée)")
            assertEquals(1, TestPlan.doneCount())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reset vide toutes les marques`() {
        val dir = Files.createTempDirectory("nyx-testplan").toFile()
        TestPlan.init(dir)
        try {
            val id = TestPlan.items().first().id
            TestPlan.setDone(id, true)
            TestPlan.reset()
            assertEquals(0, TestPlan.doneCount())
            assertEquals(0L, TestPlan.doneAt(id))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `journalisation nyx-test PASS depuis le journal memoire`() {
        val dir = Files.createTempDirectory("nyx-testplan").toFile()
        TestPlan.init(dir)
        try {
            val id = TestPlan.items().first().id
            TestPlan.setDone(id, true)
            val tail = DesktopLog.dump()
            assertNotNull(
                tail.lineSequence().firstOrNull { it.contains("nyx-test") && it.contains("PASS") && it.contains(id) },
                "la ligne nyx-test PASS doit vivre dans le journal (part dans l'export de logs)"
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
