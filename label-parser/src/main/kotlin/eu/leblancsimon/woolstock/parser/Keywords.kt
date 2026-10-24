package eu.leblancsimon.woolstock.parser

internal data class KeywordWord(val text: String, val requireDot: Boolean)

internal data class KeywordDef(
    val field: LabelField,
    val label: String,
    val words: List<KeywordWord>,
    val priority: Float,
)

/**
 * Mots-clés multilingues. `lot` et `l.` sont ambigus (« lot de 10 pelotes ») : priorité basse.
 * Les mots-clés courts (≤ 3 lettres) exigent une correspondance exacte, les autres tolèrent
 * une erreur OCR.
 */
internal object Keywords {
    val ALL: List<KeywordDef> = buildList {
        add(LabelField.DYE_LOT, 1.0f,
            "dye lot", "dyelot", "bain", "n° bain", "partie", "färbepartie", "farbpartie", "farb partie",
            "partie-nr", "kleurbad", "bagno", "partita", "farvebad", "lote")
        add(LabelField.DYE_LOT, 0.85f, "parti", "dye")
        add(LabelField.DYE_LOT, 0.75f, "lot no", "lot nr")
        add(LabelField.DYE_LOT, 0.6f, "lot")
        add(LabelField.DYE_LOT, 0.45f, "l.")
        add(LabelField.COLOR, 1.0f,
            "coloris", "couleur", "color", "colour", "shade", "farbe", "kleur", "colore", "colori", "farve", "nuance")
        add(LabelField.COLOR, 0.7f, "col", "col.", "fb")
    }

    /** Mots courants sur les étiquettes, à une lettre d'un mot-clé (« lavage à la main » ≠ « bain »). */
    private val FUZZY_DENYLIST = setOf("main", "mains", "pain", "note", "notes", "vote", "part", "parts", "party", "lots")

    private const val MIN_FUZZY_LENGTH = 4
    private const val FUZZY_QUALITY = 0.85f

    private fun MutableList<KeywordDef>.add(field: LabelField, priority: Float, vararg labels: String) {
        labels.forEach { label -> add(KeywordDef(field, label, parseWords(label), priority)) }
    }

    private fun parseWords(label: String): List<KeywordWord> {
        val folded = TextFold.fold(label)
        return tokenize(folded, folded).map { token ->
            KeywordWord(token.folded, requireDot = folded.getOrNull(token.end) == '.')
        }
    }

    /** Qualité de la correspondance entre un jeton et un mot de mot-clé : 1 exact, < 1 approché, null sinon. */
    fun wordMatch(token: Token, word: KeywordWord, lineText: String): Float? {
        if (word.requireDot && lineText.getOrNull(token.end) != '.') return null
        val variants = listOf(token.folded, token.letterized).distinct()
        if (variants.any { it == word.text }) return 1f
        if (word.text.length < MIN_FUZZY_LENGTH || token.folded in FUZZY_DENYLIST) return null
        val close = variants.any { variant ->
            variant.length >= MIN_FUZZY_LENGTH - 1 && variant.all { it.isLetter() } &&
                Levenshtein.distance(variant, word.text) <= 1
        }
        return if (close) FUZZY_QUALITY else null
    }

    fun findHits(line: PreparedLine): List<KeywordHit> {
        val tokens = line.tokens
        val hits = mutableListOf<KeywordHit>()
        var i = 0
        while (i < tokens.size) {
            var best: Pair<KeywordDef, Float>? = null
            for (def in ALL) {
                val n = def.words.size
                if (i + n > tokens.size || !adjacent(line.text, tokens, i, n)) continue
                var quality = 1f
                for (k in 0 until n) {
                    val q = wordMatch(tokens[i + k], def.words[k], line.text)
                    if (q == null) {
                        quality = 0f
                        break
                    }
                    quality = minOf(quality, q)
                }
                if (quality <= 0f) continue
                val current = best
                if (current == null || isBetter(def, quality, current.first, current.second)) best = def to quality
            }
            val match = best
            if (match == null) {
                i++
                continue
            }
            val (def, quality) = match
            val first = tokens[i]
            val last = tokens[i + def.words.size - 1]
            val end = if (def.words.last().requireDot) last.end + 1 else last.end
            hits += KeywordHit(
                field = def.field,
                keyword = def.label,
                lineIndex = line.index,
                start = first.start,
                end = end,
                box = line.boxOf(first.start, end),
                fuzzy = quality < 1f,
                weight = def.priority * quality,
            )
            i += def.words.size
        }
        return hits
    }

    fun isKeywordText(foldedWord: String): Boolean = ALL.any { def ->
        def.words.size == 1 && def.words[0].text == foldedWord
    }

    private fun isBetter(def: KeywordDef, quality: Float, other: KeywordDef, otherQuality: Float): Boolean {
        if (def.words.size != other.words.size) return def.words.size > other.words.size
        return def.priority * quality > other.priority * otherQuality
    }

    /** Les mots d'un mot-clé composé ne sont séparés que par des espaces ou une ponctuation légère. */
    private fun adjacent(text: String, tokens: List<Token>, from: Int, count: Int): Boolean {
        for (k in from until from + count - 1) {
            val gap = text.substring(tokens[k].end, tokens[k + 1].start)
            if (gap.length > 3 || gap.any { !it.isWhitespace() && it !in "-./°" }) return false
        }
        return true
    }
}
