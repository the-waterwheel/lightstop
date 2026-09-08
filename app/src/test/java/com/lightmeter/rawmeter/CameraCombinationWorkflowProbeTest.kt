package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraCombinationWorkflowProbeTest {
    @Test
    fun splitRawWorkflowVisitsDistinctStagesAndRestoresNormalPreview() {
        val probe = CameraCombinationWorkflowProbe(CameraCombinationPolicy.rawSplit) {}

        assertEquals(CameraSessionProfile.RAW_ONLY, probe.currentStage)
        assertEquals(CameraSessionProfile.COMPATIBLE, probe.advance())
        assertEquals(CameraSessionProfile.RAW_ISOLATED, probe.advance())
        assertEquals(CameraSessionProfile.RAW_ONLY, probe.advance())
        assertNull(probe.advance())
    }

    @Test
    fun identicalWorkflowStagesAreCollapsed() {
        assertEquals(
            listOf(CameraSessionProfile.COMPATIBLE),
            CameraCombinationWorkflowProbe.stagesFor(CameraCombinationPolicy.stableYuv),
        )
    }
}
