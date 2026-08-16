package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibleMeteringPolicyTest {
    @Test
    fun oneValidProcessedFrameCompletesMeasurement() {
        assertEquals(1, CompatibleMeteringPolicy.FRAME_COUNT)
        assertEquals(
            CompatibleFrameDecision.COMPLETE,
            CompatibleMeteringPolicy.decide(attemptedFrames = 1, frameValid = true),
        )
    }

    @Test
    fun invalidYuvFramesRetryBrieflyThenUsePreview() {
        assertEquals(
            CompatibleFrameDecision.RETRY,
            CompatibleMeteringPolicy.decide(attemptedFrames = 1, frameValid = false),
        )
        assertEquals(
            CompatibleFrameDecision.RETRY,
            CompatibleMeteringPolicy.decide(attemptedFrames = 2, frameValid = false),
        )
        assertEquals(
            CompatibleFrameDecision.USE_PREVIEW,
            CompatibleMeteringPolicy.decide(attemptedFrames = 3, frameValid = false),
        )
        assertTrue(CompatibleMeteringPolicy.YUV_TIMEOUT_MS < 300L)
    }
}
