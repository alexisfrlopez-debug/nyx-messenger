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
 * v0.3.14 — VALIDATION PAR LOGS : les items prouvés par l'export terrain du
 * 29/10 (logValidated != null) comptent comme PASS SANS marque opérateur —
 * [baseValidated] sert de plancher aux assertions de compteur.
 */
class TestPlanDesktopTest {

    /** Items prouvés par logs (comptent sans marque opérateur). */
    private fun baseValidated(): Int = TestPlan.items().count { it.logValidated != null }

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
            val id = TestPlan.items().first { it.logValidated == null }.id // v0.3.14 — item NON déjà prouvé par logs
            assertEquals(baseValidated(), TestPlan.doneCount(), "compteur initial = items validés par logs seuls")
            TestPlan.setDone(id, true)
            assertTrue(TestPlan.doneAt(id) > 0L, "horodatage manquant")
            assertEquals(baseValidated() + 1, TestPlan.doneCount())
            // Round-trip persistance : une NOUVELLE instance relit le fichier.
            TestPlan.init(dir)
            assertTrue(TestPlan.doneAt(id) > 0L, "marque perdue après ré-init (persistance cassée)")
            assertEquals(baseValidated() + 1, TestPlan.doneCount())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reset vide toutes les marques`() {
        val dir = Files.createTempDirectory("nyx-testplan").toFile()
        TestPlan.init(dir)
        try {
            val id = TestPlan.items().first { it.logValidated == null }.id // v0.3.14 — idem
            TestPlan.setDone(id, true)
            TestPlan.reset()
            assertEquals(baseValidated(), TestPlan.doneCount(), "reset = items validés par logs seuls")
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
            val id = TestPlan.items().first { it.logValidated == null }.id
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

    @Test
    fun `validations par logs preuves journalisees et jamais marquees`() {
        val dir = Files.createTempDirectory("nyx-testplan").toFile()
        TestPlan.init(dir)
        try {
            val validated = TestPlan.items().filter { it.logValidated != null }
            assertTrue(validated.isNotEmpty(), "des items doivent être prouvés par l'export du 29/10")
            // Une preuve émet SA ligne PASS au même titre qu'un tap opérateur…
            TestPlan.logValidatedProofs()
            val tail = DesktopLog.dump()
            for (it in validated) {
                assertNotNull(
                    tail.lineSequence().firstOrNull { l -> l.contains("nyx-test") && l.contains("PASS ${it.id}") },
                    "la preuve de ${it.id} doit vivre dans le journal"
                )
            }
            // …mais ne POSE AUCUNE marque : reset() ne les efface pas (ce sont
            // des FAITS d'export, pas des taps).
            TestPlan.reset()
            assertEquals(validated.size, TestPlan.doneCount(), "les preuves survivent au reset")
        } finally {
            dir.deleteRecursively()
        }
    }
}
