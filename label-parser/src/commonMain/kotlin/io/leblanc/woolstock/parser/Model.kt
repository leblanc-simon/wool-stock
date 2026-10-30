package io.leblanc.woolstock.parser

import kotlin.math.max
import kotlin.math.min

/** Boîte englobante en pixels : left, top, right, bottom. Indépendante d'Android. */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: Rect) = Rect(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )
}

/** Mot reconnu, quand l'OCR fournit ce niveau de détail (éléments ML Kit). */
data class OcrWord(val text: String, val box: Rect)

data class OcrLine(
    val text: String,
    val box: Rect,
    val words: List<OcrWord> = emptyList(),
)

data class Candidate(
    val value: String,
    val confidence: Float,
    val sourceLine: Int,
    /** Valeur telle que lue, avant correction O→0, I→1… */
    val rawValue: String = value,
    /** Zone de la valeur dans l'image, pour l'overlay de debug. */
    val box: Rect? = null,
)

data class LabelParseResult(
    val dyeLot: Candidate?,
    val brand: Candidate?,
    val colorCode: Candidate?,
    val colorName: Candidate?,
    val keywordHits: List<KeywordHit> = emptyList(),
) {
    companion object {
        val EMPTY = LabelParseResult(null, null, null, null)
    }
}

enum class LabelField { DYE_LOT, COLOR }

data class KeywordHit(
    val field: LabelField,
    val keyword: String,
    val lineIndex: Int,
    val start: Int,
    val end: Int,
    val box: Rect,
    val fuzzy: Boolean,
    /** Priorité du mot-clé pondérée par la qualité de la correspondance. */
    val weight: Float,
)
