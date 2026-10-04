package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimestampedCaptureResultStoreTest {
    @Test
    fun returnsOnlyAnExactTimestampMatch() {
        val store = TimestampedCaptureResultStore<String>()
        store.put(100L, "frame-100")

        assertNull(store.takeExact(101L))
        assertEquals("frame-100", store.takeExact(100L))
    }

    @Test
    fun retainsOnlyTheNewestBoundedSet() {
        val store = TimestampedCaptureResultStore<String>(capacity = 2)
        store.put(10L, "a")
        store.put(20L, "b")
        store.put(30L, "c")

        assertNull(store.takeExact(10L))
        assertEquals("b", store.takeExact(20L))
        assertEquals("c", store.takeExact(30L))
    }

    @Test
    fun ignoresInvalidTimestamps() {
        val store = TimestampedCaptureResultStore<String>()
        store.put(0L, "invalid")

        assertEquals(0, store.size)
    }
}
