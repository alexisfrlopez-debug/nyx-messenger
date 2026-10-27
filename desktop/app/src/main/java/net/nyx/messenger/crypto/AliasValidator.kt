package net.nyx.messenger.crypto

/**
 * Validation et suggestions d'alias — miroir exact de la validation serveur
 * (`journal.AliasRE`, wire-format.md §6.2) : `^[a-z0-9_]{3,24}$`.
 *
 * L'alias est un pseudonyme PUBLIC, optionnel et séparable de l'identité onion
 * (ADR-003) — il ne doit jamais contenir d'élément d'identité réelle (ADR-010).
 */
object AliasValidator {

    /** Format normalisé serveur : minuscules, chiffres, underscore, 3 à 24 caractères. */
    val REGEX: Regex = Regex("^[a-z0-9_]{3,24}$")

    fun isValid(name: String): Boolean = REGEX.matches(name)

    /** Normalise une saisie vers le format serveur (minuscules, caractères invalides → « _ »). */
    fun normalize(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_").take(24)

    /**
     * Suggestions d'alias alternatifs quand celui choisi est déjà pris (ou invalide) :
     * variations numérotées du nom normalisé — déterministes, faciles à re-vérifier.
     */
    fun suggestions(base: String, count: Int = 5): List<String> {
        val clean = normalize(base).trimEnd('_').ifBlank { "nyx_user" }
        val root = if (clean.length < 3) clean + "_user" else clean.take(24)
        val out = mutableListOf<String>()
        var n = 2
        while (out.size < count && n < 10_000) {
            val candidate = ("$root$n").take(24)
            if (isValid(candidate)) out.add(candidate)
            n += 1 + (n / 7)
        }
        return out
    }
}
