package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.media.ResearchRawImage
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCaptureResult

/** Independent geometric/identity invariants; intentionally fail when the current code violates them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoneZoomResearchTest {
    private val full = ZoneVisibleViewport(0f, 0f, 1f, 1f)

    @Test fun controlUnzoomedFallbackCoordinatesAgree() {
        val base = ZoneCoordinateMapper.uiPreviewToBasePreview(.6f, .5f, full, 1f)
        val sampled = ZoneCoordinateMapper.basePreviewToTexture(base.first, base.second, 1000, 1000, full, false, 0)
        assertEquals(600.0, sampled.x, .001)
    }

    @Test fun zoomedFallbackMustSampleTheDisplayedMarkerLocation() {
        val base = ZoneCoordinateMapper.uiPreviewToBasePreview(.6f, .5f, full, 2f)
        val sampled = ZoneCoordinateMapper.basePreviewToTexture(base.first, base.second, 1000, 1000, full, false, 0)
        println("AUDIT fallback z=2 displayedX=600 actualAnalysisX=${sampled.x}")
        assertEquals("Bitmap already contains GL zoom; the local feature cluster must use displayed pixels", 600.0, sampled.x, .001)
    }

    private fun compareReference(previewX: Float, widthFraction: Float): Pair<Float, Float> {
        val luma = ByteArray(128 * 128) { ((it % 128) * 2).toByte() }
        val frame = ZoneTrackingFrame(128, 128, luma, 0)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) for (x in 0 until 128) bitmap.setPixel(x, y, Color.rgb(x * 2, x * 2, x * 2))
        val target = ZoneMeteringTarget(.5f, .5f, previewX, .5f, widthFraction, 1f)
        return try {
            val fromYuv = RawPreviewRegistration.createReference(frame, widthFraction, 1f, target)!!
            val fromDisplay = RawPreviewRegistration.createReference(bitmap, target)!!
            fromYuv.samples[60] to fromDisplay.samples[60]
        } finally { bitmap.recycle(); frame.close() }
    }

    @Test fun controlCenteredReferencesHaveTheSameCenter() {
        val centers = compareReference(.5f, 1f)
        assertEquals(centers.first, centers.second, 1f)
    }

    @Test fun yuvReferenceMustHonorTheActualFilmViewportOffset() {
        val centers = compareReference(.65f, .7f)
        println("AUDIT offset reference yuvCenter=${centers.first} displayCenter=${centers.second}")
        assertEquals("Both references represent one point in the same synthetic grayscale frame", centers.second, centers.first, 1f)
    }

    @Test fun unrelatedRawNoiseMustNotCountAsFeatureIdentity() {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(characteristics).set(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB)
        shadowOf(characteristics).set(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL, 4095)
        val result = ShadowCaptureResult.newCaptureResult()
        shadowOf(result).set(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL, FloatArray(4))
        val image = ResearchRawImage(723L)
        val random = Random(919)
        val reference = PreviewLumaReference(FloatArray(121) { random.nextFloat() * 255f }, 11, .026f)
        val point = RawPreviewRegistration.resolve(image, result, characteristics,
            CameraUiInfo(activeArray = Rect(0, 0, 512, 512)), 1f,
            ZoneMeteringTarget(.5f, .5f, .5f, .5f, 1f, 1f), reference,
            ScreenToSensorCoordinateTransform(0, false))
        println("AUDIT unrelated reference score=${point.matchScore} sensorX=${point.sensorX} sensorY=${point.sensorY}")
        assertFalse("Independent deterministic noise has no matching identity", point.matchScore.isFinite())
    }

    @Test fun controlTrueRawPatchRegistersAtItsKnownLocation() {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val cfa = CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        shadowOf(characteristics).set(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, cfa)
        shadowOf(characteristics).set(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL, 4095)
        val result = ShadowCaptureResult.newCaptureResult()
        shadowOf(result).set(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL, FloatArray(4))
        val image = ResearchRawImage(723L)
        val sampler = RawGreenSampler(image, 0, cfa, FloatArray(4), 4095)
        val samples = FloatArray(121) { index ->
            val x = .5f + ((index % 11) / 10f * 2f - 1f) * .026f
            val y = .5f + ((index / 11) / 10f * 2f - 1f) * .026f
            sampler.sample(x * 512, y * 512)!!
        }
        val point = RawPreviewRegistration.resolve(image, result, characteristics,
            CameraUiInfo(activeArray = Rect(0, 0, 512, 512)), 1f,
            ZoneMeteringTarget(.5f, .5f, .5f, .5f, 1f, 1f), PreviewLumaReference(samples, 11, .026f),
            ScreenToSensorCoordinateTransform(0, false))
        println("AUDIT true reference score=${point.matchScore} sensorX=${point.sensorX} sensorY=${point.sensorY}")
        assertTrue(point.matchScore > .999)
        assertEquals(256f, point.sensorX, .001f)
        assertEquals(256f, point.sensorY, .001f)
    }

    @Test fun localYuvMustIgnorePixelsOutsideTheShortSideSquare() {
        fun sample(stripes: Boolean): Double {
            val width = 400
            val height = 200
            val y = ByteBuffer.wrap(ByteArray(width * height) { 32 })
            if (stripes) for (row in 92 until 108) for (x in 184 until 216) {
                if (x !in 192 until 208) y.put(row * width + x, 128.toByte())
            }
            val uv = ByteBuffer.wrap(ByteArray(width * height / 4) { 128.toByte() })
            return YuvRegionAnalyzer(width, height,
                YuvPlaneReader(y, width, 1), YuvPlaneReader(uv, width / 2, 1),
                YuvPlaneReader(uv, width / 2, 1), ProcessedLumaDecoder(ProcessedTransfer.LINEAR),
                YuvColorEncoding(), .08f).analyze(.08f)!!.luma
        }
        val before = sample(false)
        val after = sample(true)
        println("AUDIT YUV unchanged central16x16 lumaBefore=$before lumaAfter=$after deltaEv=${ExposureMath.log2(after / before)}")
        assertEquals("Outer stripes do not belong to the indicated short-side square", before, after, 1e-9)
    }

    @Test fun ispPhysicalAngleMustStayConstantWhenTheDisplayZoomChanges() {
        val result = ShadowCaptureResult.newCaptureResult()
        shadowOf(result).set(CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
        shadowOf(result).set(CaptureResult.SENSOR_SENSITIVITY, 100)
        shadowOf(result).set(CaptureResult.LENS_APERTURE, 2f)
        shadowOf(result).set(CaptureResult.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
        shadowOf(result).set(CaptureResult.TONEMAP_GAMMA, 1f)
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val store = CameraCalibrationStore(RuntimeEnvironment.getApplication())
        fun measure(zoom: Int): Double {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(128, 128, 128))
            val radius = 4 * zoom
            for (y in 48 - radius until 48 + radius) for (x in 48 - radius until 48 + radius) {
                bitmap.setPixel(x, y, Color.rgb(32, 32, 32))
            }
            return try {
                MeteringAnalysis.analyzePreview(bitmap, result, characteristics, "research-angle", MeteringMode.ANGLE,
                    store, meteringRoiFraction = .2f,
                    evaluativeViewport = EvaluativeMeteringViewport(1f, zoom.toFloat(), NormalizedSensorViewport.FULL))!!.ev100
            } finally { bitmap.recycle() }
        }
        val one = measure(1)
        val two = measure(2)
        println("AUDIT ISP physical angle ev1x=$one ev2x=$two deltaEv=${two - one}")
        assertEquals("The same physical angle covers the same synthetic scene after digital zoom", one, two, 1e-8)
    }

    @Test fun processedBoostMustCancelForAnUnchangedLinearScene() {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val store = CameraCalibrationStore(RuntimeEnvironment.getApplication())
        fun measure(boost: Int, code: Int, mode: MeteringMode): Double {
            val result = ShadowCaptureResult.newCaptureResult()
            shadowOf(result).set(CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
            shadowOf(result).set(CaptureResult.SENSOR_SENSITIVITY, 100)
            shadowOf(result).set(CaptureResult.LENS_APERTURE, 2f)
            shadowOf(result).set(CaptureResult.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
            shadowOf(result).set(CaptureResult.TONEMAP_GAMMA, 1f)
            shadowOf(result).set(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost)
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(code, code, code))
            return try {
                MeteringAnalysis.analyzePreview(bitmap, result, characteristics, "research-boost", mode, store,
                    evaluativeViewport = EvaluativeMeteringViewport(1f, 1f, NormalizedSensorViewport.FULL))!!.ev100
            } finally { bitmap.recycle() }
        }
        for (mode in listOf(MeteringMode.SPOT, MeteringMode.AVERAGE)) {
            val before = measure(100, 32, mode)
            val after = measure(400, 128, mode)
            println("AUDIT processed boost mode=$mode ev100percent=$before ev400percent=$after deltaEv=${after - before}")
        }
        assertEquals("Only digital gain changed, while scene radiance and sensor exposure stayed fixed",
            measure(100, 32, MeteringMode.SPOT), measure(400, 128, MeteringMode.SPOT), 1e-8)
    }
}
