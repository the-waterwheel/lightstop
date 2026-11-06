package com.lightmeter.rawmeter

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Rational
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCaptureResult
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ExposurePreviewCalibrationCoordinatorTest {
    @Test fun twoFreshNeutralStableFramesPrepareTheManualComparison() {
        val f = Fixture()
        f.begin()
        f.frame()
        assertTrue(f.ready.isEmpty())
        f.frame()
        assertEquals(listOf(true), f.ready)
        assertTrue(f.coordinator.isActive)
        f.coordinator.updateComparison(1.0)
        assertEquals(f.baselineEv + 1.0, f.applied.last().first!!.appliedCameraEv100, .0001)
        f.advance(1_800)
        assertEquals(listOf(true), f.ready)
    }

    @Test fun freshBaselineAlsoPreparesAnAeOnlyCamera() {
        val f = Fixture(manual = false)
        f.begin()
        repeat(2) { f.frame() }
        assertEquals(listOf(true), f.ready)
        f.coordinator.updateComparison(1.0)
        assertNull(f.applied.last().first)
        assertEquals(-3, f.applied.last().second)
    }

    @Test fun timeoutCannotUseAnOldManualOrNeutralCachedResult() {
        for (mode in listOf(CaptureResult.CONTROL_AE_MODE_OFF, CaptureResult.CONTROL_AE_MODE_ON)) {
            val f = Fixture()
            f.latest = f.result(mode = mode)
            f.begin()
            f.advance(1_800)
            assertEquals(listOf(false), f.ready)
            assertFalse(f.coordinator.isActive)
            assertNull(f.coordinator.cameraId)
            val count = f.applied.size
            f.coordinator.updateComparison(1.0)
            assertEquals(count, f.applied.size)
        }
    }

    @Test fun aeCompensationCapabilityCannotReplaceMissingExposureMetadata() {
        val f = Fixture(manual = false)
        f.begin()
        repeat(3) { f.frame(validExposure = false) }
        assertTrue(f.ready.isEmpty())
        f.advance(1_800)
        assertEquals(listOf(false), f.ready)
    }

    @Test fun oldRequestTagsCannotSupplyThePreparationBaseline() {
        val f = Fixture()
        f.begin()
        repeat(2) { f.frame(request = f.request(sequence = f.sequence - 1)) }
        repeat(2) { f.frame(request = f.request(generation = f.generation - 1)) }
        assertTrue(f.ready.isEmpty())
        repeat(2) { f.frame() }
        assertEquals(listOf(true), f.ready)
    }

    @Test fun cachedOrDuplicateTimestampsCannotCountAsTwoFreshFrames() {
        val f = Fixture()
        val cached = f.result()
        f.latest = cached
        f.begin()
        val request = f.request()
        repeat(2) { f.coordinator.onCaptureResult(request, cached) }
        f.drain()
        assertTrue(f.ready.isEmpty())
        val fresh = f.result()
        repeat(2) { f.coordinator.onCaptureResult(request, fresh) }
        f.drain()
        assertTrue(f.ready.isEmpty())
        f.frame()
        assertEquals(listOf(true), f.ready)
    }

    @Test fun physicalMetadataCanUseOnlyItsPairedLogicalFrameTimestamp() {
        val f = Fixture()
        f.begin()
        val request = f.request()
        val physical = f.result(includeTimestamp = false)
        // Missing both timestamps cannot contribute a frame; repeating one paired timestamp
        // cannot supply the two distinct neutral frames needed for calibration either.
        f.coordinator.onCaptureResult(request, physical)
        f.coordinator.onCaptureResult(request, physical, f.timestamp)
        f.coordinator.onCaptureResult(request, physical, f.timestamp)
        f.drain()
        assertTrue(f.ready.isEmpty())
        f.coordinator.onCaptureResult(request, physical, f.timestamp + 33_333_333L)
        f.drain()
        assertEquals(listOf(true), f.ready)
    }

    @Test fun manualCompensatedAndUnstableFramesResetTheStableStreak() {
        val f = Fixture()
        f.begin()
        f.frame()
        f.frame(mode = CaptureResult.CONTROL_AE_MODE_OFF)
        f.frame()
        f.frame(compensation = 1)
        f.frame()
        f.frame(aeState = CaptureResult.CONTROL_AE_STATE_SEARCHING)
        f.frame()
        assertTrue(f.ready.isEmpty())
        f.frame()
        assertEquals(listOf(true), f.ready)
    }

    @Test fun missingNeutralControlsAreNotAssumedToBeAeZero() {
        val f = Fixture()
        f.begin()
        val request = f.request(mode = null, compensation = null)
        repeat(2) { f.frame(request = request, mode = null, compensation = null) }
        assertTrue(f.ready.isEmpty())
        f.advance(1_800)
        assertEquals(listOf(false), f.ready)
    }

    @Test fun sessionChangeRejectsEvenFramesTaggedWithTheOriginalSession() {
        val f = Fixture()
        f.begin()
        val oldRequest = f.request()
        f.frame(request = oldRequest)
        f.generation++
        f.frame(request = oldRequest)
        assertEquals(listOf(false), f.ready)
        assertFalse(f.coordinator.isActive)
        f.advance(1_800)
        assertEquals(listOf(false), f.ready)
    }

    @Test fun lensChangeAndCameraCloseCancelPreparation() {
        val lens = Fixture()
        lens.begin()
        lens.frame()
        lens.identity = CalibrationCaptureIdentity("other-route", "other-lens")
        lens.frame()
        assertEquals(listOf(false), lens.ready)
        assertFalse(lens.coordinator.isActive)

        val closed = Fixture()
        closed.begin()
        closed.frame()
        closed.coordinator.cancelForCameraClose()
        closed.advance(1_800)
        assertEquals(listOf(false), closed.ready)
        assertFalse(closed.coordinator.isActive)
    }

    private class Fixture(manual: Boolean = true) {
        val ready = mutableListOf<Boolean>()
        val applied = mutableListOf<Pair<ExposurePreviewManualExposure?, Int>>()
        var generation = 1
        var sequence = 10L
        var timestamp = 1_000_000_000L
        var identity = CalibrationCaptureIdentity("route", "lens")
        var latest: CaptureResult? = null
        val baselineEv = ExposurePreviewMath.cameraEv100(2.0, 10_000_000L, 100)!!
        private val handler = Handler(Looper.getMainLooper())
        private val chars = ShadowCameraCharacteristics.newCameraCharacteristics().also {
            shadowOf(it).set(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE, Range(100_000L, 1_000_000_000L))
            shadowOf(it).set(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION, 1_000_000_000L)
            shadowOf(it).set(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, Range(100, 3200))
            shadowOf(it).set(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, Range(-6, 6))
            shadowOf(it).set(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, Rational(1, 3))
        }
        private val device: CameraDevice
        val coordinator = ExposurePreviewCalibrationCoordinator(
            mainHandler = handler,
            cameraHandler = { handler },
            cameraGeneration = { generation },
            previewRequestSequence = { sequence },
            latestResult = { latest },
            characteristics = { chars },
            cameraInfo = { CameraUiInfo(aperture = 2f, manualSensorAvailable = manual) },
            calibrationIdentity = { identity },
            calibrationCameraId = { identity.routeId },
            applyPreview = { exposure, compensation -> sequence++; applied += exposure to compensation },
        )

        init {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
            val manager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = "calibration-${nextCameraId++}"
            shadowOf(manager).addCamera(id, chars)
            var opened: CameraDevice? = null
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { opened = camera }
                override fun onDisconnected(camera: CameraDevice) = Unit
                override fun onError(camera: CameraDevice, error: Int) = Unit
            }, handler)
            drain()
            device = checkNotNull(opened)
        }

        fun begin() = coordinator.begin { ready += it }
        fun drain() = shadowOf(Looper.getMainLooper()).idle()
        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

        fun request(
            generation: Int = this.generation,
            sequence: Long = this.sequence,
            mode: Int? = CaptureRequest.CONTROL_AE_MODE_ON,
            compensation: Int? = 0,
        ): CaptureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            setTag(PreviewRequestTag(generation, sequence, null))
            if (mode != null) set(CaptureRequest.CONTROL_AE_MODE, mode)
            if (compensation != null) set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
        }.build()

        fun result(
            mode: Int? = CaptureResult.CONTROL_AE_MODE_ON,
            compensation: Int? = 0,
            aeState: Int = CaptureResult.CONTROL_AE_STATE_CONVERGED,
            validExposure: Boolean = true,
            includeTimestamp: Boolean = true,
        ): CaptureResult {
            timestamp += 33_333_333L
            return ShadowCaptureResult.newCaptureResult().also {
                if (includeTimestamp) shadowOf(it).set(CaptureResult.SENSOR_TIMESTAMP, timestamp)
                if (mode != null) shadowOf(it).set(CaptureResult.CONTROL_AE_MODE, mode)
                if (compensation != null) shadowOf(it).set(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
                shadowOf(it).set(CaptureResult.CONTROL_AE_STATE, aeState)
                if (validExposure) {
                    shadowOf(it).set(CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
                    shadowOf(it).set(CaptureResult.SENSOR_SENSITIVITY, 100)
                    shadowOf(it).set(CaptureResult.LENS_APERTURE, 2f)
                }
            }
        }

        fun frame(
            request: CaptureRequest = request(),
            mode: Int? = CaptureResult.CONTROL_AE_MODE_ON,
            compensation: Int? = 0,
            aeState: Int = CaptureResult.CONTROL_AE_STATE_CONVERGED,
            validExposure: Boolean = true,
        ) {
            latest = result(mode, compensation, aeState, validExposure)
            coordinator.onCaptureResult(request, checkNotNull(latest))
            drain()
        }

        companion object { private var nextCameraId = 0 }
    }
}
