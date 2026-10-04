package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class FilmNegativeSamplingTest {
    @Test fun tinyToneCurveQuantisationIsCompatibleButRealChangeIsNot() {
        val first = evidence(1).copy(processingCurve = listOf(0f, .2f, .6f, 1f))
        assertTrue(first.compatible(evidence(2).copy(processingCurve = listOf(0f, .20001f, .601f, 1f))))
        assertFalse(first.compatible(evidence(2).copy(processingCurve = listOf(0f, .22f, .6f, 1f))))
        assertFalse(first.compatible(evidence(2).copy(processingCurve = emptyList())))
        assertFalse(first.compatible(evidence(2).copy(processingCurve = listOf(0f, Float.NaN, .6f, 1f))))
    }

    @Test fun stableBaseWindowCanTolerateSubPixelCurveRoundingAcrossFrames() {
        val window = FilmNegativeSampleWindow()
        assertNull(window.add(rgb, evidence(1).copy(processingCurve = listOf(.5f))))
        assertNull(window.add(rgb, evidence(2).copy(processingCurve = listOf(.5001f))))
        assertNotNull(window.add(rgb, evidence(3).copy(processingCurve = listOf(.4999f))))
    }
    private val rgb = floatArrayOf(0.8f, 0.6f, 0.4f)
    private fun evidence(frame: Int) = FilmNegativeFrameEvidence(
        frame * 33_000_000L, "camera-A", true, 10_000_000L, 100,
        listOf(2f, 1f, 1f, 1.5f), "sRGB-matrix-1")

    @Test fun joinsExactTimestampsInEitherArrivalOrder() {
        val matcher = FilmNegativeSampleMatcher()
        assertNull(matcher.frame(evidence(1).timestampNs, rgb))
        assertNull(matcher.evidence(evidence(1)))
        assertNull(matcher.evidence(evidence(2)))
        assertNull(matcher.frame(evidence(2).timestampNs, rgb))
        assertNull(matcher.frame(evidence(3).timestampNs, rgb))
        assertNotNull(matcher.evidence(evidence(3)))
    }

    @Test fun cannotBorrowMetadataFromAnotherFrameOrCountDuplicateFrames() {
        val matcher = FilmNegativeSampleMatcher()
        repeat(20) {
            assertNull(matcher.evidence(evidence(it + 1)))
            assertNull(matcher.frame(evidence(it + 1).timestampNs + 1, rgb))
        }
        matcher.clear()
        repeat(5) {
            assertNull(matcher.evidence(evidence(1)))
            assertNull(matcher.frame(evidence(1).timestampNs, rgb))
        }
    }

    @Test fun unlockedMissingOrChangedCalibrationCannotFormOneWindow() {
        val changes = listOf(evidence(2).copy(locked = false), evidence(2).copy(colorGains = emptyList()),
            evidence(2).copy(cameraKey = "camera-B"), evidence(2).copy(exposureNs = 20_000_000L),
            evidence(2).copy(processingKey = "other-curve"), evidence(2).copy(colorGains = listOf(3f, 1f, 1f, 1.5f)))
        for (changed in changes) {
            val window = FilmNegativeSampleWindow()
            assertNull(window.add(rgb, evidence(1)))
            assertNull(window.add(rgb, changed))
            assertNull(window.add(rgb, evidence(3)))
        }
    }

    @Test fun invalidPixelSampleAndLargeTimeGapResetWindow() {
        val window = FilmNegativeSampleWindow()
        assertNull(window.add(rgb, evidence(1)))
        assertNull(window.add(null, evidence(2)))
        assertNull(window.add(rgb, evidence(3)))
        assertNull(window.add(rgb, evidence(20)))
        assertNull(window.add(rgb, evidence(21)))
        assertNotNull(window.add(rgb, evidence(22)))
    }

    @Test fun slowlyDriftingColorMustAgreeWithAnchorNotOnlyPreviousFrame() {
        val window = FilmNegativeSampleWindow()
        assertNull(window.add(rgb, evidence(1)))
        assertNull(window.add(floatArrayOf(0.81f, 0.6f, 0.4f), evidence(2)))
        assertNull(window.add(floatArrayOf(0.82f, 0.6f, 0.4f), evidence(3)))
        assertNull(window.add(floatArrayOf(0.82f, 0.6f, 0.4f), evidence(4)))
        assertNotNull(window.add(floatArrayOf(0.82f, 0.6f, 0.4f), evidence(5)))
    }
}
