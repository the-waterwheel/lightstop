package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class ParameterRecordModelsTest {
    @Test
    fun `raw point lookup applies display rotation and preview crop`() {
        val values = FloatArray(16) { index -> (index + 1).toFloat() }
        val grid = RecordedRawGrid(
            width = 4,
            height = 4,
            values = values,
            referenceLuma = 1f,
            screenToSensorRotationDegrees = 90,
            cropLeft = 0.25f,
            cropTop = 0.25f,
            cropRight = 0.75f,
            cropBottom = 0.75f,
        )

        // Display top-right rotates to the sensor's crop top-left.
        assertEquals(0.0, requireNotNull(grid.relativeEvAt(1f, 0f)), 0.0001)
    }

    @Test
    fun `category summarizes one or several film stocks`() {
        val first = record("1", "Portra 400")
        val same = record("2", "Portra 400")
        val second = record("3", "HP5 Plus")

        assertEquals("Portra 400", category(listOf(first, same)).filmSummary)
        assertEquals("Portra 400…", category(listOf(first, second)).filmSummary)
    }

    private fun category(records: List<ParameterRecordEntry>) = ParameterRecordCategory(
        id = "category",
        startedAtEpochMs = 1L,
        endedAtEpochMs = null,
        records = records,
    )

    private fun record(id: String, filmName: String) = ParameterRecordEntry(
        id = id,
        categoryId = "category",
        capturedAtEpochMs = null,
        previewPath = "$id.jpg",
        rawPath = null,
        mode = ParameterRecordMode.NORMAL,
        apertureCoordinate = 0.0,
        shutterCoordinate = 0.0,
        ei = 100,
        ev100 = null,
        filmId = filmName,
        filmName = filmName,
        notes = emptyList(),
        location = null,
        zonePoints = emptyList(),
        rawGrid = null,
    )
}
