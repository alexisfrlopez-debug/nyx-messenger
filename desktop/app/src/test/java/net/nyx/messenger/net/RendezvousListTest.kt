package net.nyx.messenger.net

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du rendezvous signé (ADR-016) — vecteurs réels produits par
 * scripts/gen_rendezvous.py (ed25519lib validé RFC 8032 3/3). La clé de test
 * est le vecteur RFC 8032 n°1 — JAMAIS utilisée en production.
 */
class RendezvousListTest {

    private val pubB64 = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
    private val json = """{"format":"nyx-rendezvous","version":1,"generatedAt":1760000000,"replicas":[{"onion":"5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:80","note":"test"}]}"""
    private val sigB64 = "GhfxTjyefPSkGbY27uaVwS7xHnMu+n5tubTNhQuBDtq0Cb/AD0dp++Ne2hldUDjpuR2TncUxk2YWiOHcUy1eDg=="

    private val onion = "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:80"

    @Test
    fun `liste valide acceptée avec port conservé`() {
        val v = RendezvousList.load(
            json.toByteArray(), Base64.getDecoder().decode(sigB64), pubB64
        )!!
        assertEquals(1760000000L, v.generatedAt)
        assertEquals(1, v.entries.size)
        assertEquals(onion, v.entries[0].onion)
        assertEquals("test", v.entries[0].note)
    }

    @Test
    fun `octet altéré refusé en bloc`() {
        val tampered = json.toByteArray().clone()
        tampered[10] = tampered[10]  // copie défensive
        // altération minime du contenu (1 caractère) : 0x30 -> 0x31
        for (i in tampered.indices) {
            if (tampered[i] == ':'.code.toByte()) { tampered[i] = ';'.code.toByte(); break }
        }
        assertNull(RendezvousList.load(tampered, Base64.getDecoder().decode(sigB64), pubB64))
    }

    @Test
    fun `signature altérée refusée`() {
        val sig = Base64.getDecoder().decode(sigB64)
        sig[0] = (sig[0].toInt() xor 0x01).toByte()
        assertNull(RendezvousList.load(json.toByteArray(), sig, pubB64))
    }

    @Test
    fun `clé publique étrangère refusée`() {
        val foreign = "M//9JU3ZIPI210FkqUMDL+oVRsnLuaRjn4H+2uRFy/Q="
        assertNull(RendezvousList.load(json.toByteArray(), Base64.getDecoder().decode(sigB64), foreign))
    }

    @Test
    fun `format et version invalides refusés`() {
        val wrong = json.replace("nyx-rendezvous", "nyx-rendezvous-x")
        val sigWrong = sign(wrong.toByteArray())
        assertNull(RendezvousList.load(wrong.toByteArray(), sigWrong, pubB64)) // format ≠
        assertNull(RendezvousList.load(json.toByteArray(), sigWrong, pubB64))  // signature ne couvre pas
    }

    @Test
    fun `liste vide refusée`() {
        val empty = """{"format":"nyx-rendezvous","version":1,"generatedAt":1,"replicas":[]}"""
        assertNull(RendezvousList.load(empty.toByteArray(), sign(empty.toByteArray()), pubB64))
    }

    @Test
    fun `onion invalide écartée une à une`() {
        val doc = """{"format":"nyx-rendezvous","version":1,"generatedAt":2,"replicas":[
            {"onion":"zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz.onion","note":"fausse"},
            {"onion":"$onion","note":"bonne"},
            {"onion":"pas une adresse","note":"garbage"}]}"""
        val v = RendezvousList.load(doc.toByteArray(), sign(doc.toByteArray()), pubB64)!!
        assertEquals(1, v.entries.size)
        assertEquals(onion, v.entries[0].onion)
    }

    @Test
    fun `doublons éliminés`() {
        val doc = """{"format":"nyx-rendezvous","version":1,"generatedAt":3,"replicas":[
            {"onion":"$onion"},{"onion":"${onion.uppercase()}"}]}"""
        val v = RendezvousList.load(doc.toByteArray(), sign(doc.toByteArray()), pubB64)!!
        assertEquals(1, v.entries.size)
    }

    @Test
    fun `normalize valide port et checksum`() {
        assertEquals(onion, RendezvousList.normalize(onion))
        assertEquals(onion, RendezvousList.normalize(onion.uppercase()))
        assertEquals("5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion", RendezvousList.normalize("5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion"))
        assertNull(RendezvousList.normalize(""))
        assertNull(RendezvousList.normalize("invalide"))
        assertNull(RendezvousList.normalize("5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqe.onion")) // checksum faux
        assertNull(RendezvousList.normalize("5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:notaport"))
    }

    @Test
    fun `verify refuse signature tronquée`() {
        assertFalse(RendezvousList.verify(json.toByteArray(), ByteArray(10), pubB64))
        assertFalse(RendezvousList.verify(json.toByteArray(), Base64.getDecoder().decode(sigB64), "courte="))
    }

    // ---- v0.3.5 : le .sig est du TEXTE base64 (cause racine session 75) ----

    @Test
    fun `signature texte base64 avec fin de ligne acceptée`() {
        // LE format produit par scripts/gen_rendezvous.py : base64 + '\n'.
        // AVANT v0.3.5, ServiceLocator/NyxApplication passaient CES octets
        // (89 ≠ 64) → rejet instantané d'une liste pourtant valide.
        val sigFile = (sigB64 + "\n").toByteArray(Charsets.US_ASCII)
        assertEquals(89, sigFile.size)
        val v = RendezvousList.load(json.toByteArray(), sigFile, pubB64)
        assertNotNull("le .sig TEL QUE LU dans le fichier doit être accepté", v)
        assertEquals(onion, v!!.entries[0].onion)
    }

    @Test
    fun `signature texte base64 sans fin de ligne acceptée`() {
        val v = RendezvousList.load(json.toByteArray(), sigB64.toByteArray(Charsets.US_ASCII), pubB64)
        assertNotNull(v)
    }

    @Test
    fun `decodeSignature - bornes et garbage`() {
        // 64 octets → tels quels.
        val raw64 = Base64.getDecoder().decode(sigB64)
        assertArrayEquals(raw64, RendezvousList.decodeSignature(raw64))
        // Texte base64 + espaces/fins de ligne → décodé.
        assertArrayEquals(raw64, RendezvousList.decodeSignature("  $sigB64 \r\n".toByteArray(Charsets.US_ASCII)))
        // Garbage : vide, longueurs non multiples de 4, base64 illisible,
        // base64 décodant à ≠ 64 octets → null (rejet).
        assertNull(RendezvousList.decodeSignature(ByteArray(0)))
        assertNull(RendezvousList.decodeSignature("abc".toByteArray()))
        assertNull(RendezvousList.decodeSignature("!!!".toByteArray()))
        assertNull(RendezvousList.decodeSignature("AAAAA".toByteArray()))
        assertNull(RendezvousList.decodeSignature(Base64.getEncoder().encode(ByteArray(32))))
        assertNull(RendezvousList.decodeSignature(ByteArray(63)))
        assertNull(RendezvousList.decodeSignature(ByteArray(65)))
    }

    /** Signature locale via la clé de test (vecteur RFC 8032 n°1) — implémentation BC (vérification croisée Python↔Kotlin). */
    private fun sign(raw: ByteArray): ByteArray {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val params = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, params)
        val domain = RendezvousList.DOMAIN.toByteArray(Charsets.US_ASCII)
        signer.update(domain, 0, domain.size)
        signer.update(raw, 0, raw.size)
        return signer.generateSignature()
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }
}
