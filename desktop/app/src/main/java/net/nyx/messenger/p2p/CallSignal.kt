package net.nyx.messenger.p2p

/**
 * v0.3.6 — PORTAGE MIRROIR de CallSignal Android v0.2.18/0.2.44 (objet pur,
 * testé JVM — ADR-015) : signalisation d'appel sur le journal.
 *
 *  - offer  : l'appelant sonne le pair (poll inbox ≤ 30 s desktop) ;
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
    // v0.2.44 — REPLI RELAIS MÉDIA : l'offre indique l'onion du RÉPLICA où
    // l'appelant ouvrira un canal relais (/call/relay). Vide = pas de repli.
    // (Le bureau v0.3.6 ne BRANCHE pas le relais — direct seul — mais PARSE
    // le champ pour rester interopérable avec un appelant Android.)
    val relay: String = "",
    // v0.3.6 — CODEC AUDIO ANNONCÉ PAR L'APPELANT ("adpcm" bureau ; absent =
    // AAC MediaCodec Android ≤ v0.2.53). Un appelé Android ≥ v0.2.54 qui lit
    // "adpcm" branche son codec logiciel ADPCM ; un ancien l'ignore (il
    // décoderait du silence — voir P2pProtocol.Hello.ac).
    val ac: String = "",
    // v0.3.7 — INTENTION VISIO DE L'OFFRE (cosmétique : l'écran d'appel
    // entrant affiche « visio » AVANT le HELLO ; le choix réel se fait au
    // HELLO.vc/ACCEPT.vc). "mjpeg" bureau ; "h264,mjpeg" téléphone ≥ v0.2.55 ;
    // absent = audio (pair ancien : clé ignorée).
    val vc: String = "",
) {
    fun encode(): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val relayField = if (relay.isNotEmpty()) ",\"relay\":\"${esc(relay)}\"" else ""
        val acField = if (ac.isNotEmpty()) ",\"ac\":\"${esc(ac)}\"" else ""
        val vcField = if (vc.isNotEmpty()) ",\"vc\":\"${esc(vc)}\"" else ""
        return "{\"v\":1,\"kind\":\"call\",\"op\":\"${esc(op)}\",\"cid\":\"${esc(cid)}\"," +
            "\"alias\":\"${esc(alias)}\",\"ts\":$ts$relayField$acField$vcField}"
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
            val relay = MiniJson.str(plain, "relay") ?: ""
            val ac = MiniJson.str(plain, "ac") ?: ""
            val vc = MiniJson.str(plain, "vc") ?: ""
            return CallSignal(op, cid, alias, ts, relay, ac, vc)
        }

        fun newCid(): String {
            val b = ByteArray(16)
            java.security.SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
