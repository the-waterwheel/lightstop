package com.lightmeter.rawmeter

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawProbeFrameHealthPolicyTest {
    @Test
    fun `healthy probe requires a real exactly paired readable raw buffer`() {
        assertTrue(RawProbeFrameHealthPolicy.evaluate(healthy()).usable)
    }

    @Test
    fun `capture completion without the matching raw image cannot pass`() {
        val health = RawProbeFrameHealthPolicy.evaluate(
            healthy().copy(resultTimestampNs = 124L),
        )

        assertFalse(health.usable)
        assertEquals("RAW Image and CaptureResult timestamps do not match", health.reason)
    }

    @Test
    fun `truncated vendor buffer is rejected without scanning its pixels`() {
        val health = RawProbeFrameHealthPolicy.evaluate(
            healthy().copy(bufferLimit = 1_000, bufferCapacity = 1_000),
        )

        assertFalse(health.usable)
        assertEquals("RAW buffer range is smaller than its declared layout", health.reason)
    }

    @Test
    fun `missing metering metadata prevents a false healthy combination`() {
        val health = RawProbeFrameHealthPolicy.evaluate(
            healthy().copy(exposureTimeNs = null),
        )

        assertFalse(health.usable)
        assertEquals("RAW exposure metadata is unavailable", health.reason)
    }

    @Test
    fun `invalid black level cannot pass as usable raw metadata`() {
        val health = RawProbeFrameHealthPolicy.evaluate(
            healthy().copy(validBlackLevel = false),
        )

        assertFalse(health.usable)
        assertEquals("RAW black-level metadata is unavailable", health.reason)
    }

    private fun healthy() = RawProbeFrameDescriptor(
        format = ImageFormat.RAW_SENSOR,
        width = 400,
        height = 300,
        expectedWidth = 400,
        expectedHeight = 300,
        imageTimestampNs = 123L,
        resultTimestampNs = 123L,
        planeCount = 1,
        directBuffer = true,
        bufferPosition = 0,
        bufferLimit = 400 * 2 * 300,
        bufferCapacity = 400 * 2 * 300,
        rowStride = 400 * 2,
        pixelStride = 2,
        exposureTimeNs = 10_000_000L,
        sensitivityIso = 100,
        aperture = 2f,
        bayerCfa = true,
        validBlackLevel = true,
        whiteLevel = 4095,
    )
}
