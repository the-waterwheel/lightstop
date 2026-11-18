package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSessionProfilePolicyTest {
    @Test
    fun `each calibration source uses its smallest safe session`() {
        assertEquals(CameraSessionProfile.RAW_ONLY, CalibrationSessionProfilePolicy.profileFor(MeteringSource.RAW))
        assertEquals(CameraSessionProfile.COMPATIBLE, CalibrationSessionProfilePolicy.profileFor(MeteringSource.YUV_PREVIEW))
        assertEquals(CameraSessionProfile.PREVIEW_ONLY, CalibrationSessionProfilePolicy.profileFor(MeteringSource.ISP_PREVIEW))
    }

    @Test
    fun `explicit YUV calibration reopens a session that previously fell back to ISP`() {
        val yuv = CameraSessionProfile.COMPATIBLE
        assertTrue(CalibrationSessionProfilePolicy.requiresFreshSession(yuv, yuv, true, false))
        assertFalse(CalibrationSessionProfilePolicy.requiresFreshSession(yuv, yuv, true, true))
    }

    @Test
    fun `cached YUV failure does not force RAW or ISP to reopen their ready session`() {
        val raw = CameraSessionProfile.RAW_ONLY
        val isp = CameraSessionProfile.PREVIEW_ONLY
        assertFalse(CalibrationSessionProfilePolicy.requiresFreshSession(raw, raw, true, false))
        assertFalse(CalibrationSessionProfilePolicy.requiresFreshSession(isp, isp, true, false))
        assertTrue(CalibrationSessionProfilePolicy.requiresFreshSession(isp, isp, false, true))
    }

    @Test
    fun `rejected RAW plus preview can retry through the previous preview only once`() {
        val raw = CameraSessionProfile.RAW_ONLY
        val yuv = CameraSessionProfile.COMPATIBLE
        val isp = CameraSessionProfile.PREVIEW_ONLY
        assertEquals(yuv, CalibrationSessionProfilePolicy.rawFallbackPreview(raw, yuv))
        assertEquals(isp, CalibrationSessionProfilePolicy.rawFallbackPreview(raw, isp))
        assertNull(CalibrationSessionProfilePolicy.rawFallbackPreview(raw, raw))
        assertNull(CalibrationSessionProfilePolicy.rawFallbackPreview(raw, null))
        assertNull(CalibrationSessionProfilePolicy.rawFallbackPreview(yuv, yuv))
    }
}
