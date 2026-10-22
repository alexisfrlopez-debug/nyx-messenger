package net.nyx.messenger.p2p

/**
 * v0.2.18 — Signalisation d'appel (ADR-015) : corps « msg » chiffré E2E,
 * JSON kind=call — type DÉJÀ whitelisté par le réplica (zéro changement
 * serveur), INVISIBLE pour lui. Objet PUR (test JVM sans Android).
 *
 *  - offer  : l'appelant sonne le pair (poll inbox ≤ 15 s) ;
 *  - reject : l'appelé refuse (l'appelant quitte sa sonnerie) ;
 *  - bye    : raccroché / annulation (trace « appel manqué » possible).
 *
 * Le média lui-même ne passe JAMAIS par ici (interdit par ADR-015) : il va
 * sur la connexion directe onion-to-onion ouverte en parallèle.
 */
data class CallSignal(
    val op: String,        // offer | reject | bye
    val cid: String,       // identifiant de session (16 octets hex)
    val alias: String,     // alias déclaré de l'émetteur (affichage sonnerie)
    val ts: Long,          // horodatage émetteur (epoch s) — anti-rejeu souple
) {
    fun encode(): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"v\":1,\"kind\":\"call\",\"op\":\"${esc(op)}\",\"cid\":\"${esc(cid)}\"," +
            "\"alias\":\"${esc(alias)}\",\"ts\":$ts}"
    }

    companion object {
        const val KIND = "call"
        const val OP_OFFER = "offer"
        const val OP_REJECT = "reject"
        const val OP_BYE = "bye"

        /** Fenêtre de validité d'une offre (au-delà = appel manqué). */
        const val OFFER_TTL_S = 120L

        /** Parse strict — null pour TOUT corps qui n'est pas une signalisation (texte ordinaire). */
        fun parse(plain: String): CallSignal? {
            if (MiniJson.str(plain, "kind") != KIND) return null
            val op = MiniJson.str(plain, "op") ?: return null
            if (op != OP_OFFER && op != OP_REJECT && op != OP_BYE) return null
            val cid = MiniJson.str(plain, "cid")
                ?.takeIf { it.length >= 16 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                ?: return null
            val alias = MiniJson.str(plain, "alias") ?: ""
            val ts = MiniJson.num(plain, "ts") ?: 0L
            return CallSignal(op, cid, alias, ts)
        }

        fun newCid(): String {
            val b = ByteArray(16)
            java.security.SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
