package eu.leblancsimon.woolstock.parser

import kotlin.math.max
import kotlin.math.roundToInt

/** Ligne OCR enrichie : texte replié, jetons, et estimation de la position de chaque caractère. */
internal class PreparedLine(val index: Int, val line: OcrLine, ean: String?) {
    val text: String = line.text
    val folded: String = TextFold.fold(text)
    val tokens: List<Token> = tokenize(text, folded)
    val box: Rect = line.box
    val height: Int = max(1, box.height)
    val isBarcode: Boolean = looksLikeBarcode(text, ean)

    /** Position des mots ML Kit dans le texte de la ligne, quand ils sont fournis. */
    private val wordSpans: List<Pair<IntRange, Rect>> = buildList {
        var cursor = 0
        for (word in line.words) {
            val at = text.indexOf(word.text, cursor)
            if (at < 0) continue
            add(at until at + word.text.length to word.box)
            cursor = at + word.text.length
        }
    }

    /** Boîte estimée des caractères [start, end) : mots ML Kit si disponibles, sinon interpolation. */
    fun boxOf(start: Int, end: Int): Rect {
        if (text.isEmpty() || end <= start) return box
        var result: Rect? = null
        for ((range, wordBox) in wordSpans) {
            val s = max(start, range.first)
            val e = minOf(end, range.last + 1)
            if (s >= e) continue
            val slice = interpolate(wordBox, range.last + 1 - range.first, s - range.first, e - range.first)
            result = result?.union(slice) ?: slice
        }
        return result ?: interpolate(box, text.length, start, end)
    }

    private fun interpolate(area: Rect, length: Int, start: Int, end: Int): Rect {
        val perChar = area.width.toFloat() / max(1, length)
        return Rect(
            left = area.left + (perChar * start).roundToInt(),
            top = area.top,
            right = area.left + (perChar * end).roundToInt(),
            bottom = area.bottom,
        )
    }

    private companion object {
        /** Chiffres imprimés sous un code-barre : jamais un bain ni un coloris. */
        fun looksLikeBarcode(text: String, ean: String?): Boolean {
            val compact = text.filterNot { it.isWhitespace() || it == '<' || it == '>' }
            if (compact.isEmpty() || compact.any { !it.isDigit() }) return false
            if (compact.length >= 12) return true
            return ean != null && compact.length >= 6 && (ean.contains(compact) || compact.contains(ean))
        }
    }
}
