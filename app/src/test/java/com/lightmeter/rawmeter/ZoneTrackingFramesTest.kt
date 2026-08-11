package com.lightmeter.rawmeter

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneTrackingFramesTest {
    @Test
    fun poolReusesThreeBuffersWithoutPerFrameAllocation() {
        val pool = ZoneLumaBufferPool(capacity = 3)
        val first = pool.tryAcquire(8)!!
        val second = pool.tryAcquire(8)!!
        val third = pool.tryAcquire(8)!!
        val firstBytes = first.bytes

        assertNull(pool.tryAcquire(8))
        assertEquals(3, pool.allocationCount)

        first.close()
        val reused = pool.tryAcquire(8)!!
        assertSame(firstBytes, reused.bytes)
        assertEquals(3, pool.allocationCount)

        reused.close()
        second.close()
        third.close()
        assertEquals(3, pool.availableCount())
    }

    @Test
    fun retainedFramePreventsPrematureSlotReuse() {
        val pool = ZoneLumaBufferPool(capacity = 1)
        val lease = pool.tryAcquire(4)!!
        val originalBytes = lease.bytes
        val frame = ZoneTrackingFrame(2, 2, lease, 0)
        val latestReference = frame.retained()

        frame.close()
        assertNull(pool.tryAcquire(4))

        latestReference.close()
        val reused = pool.tryAcquire(4)!!
        assertSame(originalBytes, reused.bytes)
        reused.close()
    }

    @Test
    fun copierHandlesRowAndPixelStrideWithoutTemporaryRows() {
        val source = ByteArray(16)
        source[0] = 1
        source[2] = 2
        source[4] = 3
        source[8] = 4
        source[10] = 5
        source[12] = 6
        val destination = ByteArray(6)

        assertTrue(
            ZoneYuvLumaCopier.copy(
                width = 3,
                height = 2,
                source = ByteBuffer.wrap(source),
                rowStride = 8,
                pixelStride = 2,
                destination = destination,
            ),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), destination)
    }
}
