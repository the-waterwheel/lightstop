package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraManager
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [CameraSessionOpenTimeoutTest.HoldingCameraManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CameraSessionOpenTimeoutTest {
    @Test fun missingOpenCallbackTimesOutAndLateOpenCannotTakeOverNextStage() {
        val fixture = Fixture()
        try {
            val firstCallback = fixture.open(1)
            fixture.advance(7_999)
            assertTrue(fixture.coordinator.isOpening)
            fixture.advance(1)
            assertEquals(listOf(1), fixture.timedOut)
            assertFalse(fixture.coordinator.isOpening)

            fixture.close()
            fixture.open(2)
            val lateDevice = Shadow.newInstanceOf(
                Class.forName("android.hardware.camera2.impl.CameraDeviceImpl"),
            ) as CameraDevice
            var lateDeviceClosed = false
            ReflectionHelpers.setField(lateDevice, "mDeviceExecutor", Executor(Runnable::run))
            ReflectionHelpers.setField(lateDevice, "mCallOnClosed", Runnable { lateDeviceClosed = true })
            firstCallback.onOpened(lateDevice)
            assertTrue(lateDeviceClosed)
            assertTrue(fixture.opened.isEmpty())
            assertTrue(fixture.coordinator.isOpening)
            fixture.advance(8_000)
            assertEquals(listOf(1, 2), fixture.timedOut)
        } finally {
            fixture.release()
        }
    }

    @Test fun closingWhileOpeningCancelsTheTimeout() {
        val fixture = Fixture()
        try {
            fixture.open(1)
            fixture.advance(1_000)
            fixture.close()
            fixture.advance(8_000)
            assertTrue(fixture.timedOut.isEmpty())
            assertFalse(fixture.coordinator.isOpening)
        } finally {
            fixture.release()
        }
    }

    @Implements(CameraManager::class)
    class HoldingCameraManager : ShadowCameraManager() {
        lateinit var pendingCallback: CameraDevice.StateCallback

        @Implementation
        fun openCamera(cameraId: String, callback: CameraDevice.StateCallback, handler: Handler?) {
            pendingCallback = callback
        }
    }

    private class Fixture {
        private val manager = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CAMERA_SERVICE) as CameraManager
        private val shadowManager = Shadow.extract<HoldingCameraManager>(manager)
        private val texture = SurfaceTexture(0)
        private val handler = Handler(Looper.getMainLooper())
        val opened = mutableListOf<Int>()
        val timedOut = mutableListOf<Int>()
        val coordinator = CameraSessionCoordinator(
            cameraManager = manager,
            onRawImageAvailable = {},
            onTrackingImageAvailable = {},
            sessionParametersProvider = { _, _ -> null },
            listener = object : CameraSessionCoordinatorListener {
                override fun onCameraOpened(generation: Int) { opened += generation }
                override fun onCameraOpenTimedOut(generation: Int, error: TimeoutException) {
                    timedOut += generation
                }
                override fun onSessionConfigured(
                    device: CameraDevice,
                    session: CameraCaptureSession,
                    previewSurface: Surface?,
                    generation: Int,
                ) = Unit
                override fun onSessionConfigurationFailed(generation: Int, error: Exception?) = Unit
                override fun onCameraDisconnected(generation: Int) = Unit
                override fun onCameraError(error: Int, generation: Int) = Unit
            },
        )

        fun open(generation: Int): CameraDevice.StateCallback {
            coordinator.configureOutputs(texture, Size(640, 480), null, null, handler)
            coordinator.open("0", null, generation, handler)
            return shadowManager.pendingCallback
        }

        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        fun close() = coordinator.close()
        fun release() {
            close()
            texture.release()
        }
    }
}
