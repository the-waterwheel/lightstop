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

    @Test
    fun `pairs nearest timestamp within tolerance in either order`() {
        val pairer = TimestampedResultPairer<String, String>({}, toleranceNs = 10L)

        assertNull(pairer.offerImage(100L, "image-100"))
        assertEquals(
            TimestampedResultPair(100L, "image-100", "result-101"),
            pairer.offerResult(101L, "result-101"),
        )

        assertNull(pairer.offerResult(200L, "result-200"))
        assertEquals(
            TimestampedResultPair(199L, "image-199", "result-200"),
            pairer.offerImage(199L, "image-199"),
        )
        assertEquals(0, pairer.pendingImageCount)
        assertEquals(0, pairer.pendingResultCount)
    }

    @Test
    fun `prefers exact match over tolerance candidates`() {
        val pairer = TimestampedResultPairer<String, String>({}, toleranceNs = 10L)

        pairer.offerResult(95L, "result-95")
        pairer.offerResult(100L, "result-100")
        assertEquals(
            TimestampedResultPair(100L, "image-100", "result-100"),
            pairer.offerImage(100L, "image-100"),
        )
        assertEquals(1, pairer.pendingResultCount)
    }

    @Test
    fun `leaves pairs beyond tolerance unmatched`() {
        val pairer = TimestampedResultPairer<String, String>({}, toleranceNs = 10L)

        assertNull(pairer.offerImage(100L, "image-100"))
        assertNull(pairer.offerResult(130L, "result-130"))
        assertEquals(1, pairer.pendingImageCount)
        assertEquals(1, pairer.pendingResultCount)

        assertNull(pairer.offerImage(112L, "image-112"))
        assertNull(pairer.offerResult(123L, "result-123"))
        assertEquals(2, pairer.pendingImageCount)
        assertEquals(2, pairer.pendingResultCount)
    }

    @Test
    fun `nearest candidate wins within tolerance`() {
        val pairer = TimestampedResultPairer<String, String>({}, toleranceNs = 10L)

        pairer.offerResult(93L, "result-93")
        pairer.offerResult(96L, "result-96")
        val pair = pairer.offerImage(100L, "image-100")
        assertEquals(TimestampedResultPair(100L, "image-100", "result-96"), pair)
        assertEquals(1, pairer.pendingResultCount)
    }
}
