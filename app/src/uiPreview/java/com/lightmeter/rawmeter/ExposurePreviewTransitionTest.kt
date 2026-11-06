package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Range
import android.util.Rational
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCaptureResult
import org.robolectric.Shadows.shadowOf
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExposurePreviewTransitionTest {
    @Test fun continuousMeteringWaitsForBothTheFadeAndMatchingSensorExposure() {
        val f = Fixture()
        assertFalse(f.state.readyForContinuousMetering)
        f.frame()
        assertTrue(f.state.readyForContinuousMetering)
        f.select(f.sourceEv + 3)
        assertFalse(f.state.readyForContinuousMetering)
        repeat(90) { f.frame() }
        assertTrue(f.state.readyForContinuousMetering)
        // A delayed result from the old neutral request must not start the next sample.
        f.frame(time = 10_000_000L)
        assertFalse(f.state.readyForContinuousMetering)
        f.frame()
        assertTrue(f.state.readyForContinuousMetering)
        f.state.reset()
        assertFalse(f.state.readyForContinuousMetering)
    }

    @Test fun stableActualManualExposureCanResumeMeteringAfterTheBoundedWait() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 1)
        repeat(60) { if (f.state.isTransitioning) f.frame(evOffset = -.2) }
        assertFalse(f.state.isTransitioning)
        repeat(20) { f.frame(evOffset = -.2) }
        assertFalse("Keep the normal request-matching grace period", f.state.readyForContinuousMetering)
        repeat(30) { f.frame(evOffset = -.2) }
        assertTrue("Meter using the stable HAL exposure and its own metadata", f.state.readyForContinuousMetering)
        assertEquals(f.sourceEv + 1, f.state.manualExposure!!.appliedCameraEv100, .0001)
        assertEquals(0, f.stalled)
    }

    @Test fun changingTheTargetRestartsTheActualExposureGracePeriod() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 1)
        repeat(90) { f.frame(evOffset = -.2) }
        assertTrue(f.state.readyForContinuousMetering)
        f.select(f.sourceEv + 2)
        assertFalse(f.state.readyForContinuousMetering)
        repeat(60) { if (f.state.isTransitioning) f.frame(evOffset = -.2) }
        assertFalse(f.state.isTransitioning)
        repeat(20) { f.frame(evOffset = -.2) }
        assertFalse(f.state.readyForContinuousMetering)
        repeat(30) { f.frame(evOffset = -.2) }
        assertTrue(f.state.readyForContinuousMetering)
    }

    @Test fun staleRequestsCannotUnlockTheActualExposureFallback() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 1)
        repeat(90) { f.frame(evOffset = -.2, matchesCurrentRequest = false) }
        assertFalse(f.state.readyForContinuousMetering)
        assertEquals(1, f.stalled)
        repeat(2) {
            f.frame(evOffset = -.2)
            assertFalse(f.state.readyForContinuousMetering)
        }
        f.frame(evOffset = -.2)
        assertTrue(f.state.readyForContinuousMetering)
    }

    @Test fun aFreshFrameIsRequiredAfterThePreviewStopsDeliveringResults() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 1)
        repeat(90) { f.frame(evOffset = -.2) }
        assertTrue(f.state.readyForContinuousMetering)
        f.timestamp += 600_000_000L
        assertFalse("A cached exposure must not unlock a frozen stream", f.state.readyForContinuousMetering)
        assertEquals(1, f.stalled)
        repeat(2) {
            f.frame(evOffset = -.2)
            assertFalse(f.state.readyForContinuousMetering)
        }
        f.frame(evOffset = -.2)
        assertTrue(f.state.readyForContinuousMetering)
    }

    @Test fun unstableOrMissingActualExposureReportsFailureOnceInsteadOfSilentlyRetrying() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 1)
        repeat(90) { f.frame(evOffset = if (it % 2 == 0) -.2 else -.8) }
        repeat(3) { assertFalse(f.state.readyForContinuousMetering) }
        assertEquals(1, f.stalled)
        repeat(10) { f.frame(missingExposureMetadata = true) }
        assertFalse(f.state.readyForContinuousMetering)
        assertEquals(1, f.stalled)
        repeat(3) { f.frame(evOffset = -.2) }
        assertTrue(f.state.readyForContinuousMetering)
    }

    @Test fun ignoredManualControlNeverUsesTheStableExposureFallback() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 1)
        repeat(90) { f.frame(evOffset = -.2, aeMode = CaptureResult.CONTROL_AE_MODE_ON) }
        assertFalse(f.state.readyForContinuousMetering)
        assertEquals(1, f.stalled)
    }

    @Test fun aeCompensationMismatchCanUseFreshStableActualExposureAfterTheWait() {
        val f = Fixture(manual = false)
        f.frame(); f.select(f.sourceEv - 1)
        repeat(60) { if (f.state.isTransitioning) f.frame(compensation = 2) }
        assertFalse(f.state.isTransitioning)
        repeat(20) { f.frame(compensation = 2) }
        assertFalse(f.state.readyForContinuousMetering)
        repeat(30) { f.frame(compensation = 2) }
        assertEquals(3, f.state.compensationSteps)
        assertTrue(f.state.readyForContinuousMetering)
        assertEquals(0, f.stalled)
    }

    @Test fun noLiveFramesDuringTransitionReportFailureWithoutEndingTheFade() {
        val f = Fixture()
        f.select(f.sourceEv + 1)
        assertTrue(f.state.isTransitioning)
        f.timestamp += 1_600_000_000L
        repeat(3) { assertFalse(f.state.readyForContinuousMetering) }
        assertEquals(1, f.stalled)
        assertTrue(f.state.isTransitioning)
        f.frame()
        repeat(90) { f.frame() }
        assertFalse(f.state.isTransitioning)
        assertTrue(f.state.readyForContinuousMetering)
    }

    @Test fun previewLimitsAreDeduplicatedAndClearedForCalibrationResetAndNeutralPreview() {
        for (manual in listOf(true, false)) {
            val f = Fixture(manual = manual)
            f.select(-20.0)
            assertNotNull(f.limits.single())
            repeat(10) { f.select(-20.0) }
            assertEquals(1, f.limits.size)
            f.state.applyCalibrationComparison(null, 0)
            assertNull(f.limits.last())
            f.state.apply(f.chars, f.info)
            assertNotNull(f.limits.last())
            f.state.reset(preserveSelection = true)
            assertNull(f.limits.last())
            f.state.apply(f.chars, f.info)
            assertNotNull(f.limits.last())
            f.state.clearRequestedSelection()
            assertNull(f.limits.last())
            f.select(-20.0)
            assertNotNull(f.limits.last())
            f.select(f.sourceEv)
            assertNull(f.limits.last())
        }
    }

    @Test fun measurementResultsFadeBothBrighterAndDarkerFromTheLivePreview() {
        for (delta in listOf(-3.0, 3.0)) {
            val f = Fixture()
            f.frame()
            f.select(f.sourceEv + delta)
            assertEquals(f.sourceEv, f.state.manualExposure!!.appliedCameraEv100, .00001)
            repeat(90) { f.frame() }
            assertFalse(f.state.isTransitioning)
            assertEquals(f.sourceEv + delta, f.state.manualExposure!!.appliedCameraEv100, .0001)
            val values = f.requests.mapNotNull { it.first?.appliedCameraEv100 }
            assertTrue(values.size > 8)
            values.zipWithNext().forEach { (a, b) ->
                assertTrue(kotlin.math.abs(b - a) < .23)
                assertTrue(if (delta > 0) b >= a else b <= a)
            }
        }
    }

    @Test fun rapidUiRefreshesDoNotRestartTheFadeOrFloodTheCamera() {
        val f = Fixture()
        f.frame()
        f.select(f.sourceEv + 3)
        repeat(120) {
            repeat(10) { f.select(f.sourceEv + 3) }
            f.frame(16_666_667L)
        }
        assertFalse(f.state.isTransitioning)
        assertEquals(f.sourceEv + 3, f.state.manualExposure!!.appliedCameraEv100, .0001)
        assertTrue("Bounded repeating requests: ${f.requests.size}", f.requests.size in 9..30)
    }

    @Test fun frameRateRoundingAroundThirtyFpsDoesNotHalveTheExposureUpdates() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 3)
        repeat(40) { f.frame(33_000_000L) }
        assertFalse(f.state.isTransitioning)
        assertTrue("Keep the frame cadence near 30 fps: ${f.requests.size}", f.requests.size in 14..25)
        assertEquals(f.sourceEv + 3, f.state.manualExposure!!.appliedCameraEv100, .0001)
    }

    @Test fun aNewTargetContinuesFromTheCurrentExposureAndReversesSmoothly() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 3)
        repeat(6) { f.frame() }
        val current = f.state.manualExposure!!
        f.select(f.sourceEv - 2)
        assertEquals(current.appliedCameraEv100, f.state.manualExposure!!.appliedCameraEv100, .0001)
        f.frame()
        assertTrue(f.state.manualExposure!!.appliedCameraEv100 < current.appliedCameraEv100)
        repeat(90) { f.frame() }
        assertEquals(f.sourceEv - 2, f.state.manualExposure!!.appliedCameraEv100, .0001)
    }

    @Test fun continuousGlobalUpdatesCanRetargetAnActiveFadeWithoutAnExposureJump() {
        val f=Fixture();val live=AutoMeterSession();f.frame()
        for (target in listOf(f.sourceEv+2,f.sourceEv+3,f.sourceEv-2,f.sourceEv-3)) {
            live.accept(MeterReading(target,.18,0.0,1,100,10_000_000,2f,MeteringSource.YUV_PREVIEW))
            f.select(live.reading!!.sceneEv100)
            repeat(10) { f.frame() }
        }
        repeat(90) { f.frame() }
        val values=f.requests.mapNotNull { it.first?.appliedCameraEv100 }
        assertTrue(values.zipWithNext().all { (a,b) -> kotlin.math.abs(b-a)<.23 })
        assertFalse(f.state.isTransitioning)
        assertEquals(live.reading!!.sceneEv100,f.state.manualExposure!!.appliedCameraEv100,.0001)
    }

    @Test fun aeOnlyCamerasWalkThroughCompensationStepsWithoutChangingMeteringMath() {
        val f = Fixture(manual = false)
        f.frame()
        f.select(f.sourceEv - 2)
        assertEquals(0, f.state.compensationSteps)
        repeat(60) { f.frame() }
        assertNull(f.state.manualExposure)
        assertEquals(6, f.state.compensationSteps)
        assertFalse(f.state.isTransitioning)
        assertEquals((1..6).toList(), f.requests.map { it.second })
    }

    @Test fun rawPauseDoesNotFinishTheFadeAndSessionRebuildWaitsForItsOwnFirstFrame() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 3)
        repeat(3) { f.frame() }
        val before = f.state.manualExposure!!.appliedCameraEv100
        f.frame(5_000_000_000L)
        assertTrue(f.state.manualExposure!!.appliedCameraEv100 - before < .31)
        f.state.reset(preserveSelection = true)
        f.state.apply(f.chars, f.info)
        assertNull(f.state.manualExposure)
        assertTrue(f.state.isTransitioning)
        f.frame(time = 20_000_000L)
        val newSource = ExposurePreviewMath.cameraEv100(2.0, 20_000_000L, 100)!!
        assertEquals(newSource, f.state.manualExposure!!.appliedCameraEv100, .0001)
        repeat(90) { f.frame() }
        assertEquals(f.sourceEv + 3, f.state.manualExposure!!.appliedCameraEv100, .0001)
    }

    @Test fun meteringCancelsInterpolationImmediatelyAndLeavesBaselineSubmissionToItsOwner() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv - 3)
        repeat(3) { f.frame() }
        val count = f.requests.size
        assertTrue(f.state.neutralizeForMetering())
        assertNull(f.state.manualExposure)
        assertEquals(0, f.state.compensationSteps)
        assertFalse(f.state.isTransitioning)
        assertEquals(count, f.requests.size)
        repeat(10) { f.frame(advance = false) }
        assertEquals(count, f.requests.size)
        assertTrue(f.state.isNeutral)
    }

    @Test fun calibrationComparisonsRemainImmediateAndCannotBeOverwrittenByAnOldFade() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 3)
        val comparison = ExposurePreviewManualExposure(40_000_000L, 100,
            ExposurePreviewMath.cameraEv100(2.0, 40_000_000L, 100)!!, false)
        f.state.clearRequestedSelection()
        f.state.applyCalibrationComparison(comparison, 0)
        val count = f.requests.size
        repeat(20) { f.frame(advance = false) }
        assertEquals(comparison, f.state.manualExposure)
        assertFalse(f.state.isTransitioning)
        assertEquals(count, f.requests.size)
        f.state.reset()
        f.state.apply(f.chars, f.info)
        assertNull(f.state.manualExposure)
    }

    @Test fun turningOffStillRequiresNeutralEvidenceBeforeTheNextMeasurement() {
        val f = Fixture()
        f.frame(); f.select(f.sourceEv + 3)
        repeat(4) { f.frame() }
        f.state.updateSelection(null)
        f.state.apply(f.chars, f.info)
        assertNull(f.state.manualExposure)
        assertFalse(f.state.isTransitioning)
        assertFalse("A submitted AE request is not yet a neutral sensor frame", f.state.isNeutral)
        val count = f.requests.size
        val inFlight = ShadowCaptureResult.newCaptureResult().also {
            shadowOf(it).set(CaptureResult.CONTROL_AE_MODE, CaptureResult.CONTROL_AE_MODE_OFF)
        }
        f.timestamp += 33_333_334L
        f.state.onPreviewFrame(inFlight, f.timestamp)
        assertTrue(f.state.neutralizeForMetering())
        assertEquals(count, f.requests.size)
        f.frame(advance = false)
        assertTrue(f.state.isNeutral)
        repeat(30) { f.frame() }
        assertEquals(count, f.requests.size)
    }

    @Test fun hardwareClampingIsTheFinalTargetAndMissingMetadataHasABoundedFallback() {
        val f = Fixture()
        f.select(-20.0)
        assertNull(f.state.manualExposure)
        repeat(3) {
            f.timestamp += 33_333_334L
            f.state.onPreviewFrame(ShadowCaptureResult.newCaptureResult(), f.timestamp)
        }
        assertNotNull(f.state.manualExposure)
        repeat(180) { f.frame() }
        assertFalse(f.state.isTransitioning)
        assertTrue(f.state.manualExposure!!.clamped)
        assertTrue(f.state.manualExposure!!.exposureTimeNs <= 66_666_667L)
        assertTrue(f.state.manualExposure!!.sensitivity <= 3200)
    }

    @Test fun unsupportedCamerasReportOnceAndNeverStartAnAnimationLoop() {
        val f = Fixture(manual = false, compensationRange = Range(0, 0))
        f.select(5.0); f.select(6.0)
        repeat(30) { f.frame() }
        assertEquals(1, f.unavailable)
        assertTrue(f.requests.isEmpty())
        assertFalse(f.state.isTransitioning)
    }

    private class Fixture(manual: Boolean = true, compensationRange: Range<Int> = Range(-6, 6)) {
        val requests = mutableListOf<Pair<ExposurePreviewManualExposure?, Int>>()
        val limits = mutableListOf<ExposurePreviewLimit?>()
        var unavailable = 0
        var stalled = 0
        var timestamp = 1_000_000_000L
        val sourceEv = ExposurePreviewMath.cameraEv100(2.0, 10_000_000L, 100)!!
        val info = CameraUiInfo(aperture = 2f, manualSensorAvailable = manual)
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics().also {
            shadowOf(it).set(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE, Range(100_000L, 1_000_000_000L))
            shadowOf(it).set(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION, 1_000_000_000L)
            shadowOf(it).set(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, Range(100, 3200))
            shadowOf(it).set(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, compensationRange)
            shadowOf(it).set(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, Rational(1, 3))
        }
        val state = ExposurePreviewStateCoordinator({ changed() }, { unavailable++ },
            onMeteringStalled = { stalled++ }, onLimitChanged = { limits += it }, nanoClock = { timestamp })
        private fun changed() { requests += state.manualExposure to state.compensationSteps }
        fun select(target: Double) {
            state.updateSelection(ExposurePreviewSelection(sourceEv, target, 0.0))
            state.apply(chars, info)
        }
        fun frame(
            deltaNs: Long = 33_333_334L,
            time: Long? = null,
            advance: Boolean = true,
            evOffset: Double = 0.0,
            matchesCurrentRequest: Boolean = true,
            compensation: Int? = null,
            aeMode: Int? = null,
            missingExposureMetadata: Boolean = false,
        ) {
            timestamp += deltaNs
            val applied = state.manualExposure
            val result = ShadowCaptureResult.newCaptureResult().also {
                if (!missingExposureMetadata) {
                    val exposure = time ?: applied?.exposureTimeNs ?: 10_000_000L
                    shadowOf(it).set(CaptureResult.SENSOR_EXPOSURE_TIME, (exposure * 2.0.pow(-evOffset)).toLong())
                    shadowOf(it).set(CaptureResult.SENSOR_SENSITIVITY, if (time != null) 100 else applied?.sensitivity ?: 100)
                    shadowOf(it).set(CaptureResult.LENS_APERTURE, 2f)
                }
                shadowOf(it).set(CaptureResult.CONTROL_AE_MODE, aeMode ?: if (applied == null || time != null) CaptureResult.CONTROL_AE_MODE_ON else CaptureResult.CONTROL_AE_MODE_OFF)
                shadowOf(it).set(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION, compensation ?: state.compensationSteps)
            }
            state.onPreviewFrame(result, timestamp, advance, matchesCurrentRequest)
        }
    }
}
