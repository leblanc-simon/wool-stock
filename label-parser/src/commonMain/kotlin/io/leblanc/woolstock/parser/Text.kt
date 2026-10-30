package io.leblanc.woolstock.parser

import kotlin.math.min

/**
 * Repli insensible à la casse et aux accents, caractère par caractère : la chaîne repliée a
 * exactement la même longueur que l'originale, ce qui permet de reporter les positions.
 */
internal object TextFold {
    fun fold(text: String): String = buildString(text.length) { text.forEach { append(foldChar(it)) } }

    fun foldChar(c: Char): Char {
        when (c) {
            'ß' -> return 's'
            'ø', 'Ø' -> return 'o'
            'æ', 'Æ' -> return 'a'
            'œ', 'Œ' -> return 'o'
            'ł', 'Ł' -> return 'l'
            'º' -> return '°'
            '’' -> return '\''
        }
        val lower = c.lowercaseChar()
        if (lower.code < 128) return lower
        val decomposed = canonicalDecomposition(lower.toString())
        return decomposed.firstOrNull { it.category != CharCategory.NON_SPACING_MARK } ?: lower
    }
}

internal object Levenshtein {
    fun distance(a: CharSequence, b: CharSequence): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}

/** Suite maximale de lettres et de chiffres, avec sa position dans la ligne d'origine. */
internal data class Token(val text: String, val folded: String, val start: Int, val end: Int) {
    val hasDigit: Boolean get() = folded.any { it.isDigit() }
    val hasLetter: Boolean get() = folded.any { it.isLetter() }

    /** Variante où les chiffres souvent confondus avec des lettres sont remplacés (L0T → lot). */
    val letterized: String
        get() = if (hasDigit && hasLetter) folded.map { DIGIT_AS_LETTER[it] ?: it }.joinToString("") else folded

    private companion object {
        val DIGIT_AS_LETTER = mapOf('0' to 'o', '1' to 'l', '5' to 's', '8' to 'b')
    }
}

internal fun tokenize(text: String, folded: String = TextFold.fold(text)): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    while (i < text.length) {
        if (!text[i].isLetterOrDigit()) {
            i++
            continue
        }
        val start = i
        while (i < text.length && text[i].isLetterOrDigit()) i++
        tokens += Token(text.substring(start, i), folded.substring(start, i), start, i)
    }
    return tokens
}

/** Morceau de texte délimité par des espaces (ou des « / » quand on sépare des valeurs appariées). */
internal data class Chunk(val text: String, val start: Int, val end: Int)

internal fun chunks(text: String, from: Int, to: Int, splitOnSlash: Boolean = false): List<Chunk> {
    val result = mutableListOf<Chunk>()
    var i = from
    fun isSeparator(c: Char) = c.isWhitespace() || (splitOnSlash && c == '/')
    while (i < to) {
        if (isSeparator(text[i])) {
            i++
            continue
        }
        val start = i
        while (i < to && !isSeparator(text[i])) i++
        val trimmed = trimChunk(text, start, i)
        if (trimmed != null) result += trimmed
    }
    return result
}

private const val CHUNK_TRIM = ".,;:()[]{}\"'*|"

private fun trimChunk(text: String, start: Int, end: Int): Chunk? {
    var s = start
    var e = end
    while (s < e && text[s] in CHUNK_TRIM) s++
    while (e > s && text[e - 1] in CHUNK_TRIM) e--
    return if (s < e) Chunk(text.substring(s, e), s, e) else null
}
