package com.lightmeter.rawmeter

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCalibrationResponseValidityTest {
    private val app = RuntimeEnvironment.getApplication()
    private val store = CameraCalibrationStore(app)
    private val originalContext = CalibrationCaptureContext(
        buildFingerprintHash = "build-a",
        cameraInfoVersion = "1",
        selectionRouteId = "0",
        logicalCameraId = "0",
        configuredPhysicalCameraId = null,
        confirmedPhysicalCameraId = "main",
        routeKind = CameraRouteKind.LOGICAL_AUTO,
        outputs = mapOf(
            MeteringSource.YUV_PREVIEW to CalibrationOutputGeometry(640, 480, 35),
            MeteringSource.ISP_PREVIEW to CalibrationOutputGeometry(640, 480, 0),
        ),
    )

    @Test fun environmentCutoffDisablesBothProcessedCorrectionPaths() {
        val yuv = MeteringSource.YUV_PREVIEW
        val isp = MeteringSource.ISP_PREVIEW
        store.setActiveContext(originalContext)
        val record = save(originalContext, yuv, .12, 1.0, isp)
        assertCorrection(yuv, .12, 1.0)
        assertCorrection(isp, .12, 1.0)

        app.getSharedPreferences("calibration_environment", Context.MODE_PRIVATE).edit()
            .putLong("minimum_valid_timestamp", record.updatedAtEpochMs + 1)
            .apply()

        assertCorrection(yuv, .12, 0.0)
        assertCorrection(isp, .12, 0.0)
    }

    @Test fun recalibratingAfterAnOutputChangeStartsAFreshResponse() {
        val source = MeteringSource.YUV_PREVIEW
        store.setActiveContext(originalContext)
        save(originalContext, source, .1, 1.0)
        val original = save(originalContext, source, .4, 3.0)
        assertEquals(2, original.yuv.response!!.anchors.size)
        assertCorrection(source, .25, 2.0)

        val resized = originalContext.copy(
            outputs = originalContext.outputs + (source to CalibrationOutputGeometry(800, 600, 35)),
        )
        store.setActiveContext(resized)
        assertCorrection(source, .25, 0.0)
        val updated = save(resized, source, .4, 4.0)
        assertEquals(listOf(ProcessedResponseAnchor(.4, 4.0)), updated.yuv.response!!.anchors)
        assertCorrection(source, .1, 4.0)
    }

    private fun save(
        context: CalibrationCaptureContext,
        source: MeteringSource,
        luma: Double,
        correction: Double,
        vararg additionalSources: MeteringSource,
    ): CameraCalibrationRecord {
        val sources = listOf(source) + additionalSources
        val samples = sources.associateWith {
            CalibrationMeasurementSample(it, "0", context.signature(it), 10.0, 0.0, luma)
        }
        return store.updateUserCorrections(
            cameraId = "0",
            referenceEv100 = 10.0 + correction,
            measurements = sources.associateWith { 10.0 },
            samples = samples,
        )
    }

    private fun assertCorrection(source: MeteringSource, luma: Double, expected: Double) {
        assertEquals(expected, store.responseCorrection("0", source, luma), 1e-6)
        assertEquals(expected, store.responseCorrectionSnapshot("0", source)(luma), 1e-6)
    }
}
