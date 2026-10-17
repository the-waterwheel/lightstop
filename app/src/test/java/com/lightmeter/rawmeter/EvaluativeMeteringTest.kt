package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class EvaluativeMeteringTest {
    private fun summary(vararg areas: Pair<Int, Double>) = EvaluativeMeteringAlgorithm.summarize(
        areas.flatMap { (count, ev) -> List(count) { ev } }.toDoubleArray(), 0.0, 0.0,
    )!!
    private fun stat(base: Double, high: Double, baseReliable: Boolean = true, highReliable: Boolean = true) =
        MeteringFrameStat(base, 0.18, if (highReliable) 0.0 else 0.02, 100, 10_000_000L, 2f,
            evaluative = EvaluativeFrameStats(base, high, baseReliable, highReliable, !highReliable))

    @Test fun uniformSceneAndOrdinaryContrastDoNotReceiveAutomaticUnderexposure() {
        for (stats in listOf(summary(1000 to 12.0), summary(200 to 11.0, 600 to 12.0, 200 to 13.0))) {
            val decision = EvaluativeMeteringAlgorithm.decide(stats.baseEv100, stats.highlightEv100)
            assertEquals(12.0, decision.recommendedEv100, 1e-10)
            assertEquals(0.0, decision.exposureReductionEv, 0.0)
        }
    }

    @Test fun tinyBrightAreaDoesNotReplaceTheFrameExposure() {
        val stats = summary(998 to 12.0, 2 to 22.0)
        assertEquals(12.0, stats.baseEv100, 1e-10)
        assertEquals(12.0, stats.highlightEv100, 1e-10)
    }

    @Test fun substantialBrightAreaGetsTheSpecifiedLimitedCorrection() {
        val stats = summary(900 to 12.0, 100 to 15.0)
        assertEquals(12.0 + 1.0 / 6, stats.baseEv100, 1e-10)
        val result = EvaluativeMeteringAlgorithm.decide(stats.baseEv100, stats.highlightEv100)
        assertEquals(1.0 / 3, result.exposureReductionEv, 1e-10)
        assertEquals(12.5, result.recommendedEv100, 1e-10)
    }

    @Test fun highContrastStopsAtHalfAStopAndRetainsTheUnresolvedPressure() {
        val stats = summary(500 to 8.0, 500 to 16.0)
        val result = EvaluativeMeteringAlgorithm.decide(stats.baseEv100, stats.highlightEv100)
        assertEquals(12.0, stats.baseEv100, 1e-10)
        assertEquals(0.5, result.exposureReductionEv, 0.0)
        assertEquals(1.0, result.highlightResidualEv, 1e-10)
    }

    @Test fun fractionalQuantileBoundariesPreserveAreaWithSmallSampleCounts() {
        val a = doubleArrayOf(0.0, 10.0)
        assertEquals(5.0, EvaluativeMeteringAlgorithm.intervalMean(a, .05, .95), 1e-10)
        assertEquals(10.0, EvaluativeMeteringAlgorithm.intervalMean(a, .98, .995), 1e-10)
    }

    @Test fun normalizationTranslationOrderingAndSamplingDensityPreserveTheDecision() {
        val original = summary(900 to 12.0, 100 to 15.0)
        val shifted = summary(1800 to 6.0, 200 to 9.0)
        val reversed = summary(100 to 15.0, 900 to 12.0)
        assertEquals(original.baseEv100 - 6, shifted.baseEv100, 1e-10)
        assertEquals(original.highlightEv100 - 6, shifted.highlightEv100, 1e-10)
        assertEquals(original, reversed)
    }

    @Test fun clippingOnlyInvalidatesTheStatisticsWhoseQuantilesCanBeCensored() {
        val samples = DoubleArray(1000) { 12.0 }
        assertTrue(EvaluativeMeteringAlgorithm.summarize(samples.copyOf(), .002, 0.0)!!.highlightReliable)
        val clipped = EvaluativeMeteringAlgorithm.summarize(samples.copyOf(), .02, 0.0)!!
        assertTrue(clipped.baseReliable)
        assertFalse(clipped.highlightReliable)
        assertFalse(EvaluativeMeteringAlgorithm.summarize(samples.copyOf(), .2, 0.0)!!.baseReliable)
        assertFalse(EvaluativeMeteringAlgorithm.summarize(samples.copyOf(), 0.0, .2)!!.baseReliable)
        val dark = EvaluativeMeteringAlgorithm.summarize(samples.copyOf(), 0.0, 1.0)!!
        assertFalse(dark.highlightReliable)
        assertFalse(dark.highlightClipped) // A dark frame must never cause a shorter exposure retry.
    }

    @Test fun invalidSamplesAreNeverSilentlyRemovedAndReweighted() {
        assertNull(EvaluativeMeteringAlgorithm.summarize(doubleArrayOf(12.0, Double.NaN), 0.0, 0.0))
        assertNull(EvaluativeMeteringAlgorithm.summarize(doubleArrayOf(), 0.0, 0.0))
    }

    @Test fun recoveredShortExposureHighlightsUseTheOriginalGoodBase() {
        val original = stat(12.0, 15.0, highReliable = false)
        val shortExposure = stat(10.0, 15.0, baseReliable = false)
        val combined = EvaluativeMeteringFusion.recoverBase(shortExposure, original)
        val reading = MeteringFusion.fuse(listOf(combined), MeteringSource.RAW)!!
        assertEquals(12.0, reading.sceneEv100, 0.0)
        assertEquals(12.5, reading.evaluative!!.recommendedEv100, 0.0)
        assertTrue(reading.evaluative.highlightsConfirmed)
        assertNull(reading.calibrationSample)
    }

    @Test fun stillClippedAtCameraExposureLimitReturnsBaseWithUnconfirmedHighlights() {
        val original = stat(12.0, 20.0, highReliable = false)
        val shortExposure = stat(10.0, 20.0, baseReliable = false, highReliable = false)
        val combined = EvaluativeMeteringFusion.recoverBase(shortExposure, original)
        val reading = MeteringFusion.fuse(listOf(combined), MeteringSource.RAW)!!
        assertEquals(12.0, reading.evaluative!!.recommendedEv100, 0.0)
        assertFalse(reading.evaluative.highlightsConfirmed)
        assertTrue(ReadingQualityPolicy.evaluate(reading).lowConfidence)
    }

    @Test fun protectedExposureCanRetainTheOriginalBaseEvenBeforeItReachesTheNoiseFloor() {
        val original = stat(12.0, 15.0, highReliable = false)
        val protected = stat(11.9, 15.0)
        val combined = EvaluativeMeteringFusion.recoverBase(protected, original, preferReference = true)
        assertEquals(12.0, combined.ev100, 0.0)
        assertEquals(15.0, combined.evaluative!!.highlightEv100, 0.0)
    }

    @Test fun entirelyCensoredBaseCannotProduceAValidReading() {
        assertNull(MeteringFusion.fuse(listOf(stat(12.0, 15.0, baseReliable = false)), MeteringSource.RAW))
    }

    @Test fun independentFrameSummariesAreFusedBeforeTheSingleFinalDecision() {
        val result = MeteringFusion.fuse(listOf(stat(11.0, 14.0), stat(13.0, 15.0)), MeteringSource.RAW)!!
        assertEquals(12.0, result.sceneEv100, 0.0)
        assertEquals(0.0, result.evaluative!!.exposureReductionEv, 0.0)
    }

    @Test fun evaluationNeverChangesZoneOrCalibrationLocalMethods() {
        assertEquals(MeteringMode.SPOT, MeteringAreaPolicy.localMode(MeteringMode.AVERAGE))
        for (mode in listOf(MeteringMode.SPOT, MeteringMode.CENTER_WEIGHTED, MeteringMode.ANGLE)) {
            assertEquals(mode, MeteringAreaPolicy.localMode(mode))
        }
    }

    @Test fun brightEndSaturationKeepsTakingThreeStopStepsUntilItClears() {
        fun next(stage: Int) = RawExposureRetryPolicy.nextStage(
            RawExposureRetryInput(0, stage, .02, true, MeteringMode.AVERAGE),
        )
        for (stage in 0 until 20) {
            assertEquals(stage + 1, next(stage))
            assertEquals(3 * (stage + 1), RawExposureRetryPolicy.exposureReductionEv(next(stage)!!, MeteringMode.AVERAGE))
            assertNull(RawExposureRetryPolicy.nextStage(RawExposureRetryInput(0, stage, 0.0, false, MeteringMode.AVERAGE)))
        }
        assertNull(RawExposureRetryPolicy.nextStage(RawExposureRetryInput(0, 0, .002, false, MeteringMode.AVERAGE)))
    }

    @Test fun colorMatrixOrderingAndGreenPositionsMatchTheExistingRawLuminanceMath() {
        assertArrayEquals(doubleArrayOf(.21, .36, .36, .07), EvaluativeColorMath.coefficients(null, null), 0.0)
        val result = EvaluativeColorMath.coefficients(doubleArrayOf(2.0, 1.0, 1.5, 3.0),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        assertArrayEquals(doubleArrayOf(.4252, .3576, .5364, .2166), result, 1e-10)
    }
}
