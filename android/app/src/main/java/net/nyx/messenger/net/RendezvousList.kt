package net.nyx.messenger.net

import java.util.Base64
import net.nyx.messenger.crypto.OnionCodec
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject

/**
 * Liste de rendezvous signée — ADR-009 §3 / ADR-016 (v0.2.23).
 *
 * L'APK embarque `assets/rendezvous.json` + `assets/rendezvous.json.sig` :
 * la liste des réplicas d'amorçage, signée par la clé ed25519 de release
 * Nyx (clé privée HORS dépôt ; la publique est compilée dans
 * [net.nyx.messenger.BuildConfig.RENDEZVOUS_PUBKEY]).
 *
 * DESIGN DE SIGNATURE (zéro ambiguïté multi-langages) : la signature ed25519
 * détachée porte sur `b"nyx-rendezvous-v1|" + OCTETS BRUTS du fichier` —
 * AUCUNE canonicalisation JSON (éditeur Python, serveur Go et client Kotlin
 * lisent les mêmes octets ; le JSON n'est réinterprété qu'APRÈS vérification).
 *
 * Filet de sécurité : une liste corrompue/altérée est REFUSÉE EN BLOC
 * (retour null → fallback sur l'amorçage legacy v0.2.7) ; les entrées dont
 * l'onion est invalide (checksum SHA3) sont écartées une à une ; les onions
 * valides ressortent dédupliquées, avec leur port conservé.
 *
 * Objet PUR (aucune classe Android sauf Base64) → testable en JVM
 * (RendezvousListTest — la BC rfc8032 est une dépendance JVM).
 */
object RendezvousList {

    /** Domaine de séparation de la signature (même constante que l'outil Python). */
    const val DOMAIN: String = "nyx-rendezvous-v1|"

    /** Entrée de la liste — onion v3 (checksum validé), port optionnel conservé. */
    data class Entry(val onion: String, val note: String?)

    /** Liste vérifiée et normalisée. */
    data class Verified(val generatedAt: Long, val entries: List<Entry>)

    /**
     * Vérifie puis interprète la liste.
     * @param raw octets EXACTS du fichier rendezvous.json
     * @param signature base64 de la signature ed25519 détachée
     * @param pubkeyB64 base64 de la clé publique de release (embarquée)
     * @return la liste vérifiée, ou null si signature/format invalide.
     */
    fun load(raw: ByteArray, signature: ByteArray, pubkeyB64: String): Verified? {
        if (!verify(raw, signature, pubkeyB64)) return null
        return try {
            val root = JSONObject(String(raw, Charsets.UTF_8))
            if (root.optString("format") != "nyx-rendezvous") return null
            if (root.optInt("version") != 1) return null
            val arr = root.optJSONArray("replicas") ?: return null
            val seen = LinkedHashSet<String>()
            val entries = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val onion = normalize(o.optString("onion")) ?: continue
                if (seen.add(onion)) entries.add(Entry(onion, o.optString("note").ifBlank { null }))
            }
            if (entries.isEmpty()) return null
            Verified(root.optLong("generatedAt"), entries)
        } catch (_: Exception) {
            null
        }
    }

    /** Vérification ed25519 détachée (Bouncy Castle rfc8032 — pure JVM/Android). */
    fun verify(raw: ByteArray, signature: ByteArray, pubkeyB64: String): Boolean {
        return try {
            val pubBytes = Base64.getDecoder().decode(pubkeyB64)
            if (pubBytes.size != 32 || signature.size != 64) return false
            val domain = DOMAIN.toByteArray(Charsets.US_ASCII)
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pubBytes, 0))
            verifier.update(domain, 0, domain.size)
            verifier.update(raw, 0, raw.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Normalisation d'une entrée : minuscule, port `:80` optionnel conservé,
     * adresse + checksum validés via [OnionCodec] (SHA3, version 0x03).
     * @return l'adresse normalisée (onion:port) ou null si invalide.
     */
    fun normalize(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        val host = s.substringBeforeLast(':')
        val port = if (s.length > host.length) s.substring(host.length) else ""
        if (!port.matches(Regex("^(:\\d{1,5})?$"))) return null
        return try {
            OnionCodec.onionToPubKey(host) // lève si checksum/longueur/version invalides
            host + port
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
