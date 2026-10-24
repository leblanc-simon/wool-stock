package eu.leblancsimon.woolstock.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max

@Serializable
data class BrandEntry(val name: String, val aliases: List<String> = emptyList())

/** Dictionnaire de marques embarqué (`brands.json`), extensible sans toucher au code. */
class BrandDictionary(val entries: List<BrandEntry>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String) = BrandDictionary(json.decodeFromString<List<BrandEntry>>(text))

        fun default(): BrandDictionary {
            val stream = BrandDictionary::class.java.getResourceAsStream("brands.json")
                ?: error("brands.json introuvable dans le classpath")
            return stream.use { fromJson(it.readBytes().decodeToString()) }
        }
    }
}

/** Correspondance floue (Levenshtein normalisé) des lignes OCR contre le dictionnaire de marques. */
internal class BrandMatcher(dictionary: BrandDictionary) {
    private class Alias(val brand: String, val compact: String, val wordCount: Int)

    private val aliases: List<Alias> = dictionary.entries.flatMap { entry ->
        (listOf(entry.name) + entry.aliases).map { alias ->
            val folded = TextFold.fold(alias)
            Alias(entry.name, folded.filter { it.isLetterOrDigit() }, tokenize(folded, folded).size)
        }
    }

    fun match(lines: List<PreparedLine>): Candidate? {
        if (lines.isEmpty()) return null
        val medianHeight = lines.map { it.height }.sorted()[lines.size / 2]
        var best: Candidate? = null
        for (line in lines) {
            if (line.isBarcode || line.tokens.isEmpty()) continue
            val sizeFactor = if (line.height > medianHeight) 1.0f else BIG_TEXT_PENALTY
            for (alias in aliases) {
                val maxWindow = minOf(alias.wordCount + 1, line.tokens.size)
                for (size in 1..maxWindow) {
                    for (start in 0..line.tokens.size - size) {
                        val window = line.tokens.subList(start, start + size)
                        val similarity = similarity(window, alias) ?: continue
                        val score = similarity * sizeFactor
                        if (best == null || score > best.confidence) {
                            val from = window.first().start
                            val to = window.last().end
                            best = Candidate(
                                value = alias.brand,
                                confidence = score,
                                sourceLine = line.index,
                                rawValue = line.text.substring(from, to),
                                box = line.boxOf(from, to),
                            )
                        }
                    }
                }
            }
        }
        return best
    }

    private fun similarity(window: List<Token>, alias: Alias): Float? {
        val allowed = when {
            alias.compact.length <= 4 -> 0
            alias.compact.length <= 7 -> 1
            else -> alias.compact.length / 5
        }
        var best: Float? = null
        for (candidate in listOf(window.joinToString("") { it.folded }, window.joinToString("") { it.letterized }).distinct()) {
            if (abs(candidate.length - alias.compact.length) > allowed) continue
            val distance = Levenshtein.distance(candidate, alias.compact)
            if (distance > allowed) continue
            val similarity = 1f - distance.toFloat() / max(candidate.length, alias.compact.length)
            if (best == null || similarity > best) best = similarity
        }
        return best
    }

    private companion object {
        /** Les marques sont presque toujours en gros caractères : une ligne de taille normale compte moins. */
        const val BIG_TEXT_PENALTY = 0.85f
    }
}
