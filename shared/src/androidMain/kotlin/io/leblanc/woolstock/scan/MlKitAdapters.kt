package io.leblanc.woolstock.scan

import com.google.mlkit.vision.text.Text
import io.leblanc.woolstock.parser.OcrLine
import io.leblanc.woolstock.parser.OcrWord
import io.leblanc.woolstock.parser.Rect

/** Adaptateur ML Kit → structure indépendante consommée par le parseur. */
fun Text.toOcrLines(): List<OcrLine> = textBlocks.flatMap { block ->
    block.lines.mapNotNull { line ->
        val box = line.boundingBox ?: return@mapNotNull null
        OcrLine(
            text = line.text,
            box = box.toParserRect(),
            words = line.elements.mapNotNull { element ->
                element.boundingBox?.let { OcrWord(element.text, it.toParserRect()) }
            },
        )
    }
}

fun android.graphics.Rect.toParserRect() = Rect(left, top, right, bottom)
