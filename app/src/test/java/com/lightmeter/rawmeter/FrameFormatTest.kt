package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameFormatTest {
    @Test
    fun panoramicFormatUsesGenericRatioName() {
        val format = FrameFormat.ALL.single { it.id == "65x24" }

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

    @Test
    fun `existing persisted indices remain stable when large formats are appended`() {
        assertEquals(
            listOf("135", "half", "645", "66", "67", "69", "65x24"),
            FrameFormat.ALL.take(7).map(FrameFormat::id),
        )
        assertEquals(
            listOf("612", "617", "45", "57", "810"),
            FrameFormat.ALL.drop(7).map(FrameFormat::id),
        )
    }

    @Test
    fun `large formats preserve aspect while using their own focal length scale`() {
        val fullFrame = FrameFormat.ALL.single { it.id == "135" }
        val fourByFive = FrameFormat.ALL.single { it.id == "45" }
        val eightByTen = FrameFormat.ALL.single { it.id == "810" }

        assertEquals(1.25f, fourByFive.landscapeAspect, 0.0001f)
        assertEquals(1.25f, eightByTen.landscapeAspect, 0.0001f)
        assertEquals(40, fullFrame.focalLengthForFullFrameEquivalent(40.0))
        assertEquals(142, fourByFive.focalLengthForFullFrameEquivalent(40.0))
        assertEquals(284, eightByTen.focalLengthForFullFrameEquivalent(40.0))
    }

    @Test
    fun `expanded format selector wraps instead of compressing twelve labels`() {
        val wide = InstrumentPresentation.formatMenuGrid(
            itemCount = FrameFormat.ALL.size,
            availableWidth = 300f,
            density = 1f,
        )
        val narrow = InstrumentPresentation.formatMenuGrid(
            itemCount = FrameFormat.ALL.size,
            availableWidth = 170f,
            density = 1f,
        )
        val zone = InstrumentPresentation.formatMenuGridWithMaximumRows(
            itemCount = FrameFormat.ALL.size,
            maximumRows = 6,
        )

        assertEquals(FormatMenuGrid(columns = 6, rows = 2), wide)
        assertEquals(FormatMenuGrid(columns = 4, rows = 3), narrow)
        assertEquals(FormatMenuGrid(columns = 2, rows = 6), zone)
    }
}
