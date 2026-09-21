package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSignatureTest {
    private fun signature(
        build: String = "build-a",
        cameraInfoVersion: String? = "1.0",
        routeId: String = "2",
        logicalId: String = "0",
        configuredPhysical: String? = "2",
        confirmedPhysical: String? = "2",
        routeKind: CameraRouteKind = CameraRouteKind.FIXED_PHYSICAL,
        width: Int = 4000,
        height: Int = 3000,
        format: Int = 0x25,
        algorithmVersion: Int = CalibrationSignature.CURRENT_ALGORITHM_VERSION,
        domain: CalibrationDomain = CalibrationDomain.METERING_RAW,
    ) = CalibrationSignature(
        schemaVersion = CalibrationSignature.CURRENT_SCHEMA_VERSION,
        algorithmVersion = algorithmVersion,
        domain = domain,
        buildFingerprintHash = build,
        cameraInfoVersion = cameraInfoVersion,
        selectionRouteId = routeId,
        logicalCameraId = logicalId,
        configuredPhysicalCameraId = configuredPhysical,
        confirmedPhysicalCameraId = confirmedPhysical,
        routeKind = routeKind,
        outputWidth = width,
        outputHeight = height,
        outputFormat = format,
    )

    @Test
    fun `serialization round trips every field`() {
        val original = signature()
        assertEquals(original, CalibrationSignature.parse(original.serialize()))
        assertEquals(original.stableHash(), CalibrationSignature.parse(original.serialize())!!.stableHash())
    }

    @Test
    fun `null optional fields round trip`() {
        val original = signature(
            cameraInfoVersion = null,
            configuredPhysical = null,
            confirmedPhysical = null,
            routeKind = CameraRouteKind.LOGICAL_AUTO,
        )
        assertEquals(original, CalibrationSignature.parse(original.serialize()))
    }

    @Test
    fun `malformed values parse to null`() {
        assertNull(CalibrationSignature.parse(null))
        assertNull(CalibrationSignature.parse(""))
        assertNull(CalibrationSignature.parse("1|2|METERING_RAW|build"))
    }

    @Test
    fun `matching signature is valid`() {
        val current = signature()
        assertEquals(
            CalibrationSignatureState.VALID,
            CalibrationSignaturePolicy.classify(current, true, current),
        )
    }

    @Test
    fun `absent data is none and legacy data is unverified`() {
        assertEquals(
            CalibrationSignatureState.NONE,
            CalibrationSignaturePolicy.classify(null, false, signature()),
        )
        assertEquals(
            CalibrationSignatureState.LEGACY_UNVERIFIED,
            CalibrationSignaturePolicy.classify(null, true, signature()),
        )
        assertFalse(CalibrationSignatureState.LEGACY_UNVERIFIED.isApplicable)
    }

    @Test
    fun `build and algorithm changes require revalidation`() {
        val stored = signature()
        assertEquals(
            CalibrationSignatureState.NEEDS_REVALIDATION,
            CalibrationSignaturePolicy.classify(stored, true, stored.copy(buildFingerprintHash = "ota")),
        )
        assertEquals(
            CalibrationSignatureState.NEEDS_REVALIDATION,
            CalibrationSignaturePolicy.classify(
                stored,
                true,
                stored.copy(cameraInfoVersion = "2.0"),
            ),
        )
        assertEquals(
            CalibrationSignatureState.NEEDS_REVALIDATION,
            CalibrationSignaturePolicy.classify(
                stored,
                true,
                stored.copy(algorithmVersion = stored.algorithmVersion + 1),
            ),
        )
    }

    @Test
    fun `route and output changes are mismatches`() {
        val stored = signature()
        assertEquals(
            CalibrationSignatureState.ROUTE_MISMATCH,
            CalibrationSignaturePolicy.classify(stored, true, stored.copy(selectionRouteId = "3")),
        )
        assertEquals(
            CalibrationSignatureState.ROUTE_MISMATCH,
            CalibrationSignaturePolicy.classify(stored, true, stored.copy(routeKind = CameraRouteKind.LOGICAL_AUTO)),
        )
        assertEquals(
            CalibrationSignatureState.ROUTE_MISMATCH,
            CalibrationSignaturePolicy.classify(stored, true, stored.copy(outputWidth = 4032)),
        )
        assertEquals(
            CalibrationSignatureState.ROUTE_MISMATCH,
            CalibrationSignaturePolicy.classify(stored, true, stored.copy(domain = CalibrationDomain.METERING_ISP)),
        )
    }

    @Test
    fun `transient null confirmed physical id does not invalidate`() {
        val stored = signature(confirmedPhysical = "2")
        assertEquals(
            CalibrationSignatureState.VALID,
            CalibrationSignaturePolicy.classify(
                stored,
                true,
                stored.copy(confirmedPhysicalCameraId = null),
            ),
        )
        assertEquals(
            CalibrationSignatureState.ROUTE_MISMATCH,
            CalibrationSignaturePolicy.classify(
                stored,
                true,
                stored.copy(confirmedPhysicalCameraId = "3"),
            ),
        )
    }

    @Test
    fun `capture context produces one signature per source output`() {
        val context = CalibrationCaptureContext(
            buildFingerprintHash = "build",
            cameraInfoVersion = "1.0",
            selectionRouteId = "2",
            logicalCameraId = "0",
            configuredPhysicalCameraId = "2",
            confirmedPhysicalCameraId = "2",
            routeKind = CameraRouteKind.FIXED_PHYSICAL,
            outputs = mapOf(
                MeteringSource.RAW to CalibrationOutputGeometry(4000, 3000, 0x25),
                MeteringSource.ISP_PREVIEW to CalibrationOutputGeometry(1920, 1080, 0),
            ),
        )
        val raw = context.signature(MeteringSource.RAW)
        val isp = context.signature(MeteringSource.ISP_PREVIEW)
        val yuv = context.signature(MeteringSource.YUV_PREVIEW)
        assertEquals(CalibrationDomain.METERING_RAW, raw.domain)
        assertEquals(CalibrationDomain.METERING_ISP, isp.domain)
        assertEquals(CalibrationDomain.METERING_YUV, yuv.domain)
        assertEquals(4000, raw.outputWidth)
        assertEquals(1920, isp.outputWidth)
        assertEquals(0, yuv.outputWidth)
        assertTrue(raw.stableHash() != isp.stableHash())
    }
}
