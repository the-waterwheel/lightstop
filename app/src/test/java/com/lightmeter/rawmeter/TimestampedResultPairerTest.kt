package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimestampedResultPairerTest {
    @Test
    fun `pairs callbacks in either delivery order`() {
        val released = mutableListOf<String>()
        val pairer = TimestampedResultPairer<String, String>(released::add)

        assertNull(pairer.offerImage(10L, "image-10"))
        assertEquals(
            TimestampedResultPair(10L, "image-10", "result-10"),
            pairer.offerResult(10L, "result-10"),
        )

        assertNull(pairer.offerResult(20L, "result-20"))
        assertEquals(
            TimestampedResultPair(20L, "image-20", "result-20"),
            pairer.offerImage(20L, "image-20"),
        )
        assertEquals(emptyList<String>(), released)
    }

    @Test
    fun `releases replaced and cleared unmatched images`() {
        val released = mutableListOf<String>()
        val pairer = TimestampedResultPairer<String, String>(released::add)

        pairer.offerImage(10L, "old-image")
        pairer.offerImage(10L, "new-image")
        pairer.offerImage(20L, "other-image")
        pairer.offerResult(30L, "unmatched-result")

        assertEquals(listOf("old-image"), released)
        assertEquals(2, pairer.pendingImageCount)
        assertEquals(1, pairer.pendingResultCount)

        pairer.clear()

        assertEquals(listOf("old-image", "new-image", "other-image"), released)
        assertEquals(0, pairer.pendingImageCount)
        assertEquals(0, pairer.pendingResultCount)
    }
}
