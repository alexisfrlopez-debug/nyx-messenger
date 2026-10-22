package net.nyx.messenger.replica

import java.security.MessageDigest

/**
 * v0.2.19 — RÈGLES DE VALIDATION du réplica temporaire embarqué — miroir exact
 * de `server/internal/journal/event.go` (Validate) pour que les DEUX bouts du
 * réseau appliquent les mêmes règles (un dépôt accepté par l'un doit être
 * accepté par l'autre, mêmes messages d'erreur → l'auto-réparation de séquence
 * du client fonctionne à l'identique sur les deux).
 *
 * Objet PUR (aucune classe Android) → testable en JVM. La vérification
 * ed25519 (libsodium) est injectée à l'exécution ([EmbeddedReplica.verifier]).
 */
object ReplicaValidate {

    /** Types d'événements connus (miroir KnownTypes côté Go). */
    val KNOWN_TYPES = setOf("msg", "contact", "prekey", "meta", "alias", "profile")

    /** Limites (miroir journal/event.go). */
    const val MAX_BODY_SIZE = 256 * 1024          // 256 Ko
    const val MAX_TS_DRIFT_S = 24 * 3600L         // tolérance horloge (latence Tor)

    /**
     * Validation SANS crypto (type, taille, base64, dérive horloge).
     * Retourne le message d'erreur GO ÉQUIVALENT, ou null si valide.
     */
    fun basicError(type: String, body: String, ts: Long, nowS: Long): String? {
        if (type !in KNOWN_TYPES) return "type inconnu \"$type\""
        if (body.isEmpty() || body.length > MAX_BODY_SIZE) {
            return "taille de body invalide (${body.length})"
        }
        runCatching { java.util.Base64.getDecoder().decode(body) }
            .getOrElse { return "body non base64" }
        val drift = kotlin.math.abs(nowS - ts)
        if (drift > MAX_TS_DRIFT_S) return "horodatage hors tolérance (${drift}s)"
        return null
    }

    /** ID d'un événement = sha256 hex de la représentation canonique (miroir Go). */
    fun eventId(canonical: String): String =
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---------- authentification de la lecture d'inbox ----------

    /**
     * Le réplica embarqué restreint la lecture d'une boîte au DÉTENTEUR de
     * l'adresse (`to`) — le serveur Go n'a pas cette exigence mais IGNORE les
     * paramètres inconnus : le même client parle aux deux (compatibilité
     * totale, zéro changement serveur). Preuve : signature ed25519 de
     * [inboxCanonical] avec la clé dont dépend l'adresse onion `to`.
     */
    const val AUTH_WINDOW_S = 300L

    fun inboxCanonical(to: String, ts: Long): String = "NYX-INBOX\n$to\n$ts"
}

/**
 * v0.2.19 — PLAFONDS du réplica temporaire (demande opérateur : « il ne faut
 * pas que ça bouffe trop de ressource/bande passante/forfait de données »).
 * Valeurs choisies pour rester anecdotiques sur un forfait mobile tout en
 * servant largement une petite liste de contacts.
 */
object ReplicaQuota {

    /** Dépôts max par auteur et par minute glissante (au-delà : 429). */
    const val MAX_DEPOSITS_PER_MIN = 60

    /** Plafonds de stockage. */
    const val MAX_EVENTS_TOTAL = 3_000
    const val MAX_BYTES_TOTAL = 48L * 1024 * 1024   // 48 MiB
    const val MAX_EVENTS_PER_INBOX = 500            // par destinataire

    /** Durée de vie d'un événement (purge périodique) — « réplica temporaire ». */
    const val TTL_MS = 48L * 3600 * 1000

    /** Limiteur de débit glissant par auteur — PUR (testable JVM). */
    class RateLimiter(private val maxPerWindow: Int = MAX_DEPOSITS_PER_MIN,
                      private val windowMs: Long = 60_000L) {
        private val hits = HashMap<String, ArrayDeque<Long>>()

        @Synchronized
        fun allow(author: String, nowMs: Long): Boolean {
            val q = hits.getOrPut(author) { ArrayDeque() }
            while (q.isNotEmpty() && nowMs - q.first() > windowMs) q.removeFirst()
            if (q.size >= maxPerWindow) return false
            q.addLast(nowMs)
            return true
        }

        @Synchronized
        fun size(): Int = hits.size
    }
}
