package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameFormatTest {
    @Test
    fun panoramicFormatUsesGenericRatioName() {
        val format = FrameFormat.ALL.last()

        assertEquals("65x24", format.id)
        assertEquals("65:24", format.label)
        assertEquals(
            "65:24",
            InstrumentPresentation.formatShortLabel(format, MenuLanguage.ENGLISH),
        )
    }

    @Test
    fun halfFrameFormatUsesTheSelectedLanguage() {
        val format = FrameFormat.ALL.single { it.id == "half" }

        assertEquals("半格 · 4:3", format.displayLabel(MenuLanguage.CHINESE))
        assertEquals("Half-frame · 4:3", format.displayLabel(MenuLanguage.ENGLISH))
        assertEquals("半格", InstrumentPresentation.formatShortLabel(format, MenuLanguage.CHINESE))
        assertEquals("Half", InstrumentPresentation.formatShortLabel(format, MenuLanguage.ENGLISH))
    }
}
