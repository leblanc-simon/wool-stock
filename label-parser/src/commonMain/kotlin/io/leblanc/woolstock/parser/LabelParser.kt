package io.leblanc.woolstock.parser

import kotlin.math.max
import kotlin.math.min

/**
 * Extrait bain, marque et coloris d'une liste de lignes OCR (une seule frame).
 *
 * 1. Repère les mots-clés (bain / coloris) et les regroupe quand ils se suivent (« Coloris/Bain »).
 * 2. Cherche la valeur sur la même ligne, sinon à droite (même bande) ou juste en dessous.
 * 3. Valide les valeurs (poids, métrages, aiguilles, EAN rejetés) et corrige prudemment l'OCR.
 * 4. Arbitre entre bain et coloris pour ne jamais attribuer la même valeur aux deux.
 */
class LabelParser(brands: BrandDictionary = BrandDictionary.default()) {
    private val brandMatcher = BrandMatcher(brands)

    fun parse(lines: List<OcrLine>, ean: String? = null): LabelParseResult {
        val prepared = lines.mapIndexed { index, line -> PreparedLine(index, line, ean) }
        val hits = prepared.flatMap { if (it.isBarcode) emptyList() else Keywords.findHits(it) }
        val session = Session(prepared, hits, ean)

        val lots = mutableListOf<ValueCandidate>()
        val colors = mutableListOf<ColorCandidate>()
        session.groups.forEach { group -> session.collect(group, lots, colors) }
        val (lot, color) = resolve(lots, colors)

        return LabelParseResult(
            dyeLot = lot?.toCandidate(),
            brand = brandMatcher.match(prepared),
            colorCode = color?.code?.let { it.copy(score = color.score).toCandidate() },
            colorName = color?.name?.let { name ->
                Candidate(name.text, (color.score * NAME_CONFIDENCE).coerceIn(0f, 1f), name.span.line, box = name.box)
            },
            keywordHits = hits,
        )
    }

    private data class Span(val line: Int, val start: Int, val end: Int) {
        fun overlaps(other: Span) = line == other.line && start < other.end && other.start < end
    }

    private data class ValueCandidate(val value: String, val raw: String, val score: Float, val span: Span, val box: Rect) {
        fun toCandidate() = Candidate(value, score.coerceIn(0f, 1f), span.line, raw, box)
    }

    private data class NamePart(val text: String, val span: Span, val box: Rect)

    private data class ColorCandidate(val code: ValueCandidate?, val name: NamePart?, val score: Float)

    /** Mots-clés consécutifs d'une même ligne, séparés seulement par de la ponctuation. */
    private class HitGroup(val hits: List<KeywordHit>, val line: PreparedLine, val regionEnd: Int) {
        val fields: List<LabelField> = hits.map { it.field }.distinct()
        val end: Int = hits.last().end
        val box: Rect = hits.map { it.box }.reduce(Rect::union)
        val weight: Float = hits.maxOf { it.weight }

        /** Zone où se trouve normalement la valeur sur la même ligne. */
        val region = Span(line.index, end, regionEnd)
    }

    /** Zone d'une autre ligne où chercher une valeur, avec le facteur lié à la distance. */
    private class Region(val line: PreparedLine, val start: Int, val end: Int, val factor: Float)

    private inner class Session(val lines: List<PreparedLine>, hits: List<KeywordHit>, val ean: String?) {
        private val hitsByLine: Map<Int, List<KeywordHit>> = hits.groupBy { it.lineIndex }
        val groups: List<HitGroup> = lines.flatMap { line -> groupsOf(line, hitsByLine[line.index].orEmpty()) }

        fun collect(group: HitGroup, lots: MutableList<ValueCandidate>, colors: MutableList<ColorCandidate>) {
            if (group.fields.size == 1) collectSingle(group, group.fields.single(), lots, colors)
            else collectPaired(group, lots, colors)
        }

        private fun collectSingle(
            group: HitGroup,
            field: LabelField,
            lots: MutableList<ValueCandidate>,
            colors: MutableList<ColorCandidate>,
        ) {
            val sameLine = Region(group.line, group.end, group.regionEnd, SAME_LINE_FACTOR)
            val found = extract(field, sameLine, group, group.weight, lots, colors)
            if (found) return
            spatialRegions(group.line, group.box, splitOnSlash = false).forEach { region ->
                extract(field, region, group, group.weight, lots, colors)
            }
        }

        /** « Coloris/Bain : 052/7812 » ou un en-tête de tableau « Color Lote » avec les valeurs dessous. */
        private fun collectPaired(group: HitGroup, lots: MutableList<ValueCandidate>, colors: MutableList<ColorCandidate>) {
            val line = group.line
            val start = skipValuePrefix(line.text, group.end, group.regionEnd)
            val sameLine = valueChunks(line, start, group.regionEnd, splitOnSlash = true)
            if (sameLine.size >= group.fields.size) {
                assignInOrder(group, line, sameLine, SAME_LINE_FACTOR, lots, colors)
                return
            }
            pairingLine(group)?.let { (region, values) ->
                assignInOrder(group, region.line, values, region.factor, lots, colors)
            }
            // Repli : chaque mot-clé cherche sa propre valeur sous lui (utile avec les boîtes de mots).
            for (hit in group.hits) {
                spatialRegions(line, hit.box, splitOnSlash = true).forEach { region ->
                    val scaled = Region(region.line, region.start, region.end, region.factor * PAIRED_FALLBACK_FACTOR)
                    extract(hit.field, scaled, group, hit.weight, lots, colors, singleChunk = true)
                }
            }
        }

        private fun assignInOrder(
            group: HitGroup,
            line: PreparedLine,
            values: List<Chunk>,
            factor: Float,
            lots: MutableList<ValueCandidate>,
            colors: MutableList<ColorCandidate>,
        ) {
            group.fields.zip(values).forEach { (field, chunk) ->
                val weight = group.hits.filter { it.field == field }.maxOf { it.weight }
                val region = Region(line, chunk.start, chunk.end, factor)
                extract(field, region, group, weight, lots, colors, singleChunk = true)
            }
        }

        /** Ajoute le candidat trouvé dans la région ; renvoie vrai si un candidat valide a été trouvé. */
        private fun extract(
            field: LabelField,
            region: Region,
            group: HitGroup,
            weight: Float,
            lots: MutableList<ValueCandidate>,
            colors: MutableList<ColorCandidate>,
            singleChunk: Boolean = false,
        ): Boolean {
            val ownership = if (isOwnedByOther(region, group)) OTHER_KEYWORD_PENALTY else 1f
            val score = weight * region.factor * ownership
            return when (field) {
                LabelField.DYE_LOT -> extractLot(region, score, singleChunk)?.also { lots += it } != null
                LabelField.COLOR -> extractColor(region, score, singleChunk)?.also { colors += it } != null
            }
        }

        private fun extractLot(region: Region, score: Float, singleChunk: Boolean): ValueCandidate? {
            val line = region.line
            val start = if (singleChunk) region.start else skipValuePrefix(line.text, region.start, region.end)
            val chunk = valueChunks(line, start, region.end, splitOnSlash = singleChunk).firstOrNull() ?: return null
            return codeCandidate(line, chunk, score, ValueRules::isPlausibleDyeLot)
        }

        private fun extractColor(region: Region, score: Float, singleChunk: Boolean): ColorCandidate? {
            val line = region.line
            val start = if (singleChunk) region.start else skipValuePrefix(line.text, region.start, region.end)
            val chunks = valueChunks(line, start, region.end, splitOnSlash = singleChunk)
            if (chunks.isEmpty()) return null
            val first = codeCandidate(line, chunks[0], score, ValueRules::isPlausibleColorCode)
            val nameWords = mutableListOf<Chunk>()
            var code = first
            if (first != null) {
                chunks.drop(1).takeWhile { ValueRules.isColorNameWord(it.text) }.take(MAX_NAME_WORDS).forEach { nameWords += it }
            } else {
                chunks.takeWhile { ValueRules.isColorNameWord(it.text) }.take(MAX_NAME_WORDS).forEach { nameWords += it }
                if (nameWords.isEmpty()) return null
                code = chunks.getOrNull(nameWords.size)?.let { codeCandidate(line, it, score, ValueRules::isPlausibleColorCode) }
            }
            val name = nameWords.takeIf { it.isNotEmpty() }?.let { words ->
                val from = words.first().start
                val to = words.last().end
                NamePart(line.text.substring(from, to), Span(line.index, from, to), line.boxOf(from, to))
            }
            val finalScore = if (code == null) score * NAME_ONLY_FACTOR else score
            return ColorCandidate(code, name, finalScore)
        }

        private fun codeCandidate(
            line: PreparedLine,
            chunk: Chunk,
            score: Float,
            isPlausible: (String, String, String?) -> Boolean,
        ): ValueCandidate? {
            if (!isPlausible(chunk.text, line.text.substring(chunk.end), ean)) return null
            val value = ValueRules.normalizeDigits(chunk.text)
            val adjusted = if (value != chunk.text) score * CORRECTED_FACTOR else score
            return ValueCandidate(value, chunk.text, adjusted, Span(line.index, chunk.start, chunk.end), line.boxOf(chunk.start, chunk.end))
        }

        /** Morceaux de valeur d'une ligne, en ignorant ceux qui chevauchent un mot-clé. */
        private fun valueChunks(line: PreparedLine, from: Int, to: Int, splitOnSlash: Boolean): List<Chunk> {
            val lineHits = hitsByLine[line.index].orEmpty()
            return chunks(line.text, from, to, splitOnSlash).filter { chunk ->
                lineHits.none { it.start < chunk.end && chunk.start < it.end }
            }
        }

        private fun isOwnedByOther(region: Region, group: HitGroup): Boolean = groups.any { other ->
            other !== group && other.line.index == region.line.index &&
                region.start >= other.region.start && region.start < other.region.end
        }

        /** Fin de la zone de valeur : prochain mot-clé de la ligne ou fin de ligne. */
        private fun regionEnd(line: PreparedLine, from: Int): Int =
            hitsByLine[line.index].orEmpty().map { it.start }.filter { it >= from }.minOrNull() ?: line.text.length

        /** Valeurs candidates à droite (même bande verticale) ou juste en dessous du mot-clé. */
        private fun spatialRegions(origin: PreparedLine, anchor: Rect, splitOnSlash: Boolean): List<Region> {
            val h = origin.height.toFloat()
            val regions = mutableListOf<Region>()
            for (line in lines) {
                if (line.index == origin.index || line.isBarcode) continue
                val overlapY = min(line.box.bottom, origin.box.bottom) - max(line.box.top, origin.box.top)
                val chunks = valueChunks(line, 0, line.text.length, splitOnSlash)
                if (overlapY >= SAME_BAND_OVERLAP * min(line.height, origin.height)) {
                    val chunk = chunks.firstOrNull { line.boxOf(it.start, it.end).centerX > anchor.right } ?: continue
                    val gap = max(0, line.boxOf(chunk.start, chunk.end).left - anchor.right) / h
                    if (gap > MAX_RIGHT_GAP) continue
                    val factor = RIGHT_FACTOR * (1f - DISTANCE_DECAY * gap / MAX_RIGHT_GAP)
                    regions += Region(line, chunk.start, regionEnd(line, chunk.start), factor)
                } else if (line.box.centerY > origin.box.centerY + h / 2) {
                    val gap = max(0, line.box.top - origin.box.bottom) / h
                    if (gap > MAX_BELOW_GAP) continue
                    val left = anchor.left - h
                    val right = anchor.right + h
                    val chunk = chunks
                        .map { it to line.boxOf(it.start, it.end) }
                        .map { (chunk, box) -> chunk to (min(right, box.right.toFloat()) - max(left, box.left.toFloat())) }
                        .filter { it.second > 0 }
                        .maxByOrNull { it.second }
                        ?.first ?: continue
                    val factor = BELOW_FACTOR * (1f - DISTANCE_DECAY * gap / MAX_BELOW_GAP)
                    regions += Region(line, chunk.start, regionEnd(line, chunk.start), factor)
                }
            }
            return regions
        }

        /** Ligne voisine la plus proche dont le nombre de valeurs correspond au nombre de mots-clés. */
        private fun pairingLine(group: HitGroup): Pair<Region, List<Chunk>>? {
            val origin = group.line
            val h = origin.height.toFloat()
            return lines.asSequence()
                .filter { it.index != origin.index && !it.isBarcode }
                .mapNotNull { line ->
                    val overlapY = min(line.box.bottom, origin.box.bottom) - max(line.box.top, origin.box.top)
                    val overlapX = min(line.box.right, group.box.right) - max(line.box.left, group.box.left)
                    when {
                        overlapY >= SAME_BAND_OVERLAP * min(line.height, origin.height) && line.box.left >= group.box.right -> {
                            val gap = (line.box.left - group.box.right) / h
                            if (gap > MAX_RIGHT_GAP) null
                            else line to RIGHT_FACTOR * (1f - DISTANCE_DECAY * gap / MAX_RIGHT_GAP)
                        }
                        line.box.centerY > origin.box.centerY + h / 2 && overlapX > 0 -> {
                            val gap = max(0, line.box.top - origin.box.bottom) / h
                            if (gap > MAX_BELOW_GAP) null
                            else line to BELOW_FACTOR * (1f - DISTANCE_DECAY * gap / MAX_BELOW_GAP)
                        }
                        else -> null
                    }
                }
                .maxByOrNull { it.second }
                ?.let { (line, factor) ->
                    val values = valueChunks(line, 0, regionEnd(line, 0), splitOnSlash = true)
                    if (values.size == group.fields.size) Region(line, 0, line.text.length, factor) to values else null
                }
        }
    }

    private fun groupsOf(line: PreparedLine, hits: List<KeywordHit>): List<HitGroup> {
        if (hits.isEmpty()) return emptyList()
        val sorted = hits.sortedBy { it.start }
        val clusters = mutableListOf(mutableListOf(sorted.first()))
        for (hit in sorted.drop(1)) {
            val previous = clusters.last().last()
            if (isSeparatorGap(line.text.substring(previous.end, hit.start))) clusters.last() += hit
            else clusters += mutableListOf(hit)
        }
        return clusters.mapIndexed { i, cluster ->
            val end = clusters.getOrNull(i + 1)?.first()?.start ?: line.text.length
            HitGroup(cluster, line, end)
        }
    }

    private fun resolve(
        lots: List<ValueCandidate>,
        colors: List<ColorCandidate>,
    ): Pair<ValueCandidate?, ColorCandidate?> {
        fun compatible(color: ColorCandidate?, lot: ValueCandidate?): Boolean {
            if (color == null || lot == null) return true
            val code = color.code
            if (code != null && (code.span.overlaps(lot.span) || code.value.equals(lot.value, ignoreCase = true))) return false
            return color.name?.span?.overlaps(lot.span) != true
        }

        val bestLot = lots.maxByOrNull { it.score }
        val bestColor = colors.maxByOrNull { it.score }
        val lotFirst = bestLot to colors.filter { compatible(it, bestLot) }.maxByOrNull { it.score }
        val colorFirst = lots.filter { compatible(bestColor, it) }.maxByOrNull { it.score } to bestColor
        fun total(pair: Pair<ValueCandidate?, ColorCandidate?>) =
            (pair.first?.score ?: 0f) + (pair.second?.score ?: 0f) * COLOR_WEIGHT_IN_ARBITRATION
        return if (total(lotFirst) >= total(colorFirst)) lotFirst else colorFirst
    }

    private companion object {
        const val SAME_LINE_FACTOR = 1.0f
        const val RIGHT_FACTOR = 0.9f
        const val BELOW_FACTOR = 0.85f
        const val DISTANCE_DECAY = 0.4f
        const val SAME_BAND_OVERLAP = 0.5f

        /** Distances maximales exprimées en hauteurs de ligne du mot-clé. */
        const val MAX_RIGHT_GAP = 8f
        const val MAX_BELOW_GAP = 3f

        const val OTHER_KEYWORD_PENALTY = 0.5f
        const val PAIRED_FALLBACK_FACTOR = 0.9f
        const val CORRECTED_FACTOR = 0.95f
        const val NAME_ONLY_FACTOR = 0.8f
        const val NAME_CONFIDENCE = 0.9f
        const val COLOR_WEIGHT_IN_ARBITRATION = 0.8f
        const val MAX_NAME_WORDS = 3

        private const val PREFIX_SEPARATORS = ":.-#=|°/*~"
        private val VALUE_PREFIXES = listOf("nummer", "number", "numero", "num", "nr", "no", "n°", "n")

        /** Saute séparateurs et préfixes (« : », « N° », « No. », « Nr ») avant une valeur. */
        fun skipValuePrefix(text: String, from: Int, to: Int): Int {
            var i = from
            while (true) {
                while (i < to && (text[i].isWhitespace() || text[i] in PREFIX_SEPARATORS)) i++
                val rest = TextFold.fold(text.substring(i, to))
                val prefix = VALUE_PREFIXES.firstOrNull { p ->
                    rest.startsWith(p) && (p.endsWith('°') || rest.length == p.length || !rest[p.length].isLetterOrDigit())
                } ?: return i
                i += prefix.length
            }
        }

        fun isSeparatorGap(gap: String): Boolean {
            val words = tokenize(gap).map { it.folded }
            return words.all { it in setOf("n", "no", "nr") } &&
                gap.all { it.isLetterOrDigit() || it.isWhitespace() || it in "/:.-|,&+°" }
        }
    }
}
