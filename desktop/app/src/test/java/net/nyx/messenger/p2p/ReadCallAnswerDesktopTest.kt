package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * v0.3.8 — LECTURE DE LA RÉPONSE D'APPEL EN BOUCLE (miroir Android v0.2.56).
 *
 * Cause racine terrain 28/10 (5ᵉ export bureau) : un appelé ≤ v0.2.55
 * expédiait sa trame F_VIDEO_ROT AVANT son F_CALL_ACCEPT — l'appelant à
 * lecture unique prenait la rotation pour « pas de réponse » → BYE silencieux
 * → l'appel visio PC↔téléphone s'effondrait à l'instant du décroché.
 * [P2pProtocol.readCallAnswer] route les trames précoces et ne s'arrête que
 * sur la VRAIE réponse — ces tests verrouillent le comportement (miroir
 * des DEUX côtés : desktop/…/P2pProtocolDesktopTest ↔ android/…/P2pProtocolTest).
 */
class ReadCallAnswerDesktopTest {

    private fun frame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeByte(type)
        d.writeInt(payload.size)
        d.write(payload)
        return out.toByteArray()
    }

    @Test
    fun `rotation AVANT l'accept - la reponse est quand meme l'accept (terrain 28_10)`() {
        // Scénario EXACT du terrain : l'appelé v0.2.55 envoie F_VIDEO_ROT (270°)
        // puis F_CALL_ACCEPT — la lecture unique de v0.3.7 lisait la ROTATION.
        val stream = ByteArrayInputStream(
            frame(P2pProtocol.F_VIDEO_ROT, byteArrayOf(50, 48, 48)) +
                frame(P2pProtocol.F_CALL_ACCEPT, "{\"csd\":\"AAAA\"}".toByteArray())
        )
        var rotations = 0
        val answer = P2pProtocol.readCallAnswer(stream, 5_000) { rotations++ }
        assertEquals(P2pProtocol.F_CALL_ACCEPT, answer?.type)
        assertEquals(1, rotations)
    }

    @Test
    fun `audio et video precoces - ignores jusqu'a l'accept`() {
        val stream = ByteArrayInputStream(
            frame(P2pProtocol.F_AUDIO, ByteArray(324)) +
                frame(P2pProtocol.F_VIDEO, ByteArray(500)) +
                frame(P2pProtocol.F_AUDIO, ByteArray(324)) +
                frame(P2pProtocol.F_CALL_ACCEPT, "{\"csd\":\"AAAA\"}".toByteArray())
        )
        val answer = P2pProtocol.readCallAnswer(stream, 5_000) { }
        assertEquals(P2pProtocol.F_CALL_ACCEPT, answer?.type)
    }

    @Test
    fun `rejet - renvoye tel quel`() {
        val stream = ByteArrayInputStream(frame(P2pProtocol.F_CALL_REJECT))
        val answer = P2pProtocol.readCallAnswer(stream, 5_000) { }
        assertEquals(P2pProtocol.F_CALL_REJECT, answer?.type)
    }

    @Test
    fun `bye - renvoye tel quel`() {
        val stream = ByteArrayInputStream(
            frame(P2pProtocol.F_VIDEO_ROT, byteArrayOf(48)) + frame(P2pProtocol.F_BYE)
        )
        val answer = P2pProtocol.readCallAnswer(stream, 5_000) { }
        assertEquals(P2pProtocol.F_BYE, answer?.type)
    }

    @Test
    fun `eof sans reponse - null (pas de reponse)`() {
        val stream = ByteArrayInputStream(frame(P2pProtocol.F_VIDEO_ROT, byteArrayOf(57, 48)))
        var rotations = 0
        val answer = P2pProtocol.readCallAnswer(stream, 5_000) { rotations++ }
        assertNull(answer)
        assertEquals(1, rotations)
    }

    @Test
    fun `flux vide - null immediat`() {
        val answer = P2pProtocol.readCallAnswer(ByteArrayInputStream(ByteArray(0)), 5_000) { }
        assertNull(answer)
    }

    @Test
    fun `deadline bornee contre un spam de rotations`() {
        // Un pair hostile noie la connexion de rotations : la boucle ne doit
        // pas lire indéfiniment — la deadline la coupe (null après ~deadline).
        val spam = ByteArrayOutputStream()
        repeat(5000) { spam.write(frame(P2pProtocol.F_VIDEO_ROT, byteArrayOf(48))) }
        val start = System.currentTimeMillis()
        val answer = P2pProtocol.readCallAnswer(
            ByteArrayInputStream(spam.toByteArray()), 300
        ) { }
        val elapsed = System.currentTimeMillis() - start
        assertNull(answer)
        assertTrue("la boucle doit être bornée par la deadline (écoulé ${elapsed} ms)", elapsed < 5_000)
    }
}
