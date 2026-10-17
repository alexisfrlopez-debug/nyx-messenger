package net.nyx.messenger.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * v0.2.18 — Protocole du canal direct P2P : trames (write/read roundtrip,
 * payloads vides ET volumineux ~200 Ko = fragment filaire), HELLO
 * encode/parse, rejets des adresses aberrantes.
 */
class P2pProtocolTest {

    @Test
    fun `trame roundtrip - petit payload`() {
        val bos = ByteArrayOutputStream()
        P2pProtocol.write(bos, P2pProtocol.F_FILE, "corps".toByteArray())
        val f = P2pProtocol.read(ByteArrayInputStream(bos.toByteArray()))
        assertNotNull(f)
        assertEquals(P2pProtocol.F_FILE, f!!.type)
        assertEquals("corps", String(f.payload))
    }

    @Test
    fun `trame roundtrip - payload ~200 Ko (fragment filaire)`() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val bos = ByteArrayOutputStream()
        P2pProtocol.write(bos, P2pProtocol.F_AUDIO, big)
        val f = P2pProtocol.read(ByteArrayInputStream(bos.toByteArray()))
        assertNotNull(f)
        assertArrayEquals(big, f!!.payload)
    }

    @Test
    fun `trame vide - roundtrip`() {
        val bos = ByteArrayOutputStream()
        P2pProtocol.write(bos, P2pProtocol.F_BYE)
        val f = P2pProtocol.read(ByteArrayInputStream(bos.toByteArray()))
        assertNotNull(f)
        assertEquals(P2pProtocol.F_BYE, f!!.type)
        assertEquals(0, f.payload.size)
    }

    @Test
    fun `flux fermé - null (pas d'exception)`() {
        assertNull(P2pProtocol.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `hello roundtrip - file et call`() {
        val sig = "AAAA".repeat(22) + "AA" // 88 chars quelconques
        val hFile = P2pProtocol.Hello("file", "a".repeat(56) + ".onion", "expéditeur", "", "", 12, sig)
        val back = P2pProtocol.parseHello(P2pProtocol.encodeHello(hFile, sig))
        assertNotNull(back)
        assertEquals(hFile.kind, back!!.kind)
        assertEquals(hFile.onion, back.onion)
        assertEquals(hFile.count, back.count)
        assertEquals(sig, back.sig)

        val hCall = P2pProtocol.Hello("call", "b".repeat(56) + ".onion", "appelant", "c" * 32, "csd0=", 0, sig)
        val backCall = P2pProtocol.parseHello(P2pProtocol.encodeHello(hCall, sig))
        assertNotNull(backCall)
        assertEquals("c".repeat(32), backCall!!.cid)
        assertEquals("csd0=", backCall.csd)
    }

    @Test
    fun `hello invalide - null`() {
        assertNull(P2pProtocol.parseHello("not json".toByteArray()))
        assertNull(
            P2pProtocol.parseHello(
                """{"v":1,"t":"zzz","onion":"${"a".repeat(56)}.onion","alias":"","cid":"","csd":"","count":1,"sig":""}""".toByteArray()
            )
        )
        assertNull(
            P2pProtocol.parseHello(
                """{"v":1,"t":"file","onion":"court","alias":"","cid":"","csd":"","count":1,"sig":""}""".toByteArray()
            )
        )
    }

    @Test
    fun `chaîne signée - miroir exact`() {
        assertEquals(
            "nyx-p2p|call|abcdef.onion|cid123",
            P2pProtocol.helloSignedString("call", "abcdef.onion", "cid123")
        )
    }
}

private operator fun String.times(n: Int): String = repeat(n)
