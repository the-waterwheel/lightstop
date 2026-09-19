package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewHistogramTest {
    @Test
    fun `black and white pixels occupy opposite histogram ends`() {
        val histogram = PreviewHistogram.fromPixels(
            intArrayOf(0xff000000.toInt(), 0xff000000.toInt(), 0xffffffff.toInt()),
        )

        assertEquals(1f, histogram.first(), 0f)
        assertEquals(0.5f, histogram.last(), 0f)
        assertTrue(histogram.drop(1).dropLast(1).all { it == 0f })
    }
}
