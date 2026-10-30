package io.leblanc.woolstock.scan

import io.leblanc.woolstock.parser.Rect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ramène une boîte normalisée de Vision (0 à 1, origine en bas à gauche de l'image) dans le
 * repère d'un aperçu qui remplit la vue en rognant l'image (aspect fill), en pixels : le même
 * repère que les boîtes ML Kit sur Android, où DebugOverlay dessine.
 *
 * [imageWidth] et [imageHeight] sont ceux de l'image analysée, déjà droite ; [viewWidth] et
 * [viewHeight] ceux de la vue, en points ; [pixelsPerPoint] la densité de l'écran.
 */
internal class AspectFillMapper(
    private val imageWidth: Double,
    private val imageHeight: Double,
    viewWidth: Double,
    viewHeight: Double,
    private val pixelsPerPoint: Double,
) {
    private val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
    private val offsetX = (viewWidth - imageWidth * scale) / 2
    private val offsetY = (viewHeight - imageHeight * scale) / 2

    fun map(x: Double, y: Double, width: Double, height: Double): Rect {
        val left = x * imageWidth * scale + offsetX
        val top = (1 - y - height) * imageHeight * scale + offsetY
        val right = left + width * imageWidth * scale
        val bottom = top + height * imageHeight * scale
        return Rect(left.px(), top.px(), right.px(), bottom.px())
    }

    private fun Double.px() = (this * pixelsPerPoint).roundToInt()
}
