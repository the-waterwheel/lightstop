package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun negativeAdjustmentsNeverCoverMoreThanHalfTheImageAtLargeTextSizes() {
        for ((w, h) in listOf(360 to 800, 360 to 480, 800 to 360, 1260 to 2800, 2800 to 1260)) {
            for (handle in listOf(48, 96, 180, 300)) {
                val drawer = AdaptiveLayout.drawer(w, h, 1f, 96, handle, 2400, true, false)
                assertTrue(drawer.width.toLong() * drawer.height <= w.toLong() * h / 2)
            }
        }
    }
    @Test fun normalAndLargeWindowsKeepOriginalDesignDensity() {
        for (scale in listOf(1f, 2f, 3.5f)) {
            for ((w, h) in listOf(360 to 640, 640 to 360, 800 to 1280, 1280 to 800)) {
                assertEquals(scale, AdaptiveLayout.density((w * scale).toInt(), (h * scale).toInt(),
                    scale, LayoutProfile.METER), 0.0001f)
            }
            assertEquals(scale, AdaptiveLayout.density((360 * scale).toInt(), (340 * scale).toInt(),
                scale, LayoutProfile.EDITOR), 0.0001f)
        }
    }

    @Test fun compactScaleIsBoundedIdempotentAndIndependentOfPixelDensity() {
        for (profile in LayoutProfile.entries) for ((w, h) in listOf(180 to 160, 240 to 300, 600 to 180)) {
            val first = AdaptiveLayout.density(w, h, 1f, profile)
            assertTrue(first > 0 && first <= 1)
            assertEquals(first, AdaptiveLayout.density(w, h, first, profile), 0.0001f)
            assertEquals(first * 3, AdaptiveLayout.density(w * 3, h * 3, 3f, profile), 0.0001f)
        }
    }

    @Test fun zeroMeasurementDoesNotPoisonLaterLayout() {
        assertEquals(3f, AdaptiveLayout.density(0, 0, 3f, LayoutProfile.METER), 0f)
        assertEquals(1f, AdaptiveLayout.density(0, 0, Float.NaN, LayoutProfile.TOOL), 0f)
        assertEquals(3f, AdaptiveLayout.density(1080, 1920, 3f, LayoutProfile.METER), 0f)
    }

    @Test fun gridKeepsAuthoredColumnsAndReflowsOnlyWhenNecessary() {
        assertEquals(3, AdaptiveLayout.columns(360f, 10f, 8f, 72f, 3))
        assertEquals(3, AdaptiveLayout.columns(1200f, 10f, 8f, 72f, 3))
        assertEquals(2, AdaptiveLayout.columns(220f, 10f, 8f, 72f, 3))
        assertEquals(1, AdaptiveLayout.columns(120f, 10f, 8f, 72f, 3))
        assertEquals(1, AdaptiveLayout.columns(0f, 10f, 8f, 72f, 3))
    }

    @Test fun systemInsetsAreNotAppliedTwice() {
        assertEquals(0, AdaptiveLayout.remainingInset(40, 40))
        assertEquals(40, AdaptiveLayout.remainingInset(40, 0))
        assertEquals(20, AdaptiveLayout.remainingInset(40, 20))
        assertEquals(0, AdaptiveLayout.remainingInset(40, 60))
    }

    @Test fun negativeDrawerPreservesOriginalPortraitAndLandscapePlacement() {
        val portrait = AdaptiveLayout.drawer(360, 800, 1f, 52, 48, 1200, true, false)
        assertFalse(portrait.side)
        assertEquals(360, portrait.width)
        assertEquals(352, portrait.height)
        assertEquals(448, portrait.top)
        val right = AdaptiveLayout.drawer(800, 360, 1f, 52, 48, 1200, true, false)
        val left = AdaptiveLayout.drawer(800, 360, 1f, 52, 48, 1200, true, true)
        assertTrue(right.side)
        assertEquals(320, right.width)
        assertEquals(308, right.height)
        assertEquals(480, right.left)
        assertEquals(0, left.left)
    }

    @Test fun expandedTextAndShortWindowsKeepDrawerInsideAvailableBounds() {
        for (w in listOf(0, 160, 360, 800)) for (h in listOf(0, 80, 160, 640)) {
            for (expanded in listOf(false, true)) for (leftHanded in listOf(false, true)) {
                val drawer = AdaptiveLayout.drawer(w, h, 1f, 96, 72, 1800, expanded, leftHanded)
                assertTrue(drawer.width >= 0 && drawer.height >= 0)
                assertTrue(drawer.left >= 0 && drawer.top >= 0)
                assertTrue(drawer.left + drawer.width <= w && drawer.top + drawer.height <= h)
                assertTrue(drawer.top >= minOf(96, h))
            }
        }
    }
}
