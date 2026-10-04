package com.lightmeter.rawmeter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ColorTemperatureMathTest {
    private val identity = Matrix3(
        doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        ),
    )
    private val calibration = SensorColorCalibration(identity, 2_850)

    @Test
    fun estimatesDaylightFromNeutralRawPatch() {
        val reading = ColorTemperatureMath.estimate(sampleAt(6_500), calibration)

        assertNotNull(reading)
        assertEquals(6_500.0, reading!!.kelvin.toDouble(), 50.0)
    }

    @Test
    fun estimatesTungstenFromNeutralRawPatch() {
        val reading = ColorTemperatureMath.estimate(sampleAt(2_850), calibration)

        assertNotNull(reading)
        assertEquals(2_850.0, reading!!.kelvin.toDouble(), 50.0)
    }

    @Test
    fun rejectsClippedOrDarkPatches() {
        val clipped = RawColorSample(1.0, 0.6, 0.4, 0.2, 1_000)
        val dark = RawColorSample(0.001, 0.001, 0.001, 0.0, 1_000)

        assertEquals(null, ColorTemperatureMath.estimate(clipped, calibration))
        assertEquals(null, ColorTemperatureMath.estimate(dark, calibration))
    }

    @Test
    fun matrixMultiplicationKeepsRowMajorSemantics() {
        val matrix = Matrix3(
            doubleArrayOf(
                1.0, 2.0, 3.0,
                0.0, 1.0, 0.0,
                2.0, 0.0, 1.0,
            ),
        )

        assertArrayEquals(doubleArrayOf(14.0, 2.0, 5.0), matrix * doubleArrayOf(1.0, 2.0, 3.0), 1e-9)
    }

    private fun sampleAt(kelvin: Int): RawColorSample {
        val xyz = ColorTemperatureMath.whitePointXyz(kelvin)
        val scale = 0.45 / xyz.maxOrNull()!!
        return RawColorSample(
            red = xyz[0] * scale,
            green = xyz[1] * scale,
            blue = xyz[2] * scale,
            clippedFraction = 0.0,
            sampleCount = 2_000,
        )
    }
}
