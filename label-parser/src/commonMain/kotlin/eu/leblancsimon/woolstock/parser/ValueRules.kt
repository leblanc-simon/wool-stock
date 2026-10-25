package eu.leblancsimon.woolstock.parser

/** Validation et normalisation des valeurs lues (bain, n° de coloris). */
object ValueRules {
    const val MAX_DYE_LOT_LENGTH = 10
    const val MAX_COLOR_CODE_LENGTH = 8

    private val SHAPE = Regex("^[A-Za-z0-9](?:[A-Za-z0-9/-]*[A-Za-z0-9])?$")
    private const val UNITS =
        "g|gr|grs|gram|grams|gramm|gramme|grammes|kg|oz|m|mt|mts|mtr|mtrs|metre|metres|meter|meters|yd|yds|yard|yards|mm|cm|ml"
    private val UNIT_SUFFIXED = Regex("^\\d+(?:[.,]\\d+)?(?:$UNITS)$", RegexOption.IGNORE_CASE)
    private val UNIT_FOLLOWING = Regex("^\\s*(?:(?:$UNITS)\\b|%|°)", RegexOption.IGNORE_CASE)

    private val LETTER_AS_DIGIT = mapOf(
        'O' to '0', 'o' to '0',
        'I' to '1', 'l' to '1', 'i' to '1',
        'S' to '5', 's' to '5',
        'B' to '8',
    )

    /** Lettres qui servent souvent de préfixe à un vrai numéro de bain (« B12 »), à ne pas corriger en tête. */
    private val LEGIT_PREFIXES = setOf('B', 'S', 's')

    fun isPlausibleDyeLot(raw: String, following: String = "", ean: String? = null): Boolean =
        isPlausibleCode(raw, following, ean, MAX_DYE_LOT_LENGTH)

    fun isPlausibleColorCode(raw: String, following: String = "", ean: String? = null): Boolean =
        isPlausibleCode(raw, following, ean, MAX_COLOR_CODE_LENGTH)

    private fun isPlausibleCode(raw: String, following: String, ean: String?, maxLength: Int): Boolean {
        if (raw.length !in 1..maxLength) return false
        if (!SHAPE.matches(raw) || raw.none { it.isDigit() }) return false
        if (UNIT_SUFFIXED.matches(raw)) return false
        if (UNIT_FOLLOWING.containsMatchIn(following)) return false
        if (ean != null && raw.length >= 5 && raw.filter { it.isDigit() }.let { it.length >= 5 && ean.contains(it) }) return false
        return true
    }

    /**
     * Correction prudente des confusions OCR (O→0, I/l→1, S→5, B→8), uniquement dans une valeur
     * majoritairement numérique. Une lettre en tête suivie de chiffres peut être un préfixe légitime :
     * on ne la corrige que si c'est un O, un I ou un l.
     */
    fun normalizeDigits(raw: String): String {
        val alnum = raw.filter { it.isLetterOrDigit() }
        if (alnum.isEmpty()) return raw
        val digits = alnum.count { it.isDigit() }
        if (digits * 2 <= alnum.length) return raw
        return raw.mapIndexed { index, c ->
            val replacement = LETTER_AS_DIGIT[c]
            when {
                replacement == null -> c
                index == 0 && c in LEGIT_PREFIXES -> c
                else -> replacement
            }
        }.joinToString("")
    }

    /** Mot de nom de coloris : lettres (accents compris), apostrophes, tirets. */
    fun isColorNameWord(word: String): Boolean {
        if (word.length < 2 || !word[0].isLetter()) return false
        if (word.any { !it.isLetter() && it != '-' && it != '\'' && it != '’' }) return false
        val folded = TextFold.fold(word)
        return !Keywords.isKeywordText(folded) && folded !in NON_NAME_WORDS
    }

    private val NON_NAME_WORDS = setOf(
        "no", "nr", "num", "n", "env", "ca", "approx", "aprox", "circa", "gr", "grs", "mt", "mts", "yds", "mm", "cm",
        "et", "and", "und", "en", "de", "di", "la", "le",
    )
}
