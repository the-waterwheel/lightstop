package com.lightmeter.rawmeter

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCaptureSessionImpl
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RawCaptureCallbackOwnershipTest.HoldingSession::class])
class RawCaptureCallbackOwnershipTest {
    @Test fun priorRawAbortCannotFinishTheNextMeasurementInTheSameSession() {
        Fixture().use { f ->
            val first = f.startMetering()
            f.meter.cancel(f.handler)
            val second = f.startMetering()

            first.onCaptureSequenceAborted(f.session, 1)
            assertTrue(f.meter.isMeasuring)
            assertTrue(f.errors.isEmpty())

            second.onCaptureSequenceAborted(f.session, 2)
            assertFalse(f.meter.isMeasuring)
            assertEquals(1, f.errors.size)
        }
    }

    @Test fun priorVignettingFailureCannotFinishTheNextCalibration() {
        Fixture().use { f ->
            val first = f.startVignetting()
            f.vignetting.cancel(f.handler)
            val second = f.startVignetting()
            val failure = Shadow.newInstanceOf(CaptureFailure::class.java)

            first.onCaptureFailed(f.session, f.request(), failure)
            assertTrue(f.vignetting.isActive)
            assertTrue(f.errors.isEmpty())

            second.onCaptureFailed(f.session, f.request(), failure)
            assertFalse(f.vignetting.isActive)
            assertEquals(1, f.errors.size)
        }
    }

    @Implements(className = "android.hardware.camera2.impl.CameraCaptureSessionImpl", isInAndroidSdk = false)
    class HoldingSession : ShadowCameraCaptureSessionImpl() {
        val callbacks = mutableListOf<CameraCaptureSession.CaptureCallback>()

        @Implementation
        override fun capture(request: CaptureRequest, callback: CameraCaptureSession.CaptureCallback, handler: Handler): Int {
            callbacks += callback
            return callbacks.size
        }
    }

    private class Fixture : AutoCloseable {
        private val app = RuntimeEnvironment.getApplication()
        private val texture = SurfaceTexture(0)
        private val surface = Surface(texture)
        val handler = Handler(Looper.getMainLooper())
        val session = cameraObject<CameraCaptureSession>("CameraCaptureSessionImpl")
        private val shadow = Shadow.extract<HoldingSession>(session)
        private val device = cameraObject<CameraDevice>("CameraDeviceImpl").also {
            ReflectionHelpers.setField(it, "mCameraId", "0")
        }
        private val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        private val cameraInfo = CameraUiInfo(cameraId = "0", rawAvailable = true)
        private val context = RawMeteringContext(device, session, surface, handler, null, characteristics, cameraInfo, null)
        private val vignettingStore = VignettingCalibrationStore(app)
        val errors = mutableListOf<String>()
        val meter = RawLightMeter(CameraCalibrationStore(app), vignettingStore, { _, en -> en },
            object : RawLightMeterListener {
                override fun onRawCaptureResult(result: CaptureResult, totalResult: TotalCaptureResult) = Unit
                override fun onRawMeteringStarted(frameCount: Int) = Unit
                override fun onRawMeteringReading(reading: MeterReading) = Unit
                override fun onRawZoneBatchReading(results: List<ZoneMeteringResult>) = Unit
                override fun onRawMeteringError(message: String, meteringMode: MeteringMode, target: ZoneMeteringTarget?, meteringRoiFraction: Float?) {
                    errors += message
                }
            },
        )
        val vignetting = VignettingCalibrationCaptureCoordinator(
            vignettingStore, { _, en -> en }, { characteristics }, { cameraInfo },
            object : VignettingCalibrationCaptureListener {
                override fun onVignettingCaptureStarted() = Unit
                override fun onVignettingCaptureResult(result: TotalCaptureResult, effectiveResult: CaptureResult) = Unit
                override fun onVignettingCaptureFinished(info: VignettingCalibrationInfo) = Unit
                override fun onVignettingCaptureError(message: String) { errors += message }
            },
        )

        fun request(): CaptureRequest = meter.buildCaptureRequest(context)

        fun startMetering(): CameraCaptureSession.CaptureCallback {
            assertTrue(meter.start(context, 4f / 3f, 1f, MeteringMode.SPOT, null, null, null,
                ScreenToSensorCoordinateTransform(0, false)))
            return shadow.callbacks.last()
        }

        fun startVignetting(): CameraCaptureSession.CaptureCallback {
            assertTrue(vignetting.start(context, "0", ::request) {})
            return shadow.callbacks.last()
        }

        override fun close() {
            meter.cancel(handler)
            vignetting.cancel(handler)
            surface.release()
            texture.release()
        }

        private inline fun <reified T> cameraObject(simpleName: String): T =
            Shadow.newInstanceOf(Class.forName("android.hardware.camera2.impl.$simpleName")) as T
    }
}
