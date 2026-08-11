package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeTransitionDirectionTest {
    @Test
    fun zoneEntryDragMirrorsWithHandedness() {
        assertEquals(80f, ModeTransitionDirection.zoneEntryDistance(200f, 120f, false), 0f)
        assertEquals(80f, ModeTransitionDirection.zoneEntryDistance(120f, 200f, true), 0f)
    }

    @Test
    fun normalEntryDragMirrorsWithHandedness() {
        assertEquals(80f, ModeTransitionDirection.normalEntryDistance(120f, 200f, false), 0f)
        assertEquals(80f, ModeTransitionDirection.normalEntryDistance(200f, 120f, true), 0f)
    }

    @Test
    fun draggingAwayFromTargetModeDoesNotProducePositiveProgress() {
        assertEquals(-80f, ModeTransitionDirection.zoneEntryDistance(200f, 120f, true), 0f)
        assertEquals(-80f, ModeTransitionDirection.normalEntryDistance(120f, 200f, true), 0f)
    }
}
