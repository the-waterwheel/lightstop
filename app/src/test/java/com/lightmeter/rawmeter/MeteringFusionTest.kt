package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringFusionTest {
    private fun rawStat(
        ev100: Double,
        before: Double = ev100,
        applied: Double = 0.0,
        luma: Double = 0.5,
    ) = MeteringFrameStat(
        ev100 = ev100,
        luma = luma,
        clipped = 0.0,
        captureIso = 800,
        exposureTimeNs = 1_000_000L,
        aperture = 2.0f,
        ev100BeforeUserCalibration = before,
        appliedUserCorrectionEv = applied,
    )

    @Test
    fun `raw sample uses the same fused median as the displayed value`() {
        val reading = MeteringFusion.fuse(
            listOf(rawStat(9.0), rawStat(10.0), rawStat(12.0), rawStat(9.0)),
            MeteringSource.RAW,
        )!!
        assertEquals(9.5, reading.sceneEv100, 1e-9)
        val sample = reading.calibrationSample!!
        assertEquals(9.5, sample.ev100BeforeUserCalibration, 1e-9)
        assertEquals(9.5, reading.sceneEv100 - sample.appliedUserCorrectionEv, 1e-9)
    }

    @Test
    fun `raw sample is invariant to frame order`() {
        val a = MeteringFusion.fuse(
            listOf(rawStat(9.0), rawStat(10.0), rawStat(12.0), rawStat(9.0)),
            MeteringSource.RAW,
        )!!.calibrationSample!!.ev100BeforeUserCalibration
        val b = MeteringFusion.fuse(
            listOf(rawStat(12.0), rawStat(9.0), rawStat(10.0), rawStat(9.0)),
            MeteringSource.RAW,
        )!!.calibrationSample!!.ev100BeforeUserCalibration
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun `applied user correction is excluded from the before value`() {
        // Display EV already includes +1 EV; the sample must not.
        val reading = MeteringFusion.fuse(
            listOf(rawStat(ev100 = 10.0, before = 9.0, applied = 1.0)),
            MeteringSource.RAW,
        )!!
        assertEquals(10.0, reading.sceneEv100, 1e-9)
        assertEquals(9.0, reading.calibrationSample!!.ev100BeforeUserCalibration, 1e-9)
        assertEquals(1.0, reading.calibrationSample!!.appliedUserCorrectionEv, 1e-9)
    }

    @Test
    fun `a mid-burst correction change makes the raw sample unusable`() {
        val reading = MeteringFusion.fuse(
            listOf(rawStat(10.0, before = 9.0, applied = 1.0), rawStat(11.0, before = 9.0, applied = 2.0)),
            MeteringSource.RAW,
        )!!
        assertTrue(reading.calibrationSample!!.appliedUserCorrectionEv.isNaN())
        assertFalse(reading.calibrationSample!!.isUsableForSave())
    }

    @Test
    fun `processed sample keeps one frame tuple`() {
        val reading = MeteringFusion.fuse(
            listOf(rawStat(ev100 = 5.0, before = 4.5, applied = 0.5, luma = 0.2)),
            MeteringSource.YUV_PREVIEW,
        )!!
        val sample = reading.calibrationSample!!
        assertEquals(4.5, sample.ev100BeforeUserCalibration, 1e-9)
        assertEquals(0.5, sample.appliedUserCorrectionEv, 1e-9)
        assertEquals(0.2, sample.inputLuma!!, 1e-9)
    }
}
