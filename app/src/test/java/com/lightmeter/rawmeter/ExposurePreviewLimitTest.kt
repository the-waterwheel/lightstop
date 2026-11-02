package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class ExposurePreviewLimitTest {
    @Test fun darkSceneReportsPreviewRangeWithoutChangingTheSelectedPhotographicExposure() {
        val selection = ExposurePreviewSelection(5.0, -2.0, 0.0)
        val exposure = ExposurePreviewMath.manualExposure(-2.0, 2.0, 100, 100_000L,
            1_000_000_000L, 50, 800)!!
        val limit = ExposurePreviewLimit.manual(selection, exposure)!!
        assertEquals(-2.0, selection.selectedExposureEv100, 0.0)
        assertEquals(-2.0, limit.requestedEv, 0.0)
        assertEquals(2.9068906, limit.availableEv, .00001)
        assertEquals("目标 -2.0 / 可用 2.9 EV100", limit.detail(MenuLanguage.CHINESE))
    }

    @Test fun previewRangeUsesCalibratedExecutionTargetRatherThanTheUncorrectedPhotographicSelection() {
        val selection = ExposurePreviewSelection(5.0, -2.0, .5, -.25)
        val exposure = ExposurePreviewMath.manualExposure(-2.75, 2.0, 100, 100_000L,
            1_000_000_000L, 50, 800)!!
        assertEquals(-2.75, ExposurePreviewLimit.manual(selection, exposure)!!.requestedEv, 0.0)
    }

    @Test fun aeRangeReportsRequestedAndAvailableCompensationInBothDirections() {
        for (sign in listOf(-1, 1)) {
            val compensation = ExposurePreviewMath.quantizeCompensation(sign * 4.0, -6, 6, 1.0 / 3)
            val limit = ExposurePreviewLimit.compensation(compensation)!!
            assertEquals(sign * 4.0, limit.requestedEv, 0.0)
            assertEquals(sign * 2.0, limit.availableEv, 0.0)
            assertFalse(limit.manual)
            assertFalse(limit.detail(MenuLanguage.ENGLISH).contains("EV100"))
        }
        assertNull(ExposurePreviewLimit.compensation(ExposurePreviewMath.quantizeCompensation(.2, -6, 6, 1.0 / 3)))
        assertNull(ExposurePreviewLimit.compensation(ExposurePreviewMath.quantizeCompensation(4.0, 0, 0, 1.0 / 3)))
    }

    @Test fun attainableManualExposureHasNoRangeWarning() {
        val selection = ExposurePreviewSelection(10.0, 10.0, 0.0)
        val exposure = ExposurePreviewMath.manualExposure(10.0, 2.0, 100, 100_000L,
            1_000_000_000L, 50, 800)!!
        assertNull(ExposurePreviewLimit.manual(selection, exposure))
    }
}
