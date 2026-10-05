package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2FocusDistanceProviderTest {
    private val context = DistanceContext(7, "0", physicalIdentityKnown = true)
    private val capability = FocusDistanceCapability(
        minimumDiopters = 10f,
        calibration = CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
        resultKeyAvailable = true,
        physicalIdentityKnown = true,
    )

    @Test
    fun onlyLockedOrPassiveFocusedStationaryFramesBecomeDistanceSamples() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability)
        assertNull(provider.onFrame(context, 1L, CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN,
            CameraMetadata.LENS_STATE_STATIONARY, 1f))
        assertNull(provider.onFrame(context, 2L, CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CameraMetadata.LENS_STATE_MOVING, 1f))
        assertNull(provider.onFrame(context, 3L, CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CameraMetadata.LENS_STATE_STATIONARY, 0f))
    }

    @Test
    fun stableDiopterSamplesProduceLockedPhysicalEstimate() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability)
        var state: DistanceMeasurementState? = null
        repeat(5) { index ->
            state = provider.onFrame(
                context, 100_000_000L + index * 100_000_000L,
                CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CameraMetadata.LENS_STATE_STATIONARY,
                0.5f + index * 0.002f,
            )
        }
        val estimate = state?.estimate
        assertNotNull(estimate)
        assertEquals(2.0, estimate!!.meters, 0.03)
        assertEquals(DistanceSource.FOCUS_CALIBRATED, estimate.source)
    }

    @Test
    fun approximateFocusNeverClaimsHighQuality() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability.copy(
            calibration = CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE,
        ))
        var state: DistanceMeasurementState? = null
        repeat(10) { index ->
            state = provider.onFrame(context, 100_000_000L + index * 50_000_000L,
                CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                CameraMetadata.LENS_STATE_STATIONARY, 1f)
        }
        assertEquals(DistanceQuality.LOW, state?.estimate?.quality)
    }

    @Test
    fun restartingSamePhysicalCameraKeepsFreshEstimateWhileNewAfSamplesArrive() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability)
        var measured: DistanceMeasurementState? = null
        repeat(5) { index ->
            measured = provider.onFrame(
                context,
                100_000_000L + index * 100_000_000L,
                CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CameraMetadata.LENS_STATE_STATIONARY,
                0.5f,
            )
        }

        val restarted = provider.start(context, capability)

        assertEquals(DistanceMeasurementStatus.AVAILABLE, restarted.status)
        assertEquals(measured?.estimate, restarted.estimate)
    }

    @Test
    fun restartingDifferentPhysicalCameraNeverReusesDistance() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability)
        repeat(5) { index ->
            provider.onFrame(
                context,
                100_000_000L + index * 100_000_000L,
                CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CameraMetadata.LENS_STATE_STATIONARY,
                0.5f,
            )
        }

        val restarted = provider.start(context.copy(cameraIdentity = "2"), capability)

        assertEquals(DistanceMeasurementStatus.WAITING_FOR_FOCUS, restarted.status)
        assertNull(restarted.estimate)
    }

    @Test
    fun missingCalibrationOrResultKeyIsUnsupported() {
        val provider = Camera2FocusDistanceProvider()
        val state = provider.start(context, capability.copy(calibration = null, resultKeyAvailable = false))
        assertEquals(DistanceMeasurementStatus.UNSUPPORTED, state.status)
        assertFalse(state.estimate?.isFresh ?: false)
    }

    private fun feed(provider: Camera2FocusDistanceProvider, firstTimestamp: Long, diopters: Float,
        count: Int = 10): DistanceMeasurementState? {
        var state: DistanceMeasurementState? = null
        repeat(count) { index ->
            state = provider.onFrame(context, firstTimestamp + index * 50_000_000L,
                CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                CameraMetadata.LENS_STATE_STATIONARY, diopters)
        }
        return state
    }

    @Test fun expiredProviderRecoversWithoutRestartAndWithoutMixingOldSamples() {
        var now = 1_000_000_000L
        val provider = Camera2FocusDistanceProvider(clock = { now })
        provider.start(context, capability)
        assertEquals(2.0, feed(provider, 100_000_000L, 0.5f)!!.estimate!!.meters, 0.0)
        now += 3_000_000_000L
        assertEquals(DistanceMeasurementStatus.SAMPLING, feed(provider, 3_100_000_000L, 1f, 1)!!.status)
        assertEquals(1.0, feed(provider, 3_200_000_000L, 1f)!!.estimate!!.meters, 0.0)
    }

    @Test fun smallDistanceChangeUpdatesValueBoundsConfidenceAndTimestampTogether() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability)
        val old = feed(provider, 100_000_000L, 2f, 5)!!.estimate!!
        val next = feed(provider, 500_000_000L, 1f/0.6f)!!.estimate!!
        assertEquals(0.5, old.meters, 0.0)
        assertEquals(0.6, next.meters, 1e-6)
        assertTrue(next.lowerMeters!! > old.lowerMeters!!)
        assertTrue(next.confidence > old.confidence)
        assertTrue(next.timestampNs > old.timestampNs)
        assertEquals(DistanceQuality.MEDIUM, next.quality)
    }

    @Test fun elapsedClockPreventsReuseEvenIfNoFramesArrived() {
        var now = 1_000_000_000L
        val provider = Camera2FocusDistanceProvider(clock = { now })
        provider.start(context, capability)
        feed(provider, 100_000_000L, 1f)
        now += DISTANCE_TTL_NS + 1
        assertEquals(DistanceMeasurementStatus.WAITING_FOR_FOCUS, provider.start(context, capability).status)
    }

    @Test fun missingDistanceMetadataCannotProduceSamplesLater() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability.copy(resultKeyAvailable = false))
        assertNull(feed(provider, 100_000_000L, 1f))
    }

    @Test fun uncalibratedAndLegacyUnknownMetadataProduceExplicitBestEffortEstimates() {
        for (calibration in listOf(null, CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED)) {
            val provider = Camera2FocusDistanceProvider()
            assertEquals(DistanceMeasurementStatus.WAITING_FOR_FOCUS,
                provider.start(context, capability.copy(calibration = calibration)).status)
            val state = feed(provider, 100_000_000L, 0.5f)!!
            val estimate = state.estimate!!
            assertEquals(2.0, estimate.meters, 0.0)
            assertEquals(DistanceSource.FOCUS_ESTIMATED, estimate.source)
            assertEquals(DistanceQuality.LOW, estimate.quality)
            assertTrue(estimate.isApproximate)
            assertNull(estimate.lowerMeters)
            assertNull(estimate.upperMeters)
            assertTrue(estimate.confidence <= 0.30)
            assertFalse(DistanceFusionEngine.usableForFlash(estimate))
            assertEquals(2.0, state.effectiveMetersForFlash!!, 0.0)
        }
    }

    @Test fun missingPhysicalIdDoesNotDisableLegacyFocusEstimation() {
        val provider = Camera2FocusDistanceProvider()
        val legacyContext = context.copy(physicalIdentityKnown = false)
        provider.start(legacyContext, capability.copy(physicalIdentityKnown = false))
        var state: DistanceMeasurementState? = null
        repeat(5) { index -> state = provider.onFrame(legacyContext, index * 333_000_000L,
            CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED, CameraMetadata.LENS_STATE_STATIONARY, 1f) }
        assertEquals(DistanceSource.FOCUS_ESTIMATED, state!!.estimate!!.source)
        assertEquals(1.0, state!!.estimate!!.meters, 0.0)
    }

    @Test fun estimatedScaleStillRejectsFailedFocusInvalidCodesAndImplausibleReciprocals() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability.copy(calibration = null))
        assertNull(provider.onFrame(context, 1L, CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
            CameraMetadata.LENS_STATE_STATIONARY, 1f))
        assertNull(provider.onFrame(context, 2L, CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CameraMetadata.LENS_STATE_STATIONARY, Float.NaN))
        assertNull(feed(provider, 100_000_000L, 0.001f)?.estimate)
        provider.start(context, capability.copy(calibration = null, minimumDiopters = 1000f))
        assertNull(feed(provider, 100_000_000L, 100f)?.estimate)
    }

    @Test fun threeFpsPreviewCanProduceDistanceRegardlessOfCalibrationClass() {
        for (calibration in listOf(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE,
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED)) {
            val provider = Camera2FocusDistanceProvider()
            provider.start(context, capability.copy(calibration = calibration))
            var state: DistanceMeasurementState? = null
            repeat(5) { index -> state = provider.onFrame(context, 100_000_000L + index * 333_000_000L,
                CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED, CameraMetadata.LENS_STATE_STATIONARY, 0.5f) }
            assertEquals(DistanceMeasurementStatus.AVAILABLE, state!!.status)
            assertEquals(2.0, state!!.effectiveMetersForFlash!!, 0.0)
        }
    }

    @Test fun farQuantisationWidensExposureUncertaintyAndReducesConfidence() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability)
        val near = feed(provider, 100_000_000L, 1f)!!.estimate!!
        val far = feed(provider, 1_000_000_000L, 0.125f)!!.estimate!!
        assertTrue(DistanceFusionEngine.uncertaintyEv(far) > DistanceFusionEngine.uncertaintyEv(near))
        assertTrue(far.confidence < near.confidence)
    }

    @Test fun autofocusScanBreaksSampleWindowAndOutOfOrderFramesCannotRestoreIt() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability)
        feed(provider, 100_000_000L, 0.5f, 4)
        provider.onFrame(context, 400_000_000L, CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN,
            CameraMetadata.LENS_STATE_MOVING, 0.5f)
        assertNull(feed(provider, 300_000_000L, 0.5f, 1))
        assertEquals(DistanceMeasurementStatus.SAMPLING, feed(provider, 500_000_000L, 1f, 1)!!.status)
    }

    @Test fun motionAndUncontrolledFocusTargetReduceTrustEvenForStableMetadata() {
        val provider = Camera2FocusDistanceProvider()
        provider.start(context, capability)
        val stationary = feed(provider, 100_000_000L, 0.5f)!!.estimate!!
        var moving: DistanceMeasurementState? = null
        repeat(10) { index ->
            moving = provider.onFrame(context, 1_000_000_000L + index * 50_000_000L,
                CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                CameraMetadata.LENS_STATE_STATIONARY, 0.5f, motionConfidence=0.2)
        }
        assertTrue(moving!!.estimate!!.confidence < stationary.confidence)
        assertFalse(DistanceFusionEngine.usableForFlash(moving!!.estimate!!))
        provider.start(context, capability.copy(targetConfidence=0.45))
        val unknownTarget = feed(provider, 2_000_000_000L, 0.5f)!!.estimate!!
        assertFalse(DistanceFusionEngine.usableForFlash(unknownTarget))
    }
}
