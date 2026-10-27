package net.nyx.messenger.repo

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * v0.2.52 — tests JVM de la resynchronisation de séquence ([SeqResync]) :
 * la panne terrain 25/10 05:35:02 (dépôt au pair par LIEN refusé 400 « seq
 * en attente », événement PERDU) exige de relire le seq attendu dans le
 * corps du 400 — exactement comme la voie directe.
 */
class SeqResyncTest {

    @Test
    fun `refus en attente rend le seq attendu`() {
        // Corps réel du 400 du réplica embarqué : « seq 42 en attente (attendu 41) ».
        assertEquals(41L, SeqResync.expectedFrom("seq 42 en attente (attendu 41)"))
    }

    @Test
    fun `refus dépassée rend dernière + 1`() {
        assertEquals(41L, SeqResync.expectedFrom("seq 40 déjà dépassée (dernière 40)"))
    }

    @Test
    fun `json d'erreur du réplica est lu`() {
        // Le repli lien reçoit le corps brut : {"error":"seq 7 en attente (attendu 6)"}
        assertEquals(
            6L,
            SeqResync.expectedFrom("{\"error\":\"seq 7 en attente (attendu 6)\"}")
        )
    }

    @Test
    fun `erreur réseau sans seq rend null`() {
        assertNull(SeqResync.expectedFrom("Connect timed out"))
        assertNull(SeqResync.expectedFrom("failed to connect to /127.0.0.1 (port 9050)"))
    }

    @Test
    fun `message null rend null`() {
        assertNull(SeqResync.expectedFrom(null))
    }

    @Test
    fun `erreurs applicatives sans seq rendent null`() {
        assertNull(SeqResync.expectedFrom("signature invalide pour a1b2c3"))
        assertNull(SeqResync.expectedFrom("destinataire requis"))
        assertNull(SeqResync.expectedFrom("quotas: trop d'événements"))
    }

    @Test
    fun `grandes séquences`() {
        assertEquals(9_000_000_001L, SeqResync.expectedFrom("seq 9000000002 en attente (attendu 9000000001)"))
    }
}
