package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameFormatTest {
    @Test
    fun panoramicFormatUsesGenericRatioName() {
        val format = FrameFormat.ALL.last()

        assertEquals("65x24", format.id)
        assertEquals("65:24", format.label)
        assertEquals("65:24", InstrumentPresentation.formatShortLabel(format))
    }
}
