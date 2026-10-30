package io.leblanc.woolstock.parser

/**
 * Suggestions de saisie (marques, noms de coloris) : insensibles à la casse et aux accents,
 * tolérantes à une faute de frappe, pour que « stylecraft » propose « Stylecraft » et que
 * chacun reprenne la même orthographe.
 */
object Suggestions {
    /**
     * Renvoie au plus [limit] valeurs de [candidates] proches de [query], les plus proches
     * d'abord : même valeur à la casse près, début, début d'un mot, contenu, une faute.
     * [candidates] est rangé par préférence ; les doublons (casse, accents, espaces) sont
     * fusionnés en gardant la première orthographe. La saisie exacte n'est pas reproposée ;
     * une saisie vide renvoie les premiers candidats.
     */
    fun suggest(query: String, candidates: List<String>, limit: Int = 5): List<String> {
        val unique = LinkedHashMap<String, String>()
        for (candidate in candidates) {
            val value = candidate.trim()
            if (value.isNotEmpty()) unique.getOrPut(key(value)) { value }
        }
        val typed = query.trim()
        val folded = key(typed)
        if (folded.isEmpty()) return unique.values.take(limit)
        return unique.entries
            .mapIndexedNotNull { index, (candidateKey, value) ->
                if (value == typed) return@mapIndexedNotNull null
                rank(folded, candidateKey)?.let { Ranked(it, index, value) }
            }
            .sortedWith(compareBy(Ranked::rank, Ranked::order))
            .take(limit)
            .map(Ranked::value)
    }

    /** Vrai si deux saisies ne diffèrent que par la casse, les accents ou les espaces. */
    fun sameValue(a: String, b: String): Boolean = key(a) == key(b)

    private class Ranked(val rank: Int, val order: Int, val value: String)

    private val SPACES = Regex("\\s+")
    private val WORD_SEPARATORS = Regex("[\\s'’-]+")

    private fun key(value: String) = TextFold.fold(value.trim()).replace(SPACES, " ")

    private fun rank(query: String, candidate: String): Int? = when {
        candidate == query -> EXACT
        candidate.startsWith(query) -> PREFIX
        candidate.split(WORD_SEPARATORS).any { it.startsWith(query) } -> WORD_PREFIX
        candidate.contains(query) -> CONTAINS
        query.length >= TYPO_MIN_LENGTH && typo(query, candidate) -> TYPO
        else -> null
    }

    /** Une faute de frappe au plus par rapport au début du candidat (lettre en trop, en moins, changée). */
    private fun typo(query: String, candidate: String): Boolean =
        (query.length - 1..query.length + 1).any { length ->
            length <= candidate.length && Levenshtein.distance(query, candidate.take(length)) <= 1
        }

    private const val EXACT = 0
    private const val PREFIX = 1
    private const val WORD_PREFIX = 2
    private const val CONTAINS = 3
    private const val TYPO = 4
    private const val TYPO_MIN_LENGTH = 4
}
