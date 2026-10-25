package eu.leblancsimon.woolstock.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Format commun aux fixtures de test et à l'export du mode debug : quelques frames OCR brutes
 * (texte + boîtes) et, une fois annotées à la main, les valeurs attendues.
 */
@Serializable
data class LabelDump(
    val id: String,
    /** « synthetic » pour les fixtures écrites à la main, « real » pour les exports de l'app. */
    val source: String = SOURCE_SYNTHETIC,
    val description: String? = null,
    val ean: String? = null,
    val frames: List<FrameDump>,
    /** À remplir à la main ; une fixture sans `expected` est listée « à annoter » dans le rapport. */
    val expected: ExpectedFields? = null,
    /** Ce que proposait l'app au moment de l'export, pour aider à l'annotation. */
    val parsed: ParsedFields? = null,
    val exportedAt: String? = null,
    val device: String? = null,
) {
    companion object {
        const val SOURCE_SYNTHETIC = "synthetic"
        const val SOURCE_REAL = "real"
    }
}

@Serializable
data class FrameDump(
    val lines: List<LineDump>,
    val ean: String? = null,
    /** Texte brut complet renvoyé par l'OCR. */
    val text: String? = null,
)

@Serializable
data class LineDump(val text: String, val box: BoxDump, val words: List<WordDump> = emptyList())

@Serializable
data class WordDump(val text: String, val box: BoxDump)

@Serializable
data class BoxDump(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Une chaîne vide signifie « aucune valeur attendue » ; un champ absent n'est pas vérifié. */
@Serializable
data class ExpectedFields(
    val dyeLot: String? = null,
    val brand: String? = null,
    val colorCode: String? = null,
    val colorName: String? = null,
)

@Serializable
data class ParsedFields(
    val dyeLot: String? = null,
    val dyeLotRaw: String? = null,
    val brand: String? = null,
    val colorCode: String? = null,
    val colorName: String? = null,
)

object LabelDumps {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun decode(text: String): LabelDump = json.decodeFromString(text)

    fun encode(dump: LabelDump): String = json.encodeToString(dump)
}

fun BoxDump.toRect() = Rect(left, top, right, bottom)

fun Rect.toDump() = BoxDump(left, top, right, bottom)

fun LineDump.toOcrLine() = OcrLine(text, box.toRect(), words.map { OcrWord(it.text, it.box.toRect()) })

fun OcrLine.toDump() = LineDump(text, box.toDump(), words.map { WordDump(it.text, it.box.toDump()) })
