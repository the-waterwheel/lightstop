package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun missingCalibrationOrResultKeyIsUnsupported() {
        val provider = Camera2FocusDistanceProvider()
        val state = provider.start(context, capability.copy(calibration = null, resultKeyAvailable = false))
        assertEquals(DistanceMeasurementStatus.UNSUPPORTED, state.status)
        assertFalse(state.estimate?.isFresh ?: false)
    }
}
