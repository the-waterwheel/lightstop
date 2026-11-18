package com.lightmeter.rawmeter

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCaptureResult
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MeteringPreviewBaselineCoordinatorTest {
    @Test fun calibrationTimeoutSkipsTheStageAndRejectsItsLateFrames() {
        val f = Fixture()
        val expired = f.startCalibration()
        f.frame(expired, validExposure = false)
        f.frame(expired, validExposure = false)
        assertTrue(f.outcomes.isEmpty())
        f.advance(2_500)
        assertEquals(listOf("skipped"), f.outcomes)

        val current = f.startCalibration()
        f.frame(expired)
        f.frame(current)
        assertEquals(listOf("skipped"), f.outcomes)
        f.frame(current)
        assertEquals(listOf("skipped", "capture"), f.outcomes)
        assertFalse(f.coordinator.isActive)
    }

    @Test fun ordinaryMeteringStillContinuesAfterItsShortWait() {
        val f = Fixture()
        f.coordinator.start(1) { f.outcomes += "capture" }
        f.advance(1_200)
        assertEquals(listOf("capture"), f.outcomes)
        assertFalse(f.coordinator.isActive)
    }

    private class Fixture {
        private val handler = Handler(Looper.getMainLooper())
        val coordinator = MeteringPreviewBaselineCoordinator { handler }
        val outcomes = mutableListOf<String>()
        private val device = (
            Shadow.newInstanceOf(Class.forName("android.hardware.camera2.impl.CameraDeviceImpl")) as CameraDevice
        ).also { ReflectionHelpers.setField(it, "mCameraId", "0") }

        fun startCalibration(): Long {
            coordinator.start(1, onUnavailable = { outcomes += "skipped" }) {
                outcomes += "capture"
            }
            return checkNotNull(coordinator.activeGeneration)
        }

        fun frame(generation: Long, validExposure: Boolean = true) {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                setTag(PreviewRequestTag(1, generation, generation))
            }.build()
            val result = ShadowCaptureResult.newCaptureResult().also {
                shadowOf(it).set(CaptureResult.CONTROL_AE_MODE, CaptureResult.CONTROL_AE_MODE_ON)
                shadowOf(it).set(CaptureResult.CONTROL_AE_STATE, CaptureResult.CONTROL_AE_STATE_CONVERGED)
                if (validExposure) {
                    shadowOf(it).set(CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
                    shadowOf(it).set(CaptureResult.SENSOR_SENSITIVITY, 100)
                }
            }
            coordinator.onCaptureResult(request, result)
        }

        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }
}
