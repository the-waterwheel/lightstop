package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMigrationPolicyTest {
    private val context = CalibrationCaptureContext(
        buildFingerprintHash = "build-a",
        cameraInfoVersion = "1.0",
        selectionRouteId = "0",
        logicalCameraId = "0",
        configuredPhysicalCameraId = null,
        confirmedPhysicalCameraId = null,
        routeKind = CameraRouteKind.LOGICAL_AUTO,
        outputs = mapOf(
            MeteringSource.RAW to CalibrationOutputGeometry(4000, 3000, 0x25),
            MeteringSource.YUV_PREVIEW to CalibrationOutputGeometry(1920, 1080, 0x23),
            MeteringSource.ISP_PREVIEW to CalibrationOutputGeometry(1920, 1080, 0),
        ),
    )

    @Test
    fun `legacy records are visible but never applicable`() {
        val current = context.signature(MeteringSource.RAW)
        val state = CalibrationSignaturePolicy.classify(null, storedPresent = true, current = current)
        assertEquals(CalibrationSignatureState.LEGACY_UNVERIFIED, state)
        assertFalse(state.isApplicable)
    }

    @Test
    fun `fresh signatures are applicable`() {
        val current = context.signature(MeteringSource.RAW)
        val state = CalibrationSignaturePolicy.classify(current, storedPresent = true, current = current)
        assertTrue(state.isApplicable)
    }

    @Test
    fun `domains stay independent across a build change`() {
        val rawStored = context.signature(MeteringSource.RAW)
        val ispStored = context.signature(MeteringSource.ISP_PREVIEW)
        val ota = context.copy(buildFingerprintHash = "build-b")
        assertEquals(
            CalibrationSignatureState.NEEDS_REVALIDATION,
            CalibrationSignaturePolicy.classify(
                rawStored,
                true,
                ota.signature(MeteringSource.RAW),
            ),
        )
        // The RAW signature never validates the ISP domain, even on the same build.
        assertEquals(
            CalibrationSignatureState.ROUTE_MISMATCH,
            CalibrationSignaturePolicy.classify(
                rawStored,
                true,
                context.signature(MeteringSource.ISP_PREVIEW),
            ),
        )
        assertTrue(ispStored.stableHash() != rawStored.stableHash())
    }

    @Test
    fun `vignetting signature is a distinct RAW domain`() {
        val raw = context.signature(MeteringSource.RAW)
        val vignetting = context.vignettingSignature()
        assertEquals(CalibrationDomain.VIGNETTING, vignetting.domain)
        assertEquals(
            CalibrationSignatureState.ROUTE_MISMATCH,
            CalibrationSignaturePolicy.classify(raw, true, vignetting),
        )
    }
}
