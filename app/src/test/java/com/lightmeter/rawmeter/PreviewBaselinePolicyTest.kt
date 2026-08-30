package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewBaselinePolicyTest {
    private val operation = PreviewBaselineOperation(
        cameraGeneration = 7,
        generation = 12L,
        startedAtElapsedMs = 0L,
        continuation = {},
    )

    @Test
    fun `only the current neutral request tag unlocks the baseline`() {
        assertTrue(
            PreviewBaselinePolicy.acceptsResult(
                operation,
                PreviewRequestTag(7, 21L, 12L),
            ),
        )
        assertFalse(PreviewBaselinePolicy.acceptsResult(operation, PreviewRequestTag(7, 22L, 11L)))
        assertFalse(PreviewBaselinePolicy.acceptsResult(operation, PreviewRequestTag(8, 23L, 12L)))
        assertFalse(PreviewBaselinePolicy.acceptsResult(operation, null))
    }

    @Test
    fun `two tagged stable frames are required`() {
        assertFalse(PreviewBaselinePolicy.hasEnoughStableFrames(1))
        assertTrue(PreviewBaselinePolicy.hasEnoughStableFrames(2))
    }
}
