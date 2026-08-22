package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedMeteringSessionTest {
    @Test
    fun `review session never changes captured exposure`() {
        val record = record()

        val playback = RecordedMeteringSession.from(record).copy(
            mode = ParameterRecordMode.ZONE,
            apertureCoordinate = record.apertureCoordinate + 2.0,
            shutterCoordinate = record.shutterCoordinate + 2.0,
        )

        assertEquals(ParameterRecordMode.NORMAL, record.mode)
        assertEquals(3.0, record.apertureCoordinate, 0.0)
        assertEquals(-7.0, record.shutterCoordinate, 0.0)
        assertEquals(ParameterRecordMode.ZONE, playback.mode)
        assertEquals(5.0, playback.apertureCoordinate, 0.0)
        assertEquals(-5.0, playback.shutterCoordinate, 0.0)
    }

    @Test
    fun `normal RAW record reopens in Zone after review points were added`() {
        val point = RecordedZonePoint(1, 0.5f, 0.5f, 10.0, MeteringSource.RAW)
        val record = record().copy(zonePoints = listOf(point))

        assertEquals(ParameterRecordMode.NORMAL, record.mode)
        assertEquals(ParameterRecordMode.ZONE, RecordedMeteringSession.from(record).mode)
    }

    private fun record() = ParameterRecordEntry(
        id = "record",
        categoryId = "category",
        capturedAtEpochMs = 1L,
        previewPath = "preview.jpg",
        rawPath = "capture.dng",
        mode = ParameterRecordMode.NORMAL,
        apertureCoordinate = 3.0,
        shutterCoordinate = -7.0,
        ei = 400,
        ev100 = 10.0,
        filmId = "film",
        filmName = "Film",
        filmIso = 400,
        notes = emptyList(),
        location = null,
        zonePoints = emptyList(),
        rawGrid = null,
    )
}
