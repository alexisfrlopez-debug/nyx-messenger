package net.nyx.messenger.desktop

import com.goterl.lazysodium.interfaces.Sign
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.crypto.OnionCodec
import net.nyx.messenger.crypto.RecoveryKey
import net.nyx.messenger.crypto.Sodium
import java.io.File
import java.nio.file.Files

/**
 * Autotest headless du cœur desktop (ajouté au correctif v0.3.1) — lancé par
 * `nyx-messenger.bat --selftest` / `java -jar nyx-messenger.jar --selftest`
 * (pipeline de build + diagnostics terrain ; miroir des smoke tests Android :
 * marqueurs + code de sortie). Aucune fenêtre, aucun Tor, aucun réseau :
 *   1. libsodium native chargée (Natives + Sodium) ;
 *   2. clé ed25519 → adresse onion v3 (OnionCodec) et retour ;
 *   3. identité scellée créée puis restaurée (PBKDF2 150k + AES-256-GCM) ;
 *   4. chiffrement sealed-box + signature NYX1 aller-retour.
 * Sortie 0 = OK, 3 = échec.
 */
internal object SelfTest {

    fun run(): Boolean {
        var ok = true
        ok = ok and step("libsodium") { sodiumLoaded() }
        ok = ok and step("onion-v3") { onionRoundtrip() }
        ok = ok and step("identite") { identityRoundtrip() }
        ok = ok and step("chiffrement") { cipherRoundtrip() }
        println("[SELFTEST] ${if (ok) "OK — noyau desktop v${ServiceLocator.VERSION} opérationnel" else "ECHEC"}")
        return ok
    }

    private inline fun step(name: String, block: () -> Unit): Boolean = try {
        block()
        println("[SELFTEST] OK $name")
        true
    } catch (t: Throwable) {
        println("[SELFTEST] ECHEC $name — ${t.javaClass.simpleName}: ${t.message}")
        t.printStackTrace()
        false
    }

    private fun sodiumLoaded() {
        // Toute opération libsodium prouve le chargement natif — ici une
        // paire ed25519 (UnsatisfiedLinkError sinon, cf. terrain v0.3.0).
        val sign = Sodium as Sign.Native
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        check(sign.cryptoSignKeypair(pk, sk)) { "crypto_sign_keypair a échoué (libsodium non chargée ?)" }
        println("      libsodium chargée via ${System.getProperty("jna.library.path") ?: "chargement lazysodium"}")
    }

    private fun onionRoundtrip() {
        val sign = Sodium as Sign.Native
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        check(sign.cryptoSignKeypair(pk, sk)) { "crypto_sign_keypair a échoué" }
        val onion = OnionCodec.pubKeyToOnion(pk)
        check(onion.endsWith(".onion")) { "adresse invalide : $onion" }
        check(OnionCodec.onionToPubKey(onion).contentEquals(pk)) { "aller-retour onion impossible" }
        println("      onion=${onion.removePrefix("http://")}")
    }

    private fun identityRoundtrip() {
        val dir = Files.createTempDirectory("nyx-selftest").toFile()
        System.setProperty("nyx.data.dir", dir.absolutePath) // isolation totale (DesktopPaths)
        val pwd = "mot-de-passe-selftest".toCharArray()
        val id = DesktopIdentity(File(dir, "identity.json"))
        val onion = id.createIfNeeded(pwd, "selftest")
        check(onion.endsWith(".onion")) { "onion invalide : $onion" }
        val sk = id.secretKey(pwd)
        val key = RecoveryKey.encode(sk.copyOfRange(0, 32))
        id.wipe()
        val onion2 = id.restoreFromSeed(key, pwd)
        check(onion == onion2) { "onion divergent après restauration ($onion vs $onion2)" }
        check(id.secretKey(pwd).contentEquals(sk)) { "clé privée divergente après restauration" }
        dir.deleteRecursively()
    }

    private fun cipherRoundtrip() {
        val sign = Sodium as Sign.Native
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        check(sign.cryptoSignKeypair(pk, sk)) { "crypto_sign_keypair a échoué" }
        val cipher = MessageCipher.INSTANCE
        val message = "la nuit tombe, le messager tient".toByteArray(Charsets.UTF_8)
        val body = cipher.encryptFor(pk, message)
        check(cipher.decryptForSelf(sk, body)?.contentEquals(message) == true) {
            "sealed box aller-retour impossible"
        }
        val canonical = MessageCipher.canonical("MSG", "selftest", 1L, 2L, 48L, "dest", body)
        val sig = cipher.signEvent(canonical, sk)
        check(cipher.verifyEvent(canonical, sig, pk)) { "vérification NYX1 impossible" }
        check(!cipher.verifyEvent(canonical + "x", sig, pk)) { "falsification NYX1 acceptée ?!" }
    }
}
