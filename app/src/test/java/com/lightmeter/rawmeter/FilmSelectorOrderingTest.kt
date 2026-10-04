package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class FilmSelectorOrderingTest {
    @Test
    fun `available films keep source order and unavailable films move to bottom`() {
        val films = listOf("no-a", "yes-a", "no-b", "yes-b")

        val sorted = FilmSelectorOrdering.availableFirst(films) { it.startsWith("yes") }

        assertEquals(listOf("yes-a", "yes-b", "no-a", "no-b"), sorted)
    }
}
