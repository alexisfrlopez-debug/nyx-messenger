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
 *   4. chiffrement sealed-box + signature NYX1 aller-retour ;
 *   5. sauvegarde de compte v0.3.2 : export v2 → enveloppe chiffrée
 *      nyx-enc-v1 → import complet (onion + alias + profil + contacts +
 *      curseurs) — la chaîne exacte des fichiers générés par le téléphone.
 * Sortie 0 = OK, 3 = échec.
 */
internal object SelfTest {

    fun run(): Boolean {
        var ok = true
        ok = ok and step("libsodium") { sodiumLoaded() }
        ok = ok and step("onion-v3") { onionRoundtrip() }
        ok = ok and step("identite") { identityRoundtrip() }
        ok = ok and step("chiffrement") { cipherRoundtrip() }
        ok = ok and step("sauvegarde") { backupRoundtrip() }
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

    /**
     * v0.3.2 — chaîne SAUVEGARDE complète : export v2 (clé+alias+profil+
     * contacts+curseurs) → enveloppe nyx-enc-v1 (PBKDF2 150k + AES-GCM) →
     * déchiffrement → extraction → application sur une machine vierge.
     * C'est EXACTEMENT le trajet d'un fichier généré par le téléphone.
     */
    private fun backupRoundtrip() {
        val pwd = "mot-de-passe-sauvegarde".toCharArray()

        // Machine « source ».
        val dirA = Files.createTempDirectory("nyx-selftest-a").toFile()
        System.setProperty("nyx.data.dir", dirA.absolutePath)
        val idA = DesktopIdentity(File(dirA, "identity.json"))
        val storeA = DesktopStore().apply { ensureLoaded() }
        val onion = idA.createIfNeeded(null, "selftest")
        storeA.saveMyProfile(DesktopStore.MyProfile(displayName = "Nyx Test", bio = "nuit"))
        storeA.upsertContact(
            DesktopStore.Contact(
                onion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnop.onion",
                alias = "contact_temoins", addedAt = 123L,
                status = DesktopStore.ContactStatus.ACCEPTED,
            )
        )
        storeA.setCursor("zyxwvutsrqponmlkjihgfedcba234567zyxwvutsrqponmlkjihgfedcba234567zy.onion", 42L)

        // Export → enveloppe chiffrée (le format des fichiers téléphone).
        val json = BackupRestore.exportBackupJson(idA, storeA)
        val envelope = net.nyx.messenger.crypto.PasswordCrypto
            .encrypt(json.toByteArray(Charsets.UTF_8), pwd)
        check(net.nyx.messenger.crypto.PasswordCrypto.isEncrypted(envelope)) { "enveloppe non reconnue" }

        // Machine « cible » (vierge) : déchiffrement → extraction → application.
        val dirB = Files.createTempDirectory("nyx-selftest-b").toFile()
        System.setProperty("nyx.data.dir", dirB.absolutePath)
        val plain = net.nyx.messenger.crypto.PasswordCrypto.decrypt(envelope, pwd)
            ?: error("déchiffrement de l'enveloppe impossible")
        val found = net.nyx.messenger.crypto.IdentityBackup.extract(plain.toString(Charsets.UTF_8))
            ?: error("sauvegarde méconnaissable")
        val idB = DesktopIdentity(File(dirB, "identity.json"))
        val storeB = DesktopStore().apply { ensureLoaded() }
        val onionB = BackupRestore.applyRestore(idB, storeB, found, null)

        check(onion == onionB) { "onion divergent après restauration ($onion vs $onionB)" }
        check(idB.alias == "selftest") { "alias non restauré (${idB.alias})" }
        check(storeB.myProfile().displayName == "Nyx Test") { "profil non restauré" }
        check(storeB.contacts().size == 1 && storeB.contacts()[0].status == DesktopStore.ContactStatus.ACCEPTED) {
            "contacts non restaurés (${storeB.contacts().size})"
        }
        check(storeB.cursor("zyxwvutsrqponmlkjihgfedcba234567zyxwvutsrqponmlkjihgfedcba234567zy.onion") == 42L) {
            "curseur inbox non restauré"
        }
        // Mauvais mot de passe → null (jamais de crash, miroir Android v0.2.27).
        check(net.nyx.messenger.crypto.PasswordCrypto.decrypt(envelope, "faux".toCharArray()) == null) {
            "un mauvais mot de passe ne doit rien déchiffrer"
        }
        dirA.deleteRecursively(); dirB.deleteRecursively()
    }
}
