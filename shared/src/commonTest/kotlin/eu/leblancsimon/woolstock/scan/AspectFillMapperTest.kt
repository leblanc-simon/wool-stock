package eu.leblancsimon.woolstock.scan

import eu.leblancsimon.woolstock.parser.Rect
import kotlin.test.Test
import kotlin.math.roundToInt
import kotlin.test.assertEquals

class AspectFillMapperTest {
    @Test
    fun sameAspectRatioOnlyScales() {
        // Image 1080 × 1920 dans une vue 360 × 640 points, écran à 3 pixels par point.
        val mapper = AspectFillMapper(1080.0, 1920.0, 360.0, 640.0, 3.0)
        assertEquals(Rect(0, 0, 1080, 1920), mapper.map(0.0, 0.0, 1.0, 1.0))
        // Le haut de l'image est y = 1 chez Vision : une boîte en haut à gauche reste en haut.
        assertEquals(Rect(0, 0, 540, 192), mapper.map(0.0, 0.9, 0.5, 0.1))
    }

    @Test
    fun tallerViewCropsTheSides() {
        // Vue 390 × 844 : plus étroite que l'image 9:16, qui est agrandie puis rognée à gauche et à droite.
        val mapper = AspectFillMapper(1080.0, 1920.0, 390.0, 844.0, 1.0)
        val scale = 844.0 / 1920.0
        val hidden = (1080.0 * scale - 390.0) / 2
        val centre = mapper.map(0.25, 0.25, 0.5, 0.5)
        assertEquals((270.0 * scale - hidden).roundToInt(), centre.left)
        assertEquals(211, centre.top)
        assertEquals((810.0 * scale - hidden).roundToInt(), centre.right)
        assertEquals(633, centre.bottom)
    }

    @Test
    fun widerViewCropsTopAndBottom() {
        val mapper = AspectFillMapper(1000.0, 1000.0, 400.0, 200.0, 2.0)
        // L'image carrée prend 400 × 400 points, dont 100 cachés en haut et en bas.
        assertEquals(Rect(0, -200, 800, 600), mapper.map(0.0, 0.0, 1.0, 1.0))
        assertEquals(Rect(0, 0, 800, 400), mapper.map(0.0, 0.25, 1.0, 0.5))
    }
}
