package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraCalibrationCoverageTest {
    @Test
    fun `automatic camera exposes active and aggregate physical calibration`() {
        val automatic = camera("0", CameraLensRole.AUTOMATIC)
        val main = camera("0@2", CameraLensRole.MAIN, "2")
        val tele = camera("0@3", CameraLensRole.TELEPHOTO, "3")
        val records = mapOf("0@2" to calibration(0.12), "0@3" to calibration(-0.08))

        val coverage = CameraCalibrationCoverageResolver.resolve(
            camera = automatic,
            cameras = listOf(automatic, main, tele),
            selectedCameraId = "0",
            cameraInfo = CameraUiInfo(
                cameraId = "0",
                logicalCameraId = "0",
                activePhysicalCameraId = "2",
            ),
            record = records::get,
        )

        assertEquals(2, coverage.physicalLensCount)
        assertEquals(listOf("0@2", "0@3"), coverage.physical.map { it.storageCameraId })
        assertEquals("0@2", coverage.activePhysical?.storageCameraId)
        assertNull(coverage.direct)
    }

    @Test
    fun `calibration display keeps physical identity during logical reopen`() {
        assertEquals("0@2", CalibrationDisplayIdentity.resolve("0@2", "0"))
        assertEquals("0@3", CalibrationDisplayIdentity.resolve("0@2", "0@3"))
        assertEquals("1", CalibrationDisplayIdentity.resolve("0@2", "1"))
    }

    private fun camera(
        cameraId: String,
        role: CameraLensRole,
        physicalId: String? = null,
    ) = CameraDescriptor(
        cameraId = cameraId,
        logicalCameraId = "0",
        physicalCameraId = physicalId,
        lensRole = role,
        lensFacing = 1,
        rawAvailable = true,
        manualSensorAvailable = true,
        focalLengthsMm = listOf(4f),
        apertures = listOf(1.8f),
        sensorWidthMm = 6f,
        sensorHeightMm = 4f,
        sensorOrientationDegrees = 90,
        maxDigitalZoom = 8f,
    )

    private fun calibration(correction: Double) = CameraCalibrationRecord(
        raw = StreamCalibration(correction, 10.0),
        yuv = StreamCalibration(correction, 10.0),
        ispPreview = StreamCalibration(correction, 10.0),
        legacyCompatibleCorrectionEv = null,
        legacyCompatibleMeasuredEv100 = null,
        referenceEv100 = 10.0,
        updatedAtEpochMs = 1L,
        calibrationCount = 1,
    )
}
