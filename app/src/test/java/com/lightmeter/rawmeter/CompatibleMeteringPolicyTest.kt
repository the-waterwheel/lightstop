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
    fun invalidYuvFramesRetryThroughVendorStartupWindow() {
        assertEquals(
            CompatibleFrameDecision.RETRY,
            CompatibleMeteringPolicy.decide(attemptedFrames = 1, frameValid = false),
        )
        assertEquals(
            CompatibleFrameDecision.RETRY,
            CompatibleMeteringPolicy.decide(attemptedFrames = 2, frameValid = false),
        )
        assertEquals(
            CompatibleFrameDecision.RETRY,
            CompatibleMeteringPolicy.decide(attemptedFrames = 3, frameValid = false),
        )
        assertEquals(
            CompatibleFrameDecision.USE_PREVIEW,
            CompatibleMeteringPolicy.decide(
                attemptedFrames = CompatibleMeteringPolicy.MAX_YUV_ATTEMPTS,
                frameValid = false,
            ),
        )
        assertTrue(CompatibleMeteringPolicy.YUV_TIMEOUT_MS in 1_000L..2_000L)
        val displayRetryWindowMs =
            CompatibleMeteringPolicy.DISPLAY_CAPTURE_ATTEMPTS *
                CompatibleMeteringPolicy.DISPLAY_CAPTURE_RETRY_DELAY_MS
        assertTrue(displayRetryWindowMs in 1_000L..2_000L)
    }
}
