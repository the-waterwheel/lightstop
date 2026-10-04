package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class FilmNegativeDisplayGeometryTest {
    private fun map(matrix: FloatArray, x: Float, y: Float) =
        (matrix[0] * x + matrix[3] * y + matrix[6]) to
            (matrix[1] * x + matrix[4] * y + matrix[7])

    @Test fun cropKeepsTheSampleCenterAndEqualPixelScaleInEveryRotation() {
        for (degrees in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val m = FilmNegativeDisplayGeometry.matrix(1080, 1440, 1260, 2800, degrees, mirror)
            val center = map(m, 0.5f, 0.5f)
            assertEquals(0.5f, center.first, 1e-6f)
            assertEquals(0.5f, center.second, 1e-6f)
            val x = map(m, 0.5f + 100f / 1260, 0.5f)
            val y = map(m, 0.5f, 0.5f + 100f / 2800)
            fun length(p: Pair<Float, Float>): Double {
                val dx = (p.first - center.first) * 1080
                val dy = (p.second - center.second) * 1440
                return kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
            }
            assertEquals(length(x), length(y), 0.001)
        }
    }

    @Test fun sameAspectUsesTheWholeFrameAndSquareCropsOnlyTheLongAxis() {
        val whole = FilmNegativeDisplayGeometry.matrix(1080, 1440, 1260, 1680, 0, false)
        assertEquals(0f, map(whole, 0f, 0f).first, 1e-6f)
        assertEquals(0f, map(whole, 0f, 0f).second, 1e-6f)
        val crop = FilmNegativeDisplayGeometry.matrix(1080, 1440, 1260, 1260, 0, false)
        assertEquals(0f, map(crop, 0f, 0f).first, 1e-6f)
        assertEquals(0.125f, map(crop, 0f, 0f).second, 1e-6f)
    }
}
