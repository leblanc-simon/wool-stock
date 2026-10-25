package eu.leblancsimon.woolstock.scan

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.leblancsimon.woolstock.parser.LabelField
import eu.leblancsimon.woolstock.parser.Rect
import eu.leblancsimon.woolstock.resources.Res
import eu.leblancsimon.woolstock.resources.format_raw_to_value
import org.jetbrains.compose.resources.stringResource

private val LineColor = Color.White.copy(alpha = 0.6f)
private val DyeLotKeywordColor = Color(0x66FFEB3B)
private val ColorKeywordColor = Color(0x5500BCD4)
private val DyeLotColor = Color(0xFF4CAF50)
private val ColorCodeColor = Color(0xFF00BCD4)

/**
 * Boîtes OCR (coordonnées déjà ramenées dans le repère de la PreviewView), mots-clés surlignés,
 * bain entouré, avec la valeur brute et la valeur corrigée.
 */
@Composable
fun DebugOverlay(frame: DebugFrame, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val result = frame.result
    val dyeLotLabel = result.dyeLot?.let {
        if (it.rawValue != it.value) stringResource(Res.string.format_raw_to_value, it.rawValue, it.value) else it.value
    }
    Canvas(modifier) {
        frame.lines.forEach { drawBox(it.box, LineColor, Stroke(1.dp.toPx())) }
        result.keywordHits.forEach { hit ->
            drawBox(hit.box, if (hit.field == LabelField.DYE_LOT) DyeLotKeywordColor else ColorKeywordColor, fill = true)
        }
        result.colorCode?.box?.let { drawBox(it, ColorCodeColor, Stroke(2.dp.toPx())) }
        val lot = result.dyeLot
        val lotBox = lot?.box
        if (lotBox != null && dyeLotLabel != null) {
            val pad = 8.dp.toPx()
            drawOval(
                color = DyeLotColor,
                topLeft = Offset(lotBox.left - pad, lotBox.top - pad),
                size = Size(lotBox.width + 2 * pad, lotBox.height + 2 * pad),
                style = Stroke(3.dp.toPx()),
            )
            drawText(
                textMeasurer = measurer,
                text = dyeLotLabel,
                topLeft = Offset(lotBox.left.toFloat(), lotBox.bottom + pad),
                style = TextStyle(color = DyeLotColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, background = Color.Black.copy(alpha = 0.6f)),
            )
        }
    }
}

private fun DrawScope.drawBox(box: Rect, color: Color, stroke: Stroke? = null, fill: Boolean = false) {
    val topLeft = Offset(box.left.toFloat(), box.top.toFloat())
    val size = Size(box.width.toFloat(), box.height.toFloat())
    if (fill) drawRect(color, topLeft, size) else drawRect(color, topLeft, size, style = stroke ?: Stroke(1f))
}
