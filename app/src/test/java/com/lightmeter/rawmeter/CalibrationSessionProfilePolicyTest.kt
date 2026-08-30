package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationSessionProfilePolicyTest {
    @Test
    fun `each calibration source uses its smallest safe session`() {
        assertEquals(CameraSessionProfile.RAW_ONLY, CalibrationSessionProfilePolicy.profileFor(MeteringSource.RAW))
        assertEquals(CameraSessionProfile.COMPATIBLE, CalibrationSessionProfilePolicy.profileFor(MeteringSource.YUV_PREVIEW))
        assertEquals(CameraSessionProfile.PREVIEW_ONLY, CalibrationSessionProfilePolicy.profileFor(MeteringSource.ISP_PREVIEW))
    }
}
