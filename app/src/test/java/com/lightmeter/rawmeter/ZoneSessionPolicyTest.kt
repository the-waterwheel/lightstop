package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneSessionPolicyTest {
    @Test
    fun zoneUsesYuvResidentSessionWithoutRaw() {
        val profile = ZoneSessionPolicy.residentProfile(
            normalProfile = CameraSessionProfile.RAW_ONLY,
            zoneActive = true,
            manualSafePreview = false,
            trackingSupported = true,
            zoneYuvUnavailable = false,
        )

        assertEquals(CameraSessionProfile.COMPATIBLE, profile)
        assertTrue(profile.usesPreview)
        assertTrue(profile.usesTracking)
        assertFalse(profile.usesRaw)
    }

    @Test
    fun zoneRawCaptureUsesOnlyRawOutput() {
        assertFalse(CameraSessionProfile.RAW_ISOLATED.usesPreview)
        assertTrue(CameraSessionProfile.RAW_ISOLATED.usesRaw)
        assertFalse(CameraSessionProfile.RAW_ISOLATED.usesTracking)
        assertTrue(
            ZoneSessionPolicy.shouldUseTransientRaw(
                zoneActive = true,
                requestedSource = null,
                pipelineMode = MeteringPipelineMode.AUTO,
                rawSupported = true,
                manualSafePreview = false,
            ),
        )
    }

    @Test
    fun compatibilityAndManualSafeModesNeverStartTransientRaw() {
        assertFalse(
            ZoneSessionPolicy.shouldUseTransientRaw(
                zoneActive = true,
                requestedSource = null,
                pipelineMode = MeteringPipelineMode.FAST,
                rawSupported = true,
                manualSafePreview = false,
            ),
        )
        assertFalse(
            ZoneSessionPolicy.shouldUseTransientRaw(
                zoneActive = true,
                requestedSource = null,
                pipelineMode = MeteringPipelineMode.AUTO,
                rawSupported = true,
                manualSafePreview = true,
            ),
        )
    }

    @Test
    fun unavailableZoneYuvFallsBackWithoutChangingNormalProfile() {
        val zoneFallback = ZoneSessionPolicy.residentProfile(
            normalProfile = CameraSessionProfile.RAW_ONLY,
            zoneActive = true,
            manualSafePreview = false,
            trackingSupported = true,
            zoneYuvUnavailable = true,
        )
        val afterZone = ZoneSessionPolicy.residentProfile(
            normalProfile = CameraSessionProfile.RAW_ONLY,
            zoneActive = false,
            manualSafePreview = false,
            trackingSupported = true,
            zoneYuvUnavailable = true,
        )

        assertEquals(CameraSessionProfile.PREVIEW_ONLY, zoneFallback)
        assertEquals(CameraSessionProfile.RAW_ONLY, afterZone)
    }

    @Test
    fun physicalCameraContinuityRejectsOnlyConfirmedLensChanges() {
        assertFalse(ZoneSessionPolicy.physicalCameraChanged("2", null))
        assertFalse(ZoneSessionPolicy.physicalCameraChanged(null, "2"))
        assertFalse(ZoneSessionPolicy.physicalCameraChanged("2", "2"))
        assertTrue(ZoneSessionPolicy.physicalCameraChanged("2", "3"))
    }
}
