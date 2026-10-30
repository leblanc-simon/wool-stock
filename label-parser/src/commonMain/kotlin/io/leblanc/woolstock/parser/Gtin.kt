package io.leblanc.woolstock.parser

/** Codes GTIN (EAN-8, UPC-A, EAN-13, GTIN-14). */
object Gtin {
    private val VALID_LENGTHS = setOf(8, 12, 13, 14)

    /** Ne garde que les codes numériques plausibles ; un UPC-A (12 chiffres) devient un EAN-13. */
    fun normalize(raw: String?): String? {
        val digits = raw?.filterNot { it.isWhitespace() } ?: return null
        if (digits.length !in VALID_LENGTHS || digits.any { it !in '0'..'9' }) return null
        return if (digits.length == 12) "0$digits" else digits
    }

    fun hasValidFormat(code: String): Boolean = code.length in VALID_LENGTHS && code.all { it in '0'..'9' }
}
