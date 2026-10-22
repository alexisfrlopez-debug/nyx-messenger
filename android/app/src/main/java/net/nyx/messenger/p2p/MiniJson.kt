package net.nyx.messenger.p2p

/**
 * v0.2.18 — Mini-lecteur JSON pour les corps de signalisation/HELLO. Le projet
 * reste volontairement sans dépendance JSON côté client (miroir des parseurs
 * regex existants du dépôt) MAIS l'extraction des chaînes gère les échappements
 * (« \\ » et « \" » — seuls échappements émis par nos encodeurs) : un alias
 * utilisateur contenant une quote ne doit pas casser le parse.
 */
internal object MiniJson {

    /** Valeur chaîne de [key] — null si absente ; échappements dépliés. */
    fun str(json: String, key: String): String? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        while (i >= 0) {
            var j = i + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j < json.length && json[j] == ':') {
                j++
                while (j < json.length && json[j].isWhitespace()) j++
                if (j < json.length && json[j] == '"') {
                    j++
                    val sb = StringBuilder()
                    while (j < json.length) {
                        val c = json[j]
                        if (c == '\\' && j + 1 < json.length) {
                            sb.append(json[j + 1]); j += 2; continue
                        }
                        if (c == '"') return sb.toString()
                        sb.append(c); j++
                    }
                    return sb.toString() // JSON tronqué — au mieux
                }
            }
            i = json.indexOf(needle, i + 1)
        }
        return null
    }

    /** Valeur numérique de [key] — null si absente. */
    fun num(json: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
}
