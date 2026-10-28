package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * v0.2.56 — LECTURE DE LA RÉPONSE D'APPEL EN BOUCLE (miroir bureau
 * ReadCallAnswerDesktopTest v0.3.8).
 *
 * Cause racine terrain 28/10 : un appelé ≤ v0.2.55 expédiait sa trame
 * F_VIDEO_ROT AVANT son F_CALL_ACCEPT — l'appelant à lecture unique
 * prenait la rotation pour « pas de réponse » → BYE silencieux →
 * l'appel s'effondrait à l'instant du décroché. [P2pProtocol.readCallAnswer]
 * route les trames précoces et ne s'arrête que sur la VRAIE réponse.
 */
class ReadCallAnswerTest {

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
                frame(P2pProtocol.F_CALL_ACCEPT, "{\"csd\":\"AAAA\"}".toByteArray())
        )
        val answer = P2pProtocol.readCallAnswer(stream, 5_000) { }
        assertEquals(P2pProtocol.F_CALL_ACCEPT, answer?.type)
    }

    @Test
    fun `rejet et bye - renvoyes tels quels`() {
        assertEquals(
            P2pProtocol.F_CALL_REJECT,
            P2pProtocol.readCallAnswer(ByteArrayInputStream(frame(P2pProtocol.F_CALL_REJECT)), 5_000) { }?.type
        )
        assertEquals(
            P2pProtocol.F_BYE,
            P2pProtocol.readCallAnswer(
                ByteArrayInputStream(frame(P2pProtocol.F_VIDEO_ROT, byteArrayOf(48)) + frame(P2pProtocol.F_BYE)),
                5_000
            ) { }?.type
        )
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
    fun `deadline bornee contre un spam de rotations`() {
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
