package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class DepthOfFieldMathTest {
    @Test
    fun `50mm f8 at five metres produces expected limits`() {
        val result = requireNotNull(
            DepthOfFieldMath.calculate(
                focalLengthMm = 50.0,
                aperture = 8.0,
                circleOfConfusionMm = 0.03,
                focusDistanceM = 5.0,
            ),
        )

        assertEquals(10.47, result.hyperfocalDistanceM, 0.02)
        assertEquals(3.39, result.nearLimitM, 0.02)
        assertEquals(9.49, requireNotNull(result.farLimitM), 0.03)
    }

    @Test
    fun `far limit becomes infinity beyond hyperfocal distance`() {
        val result = requireNotNull(DepthOfFieldMath.calculate(50.0, 8.0, 0.03, 20.0))

        assertNull(result.farLimitM)
        assertTrue(result.nearLimitM > 0.0)
    }

    @Test
    fun `focus at infinity starts at the hyperfocal distance`() {
        val result = requireNotNull(
            DepthOfFieldMath.calculate(50.0, 8.0, 0.03, Double.POSITIVE_INFINITY),
        )

        assertEquals(result.hyperfocalDistanceM, result.nearLimitM, 0.0001)
        assertNull(result.farLimitM)
    }

    @Test
    fun `distance ruler is logarithmic and bounded`() {
        assertEquals(0.0, DepthOfFieldMath.distanceFraction(0.1), 0.0001)
        assertEquals(0.5, DepthOfFieldMath.distanceFraction(sqrt(10.0)), 0.0001)
        assertEquals(1.0, DepthOfFieldMath.distanceFraction(null), 0.0001)
        assertEquals(sqrt(10.0), DepthOfFieldMath.distanceForFraction(0.5), 0.0001)
        assertTrue(DepthOfFieldMath.distanceForFraction(1.0).isInfinite())
    }

    @Test
    fun `custom format and circle limits reject unsafe values`() {
        assertNull(DepthOfFieldMath.customFormat(0.5, 24.0))
        assertNull(DepthOfFieldMath.customFormat(36.0, 400.0))
        assertNull(DepthOfFieldMath.calculate(50.0, 8.0, 2.0, 5.0))
    }

    @Test
    fun `calculator session keeps selections while recomputing results`() {
        val session = DepthOfFieldSession()
        session.reset(FrameFormat.ALL.first(), aperture = 5.6, fullFrameEquivalentMm = 50.0)
        val originalNear = requireNotNull(session.result).nearLimitM

        session.selectCircleOfConfusion(0.02)
        session.selectFocusIndex(DepthOfFieldMath.focusDistancesM.indexOfFirst { it == 5.0 })
        session.selectFocalIndex(DepthOfFieldMath.commonFocalLengthsMm.indexOfFirst { it == 85.0 })
        session.selectApertureIndex(ExposureMath.apertures.indexOfFirst { it == 8.0 }, ExposureMath.apertures)

        assertEquals(0.02, session.circleOfConfusionMm, 0.0001)
        assertEquals(5.0, session.focusDistanceM(), 0.0001)
        assertEquals(85.0, session.focalLengthMm(), 0.0001)
        assertEquals(8.0, session.selectedAperture, 0.0001)
        assertTrue(requireNotNull(session.result).nearLimitM != originalNear)
    }
}
