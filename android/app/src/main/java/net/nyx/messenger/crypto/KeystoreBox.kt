package net.nyx.messenger.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement AES-256-GCM piloté par le Keystore matériel.
 *
 * Sert à sceller la clé privée d'identité (ed25519) au repos : la clé AES ne quitte
 * jamais le Keystore ; sans elle, le matériel scellé est indéchiffrable.
 * (ADR-002 : libsodium pour les canaux, Keystore pour le stockage local des clés.)
 */
object KeystoreBox {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val NYX_KEY_ALIAS = "nyx_identity_master_key"
    private const val GCM_TAG_BITS = 128

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(NYX_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                NYX_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** Renvoie nonce(12) || ciphertext. */
    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val nonce = cipher.iv
        val ct = cipher.doFinal(plain)
        return nonce + ct
    }

    /** Déchiffre nonce(12) || ciphertext (sortie de [seal]). */
    fun open(sealed: ByteArray): ByteArray {
        val nonce = sealed.copyOfRange(0, 12)
        val ct = sealed.copyOfRange(12, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ct)
    }
}
