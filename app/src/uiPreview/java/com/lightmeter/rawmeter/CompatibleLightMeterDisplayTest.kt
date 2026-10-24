package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import java.time.Duration
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

/** Real ISP analysis with controlled display frames and CaptureResult delivery order; no HAL/GL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CompatibleLightMeterDisplayTest {
    @Test fun metadataAlreadyCachedCompletesWithTheExistingFastPath() {
        val f = Fixture()
        f.results.put(f.view.timestampNs, result(f.view.timestampNs, iso = 100))
        f.start()
        drain()
        assertEquals(100, f.readings.single().captureIso)
        assertEquals(MeteringSource.ISP_PREVIEW, f.readings.single().source)
        assertTrue(f.view.bitmaps.single().isRecycled)
        assertFalse(f.meter.isMeasuring)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun lateMatchingMetadataUsesTheRetainedFrameEvenWhenTheDisplayHasAdvanced() {
        val f = Fixture()
        f.start()
        drain()
        val timestamp = f.view.timestampNs
        val bitmap = f.view.bitmaps.single()
        assertFalse(bitmap.isRecycled)
        assertTrue(f.meter.isMeasuring)

        f.view.timestampNs += 33_333_333L
        f.meter.onCaptureResult(result(f.view.timestampNs, iso = 800))
        drain()
        assertTrue(f.readings.isEmpty()) // A newer exposure cannot describe the retained bitmap.
        assertFalse(bitmap.isRecycled)
        advance(16)
        f.meter.onCaptureResult(result(timestamp, iso = 100))
        drain()

        assertEquals(100, f.readings.single().captureIso)
        assertEquals(1, f.view.bitmaps.size)
        assertTrue(bitmap.isRecycled)
        assertTrue(f.errors.isEmpty())
        advance(200) // The obsolete timeout must not retry a successful measurement.
        assertEquals(1, f.view.bitmaps.size)
        assertEquals(1, f.readings.size)
    }

    @Test fun missingMetadataStillFinishesWithinTheExistingRetryBudgetAndRecyclesAllFrames() {
        val f = Fixture()
        f.start()
        drain()
        advance(CompatibleMeteringPolicy.DISPLAY_CAPTURE_ATTEMPTS *
            CompatibleMeteringPolicy.DISPLAY_CAPTURE_RETRY_DELAY_MS)
        assertFalse(f.meter.isMeasuring)
        assertTrue(f.readings.isEmpty())
        assertEquals(1, f.errors.size)
        assertTrue(f.view.bitmaps.isNotEmpty())
        assertTrue(f.view.bitmaps.all { it.isRecycled })
    }

    @Test fun cancellationReleasesTheWaitingBitmapAndIgnoresLateMetadataAndTimeouts() {
        val f = Fixture()
        f.start()
        drain()
        f.meter.cancel(f.handler)
        assertTrue(f.view.bitmaps.single().isRecycled)
        f.meter.onCaptureResult(result(f.view.timestampNs))
        advance(2_000)
        assertTrue(f.readings.isEmpty())
        assertTrue(f.errors.isEmpty())
        assertEquals(1, f.view.bitmaps.size)
    }

    @Test fun cancelledMeasurementCannotFeedItsMetadataOrTimerIntoANewerCapture() {
        val f = Fixture()
        f.start()
        drain()
        val oldTimestamp = f.view.timestampNs
        f.meter.cancel(f.handler)
        f.view.timestampNs += 33_333_333L
        f.start()
        drain()

        f.meter.onCaptureResult(result(oldTimestamp, iso = 800))
        drain()
        assertTrue(f.readings.isEmpty())
        assertFalse(f.view.bitmaps.last().isRecycled)
        f.meter.onCaptureResult(result(f.view.timestampNs, iso = 100))
        advance(2_000)
        assertEquals(100, f.readings.single().captureIso)
        assertTrue(f.errors.isEmpty())
        assertEquals(2, f.view.bitmaps.size)
        assertTrue(f.view.bitmaps.all { it.isRecycled })
    }

    @Test fun cancellationBeforeQueuedCameraWorkRecyclesWithoutLookingUpOldMetadata() {
        val f = Fixture()
        f.view.afterBitmapRead = { f.meter.cancel(f.handler) }
        f.start()
        drain()
        assertTrue(f.view.bitmaps.single().isRecycled)
        assertEquals(0, f.lookups)
        assertFalse(f.meter.isMeasuring)
        assertTrue(f.readings.isEmpty())
    }

    @Test fun matchingMetadataWithoutExposureRetriesAnotherFrameAndDoesNotAcceptInvalidCalibration() {
        val f = Fixture()
        f.start()
        drain()
        val invalid = ShadowCaptureResult.newCaptureResult()
        shadowOf(invalid).set(CaptureResult.SENSOR_TIMESTAMP, f.view.timestampNs)
        f.meter.onCaptureResult(invalid)
        drain()
        assertTrue(f.view.bitmaps.single().isRecycled)
        assertTrue(f.readings.isEmpty())
        f.view.timestampNs += 33_333_333L
        advance(CompatibleMeteringPolicy.DISPLAY_CAPTURE_RETRY_DELAY_MS)
        f.meter.onCaptureResult(result(f.view.timestampNs))
        drain()
        assertEquals(1, f.readings.size)
        assertTrue(f.view.bitmaps.all { it.isRecycled })
        assertTrue(f.errors.isEmpty())
    }

    private class Fixture {
        val handler = Handler(Looper.getMainLooper())
        val view = DisplayView()
        val results = TimestampedCaptureResultStore<CaptureResult>()
        val readings = mutableListOf<MeterReading>()
        val errors = mutableListOf<String>()
        var lookups = 0
        val meter = CompatibleLightMeter(
            handler, CameraCalibrationStore(RuntimeEnvironment.getApplication()), { _, english -> english },
            object : CompatibleLightMeterListener {
                override fun onCompatibleMeteringStarted(source: MeteringSource, frameCount: Int) = Unit
                override fun onCompatibleMeteringReading(reading: MeterReading) { readings.add(reading) }
                override fun onCompatibleMeteringError(message: String) { errors.add(message) }
                override fun onCompatibleMeteringCompleted(requiresPreviewOnlySession: Boolean) = Unit
            },
        )
        private val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        fun start() {
            assertTrue(meter.measure(
                MeteringMode.CENTER_WEIGHTED, null,
                CompatibleMeteringContext(
                    view, handler, false, { true }, { lookups++; results.takeExact(it) },
                    { characteristics }, { "test-camera" },
                ),
                forceProcessedPreview = true,
            ))
        }
    }

    private class DisplayView : TextureView(RuntimeEnvironment.getApplication()) {
        var timestampNs = 1_000_000_000L
        var afterBitmapRead: (() -> Unit)? = null
        val bitmaps = mutableListOf<Bitmap>()
        private val texture = object : SurfaceTexture(0) {
            override fun getTimestamp() = timestampNs
        }
        override fun isAvailable() = true
        override fun getSurfaceTexture() = texture
        override fun getBitmap(width: Int, height: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(128, 128, 128))
            bitmaps.add(bitmap)
            afterBitmapRead?.invoke()
            return bitmap
        }
    }

    private companion object {
        fun drain() = shadowOf(Looper.getMainLooper()).idle()
        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        fun result(timestamp: Long, iso: Int = 100): CaptureResult =
            ShadowCaptureResult.newCaptureResult().also {
                shadowOf(it).set(CaptureResult.SENSOR_TIMESTAMP, timestamp)
                shadowOf(it).set(CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
                shadowOf(it).set(CaptureResult.SENSOR_SENSITIVITY, iso)
                shadowOf(it).set(CaptureResult.LENS_APERTURE, 2f)
            }
    }
}
