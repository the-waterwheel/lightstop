package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleMeteringMathTest {
    @Test
    fun `angle stops follow requested variable intervals`() {
        assertEquals(
            listOf(1, 2, 3, 4, 5, 7, 9, 11, 13, 15, 20, 25, 30, 40, 50),
            AngleMeteringMath.selectableDegrees.toList(),
        )
    }

    @Test
    fun `fifty millimetre full frame lens supports twenty five but not thirty degrees`() {
        val maximum = requireNotNull(
            AngleMeteringMath.maximumSupportedDegrees(
                focalLengthMm = 50.0,
                sensorWidthMm = 36.0,
                sensorHeightMm = 24.0,
                sensorFrameAspect = 1.5,
                zoom = 1.0,
            ),
        )

        assertEquals(26.99, maximum, 0.02)
        assertEquals(11, AngleMeteringMath.maximumSupportedIndex(maximum))
    }

    @Test
    fun `angle converts to physical crop fraction and includes zoom`() {
        val oneDegree = requireNotNull(
            AngleMeteringMath.roiFraction(1, 50.0, 36.0, 24.0, 1.5, 1.0),
        )
        val zoomedMaximum = requireNotNull(
            AngleMeteringMath.maximumSupportedDegrees(50.0, 36.0, 24.0, 1.5, 2.0),
        )

        assertEquals(0.03636, oneDegree.toDouble(), 0.0001)
        assertEquals(13.69, zoomedMaximum, 0.02)
        assertEquals(8, AngleMeteringMath.maximumSupportedIndex(zoomedMaximum))
    }

    @Test
    fun `physical angle limit and raw roi ignore electronic display zoom`() {
        val physicalMaximum = requireNotNull(
            AngleMeteringMath.maximumPhysicalSupportedDegrees(50.0, 36.0, 24.0, 1.5),
        )
        val physicalFraction = requireNotNull(
            AngleMeteringMath.physicalRoiFraction(11, 50.0, 36.0, 24.0, 1.5),
        )
        val displayedAtTwoTimes = requireNotNull(
            AngleMeteringMath.roiFraction(11, 50.0, 36.0, 24.0, 1.5, 2.0),
        )

        assertEquals(26.99, physicalMaximum, 0.02)
        assertEquals(physicalFraction * 2f, displayedAtTwoTimes, 0.0001f)
    }

    @Test
    fun `invalid optical metadata cannot produce a crop`() {
        assertNull(AngleMeteringMath.maximumSupportedDegrees(0.0, 36.0, 24.0, 1.5, 1.0))
        assertNull(AngleMeteringMath.roiFraction(1, 5.0, 0.0, 4.0, 1.5, 1.0))
    }

    @Test
    fun `compatibility mode rejects angle and normalizes an existing selection`() {
        assertFalse(MeteringAreaPolicy.canSelect(MeteringMode.ANGLE, MeteringPipelineMode.FAST))
        assertTrue(MeteringAreaPolicy.canSelect(MeteringMode.ANGLE, MeteringPipelineMode.AUTO))
        assertEquals(
            MeteringMode.CENTER_WEIGHTED,
            MeteringAreaPolicy.resolveForPipeline(
                MeteringMode.ANGLE,
                MeteringPipelineMode.FAST,
            ),
        )
    }
}
