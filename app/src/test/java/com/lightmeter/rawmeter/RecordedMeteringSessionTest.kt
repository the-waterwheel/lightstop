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
        val record = record().copy(
            zonePoints = listOf(point),
            rawGrid = RecordedRawGrid(2, 1, floatArrayOf(1f, 2f), 1f),
        )

        assertEquals(ParameterRecordMode.NORMAL, record.mode)
        assertEquals(ParameterRecordMode.ZONE, RecordedMeteringSession.from(record).mode)
    }

    @Test
    fun `zone history stays normal without a recorded raw grid`() {
        val record = record().copy(
            mode = ParameterRecordMode.ZONE,
            zonePoints = listOf(RecordedZonePoint(1, 0.5f, 0.5f, 10.0, MeteringSource.RAW)),
        )

        assertEquals(false, RecordedHistoryCapability.canRecalculateZone(record))
        assertEquals(ParameterRecordMode.NORMAL, RecordedMeteringSession.from(record).mode)
    }

    @Test
    fun `RAW point EV falls back to captured parameter exposure`() {
        val record = record().copy(
            ev100 = null,
            rawGrid = RecordedRawGrid(
                width = 2,
                height = 1,
                values = floatArrayOf(1f, 2f),
                referenceLuma = 1f,
            ),
        )

        // Captured EV is 3 - (-7) - log2(400/100) = 8; the bright cell is +1 EV.
        assertEquals(9.0, record.rawEv100At(1f, 0.5f)!!, 0.0001)
    }

    @Test
    fun `Zone rail changes exposure while respecting the locked parameter`() {
        val session = RecordedMeteringSession.from(record())

        val apertureLocked = session.exposureShifted(
            stops = -1.0,
            lockMode = ExposureLockMode.APERTURE,
            apertureStep = ExposureStep.THIRD,
            shutterStep = ExposureStep.THIRD,
        )
        assertEquals(3.0, apertureLocked.apertureCoordinate, 0.0)
        assertEquals(-6.0, apertureLocked.shutterCoordinate, 0.0)

        val shutterLocked = session.exposureShifted(
            stops = 1.0,
            lockMode = ExposureLockMode.SHUTTER,
            apertureStep = ExposureStep.THIRD,
            shutterStep = ExposureStep.THIRD,
        )
        assertEquals(4.0, shutterLocked.apertureCoordinate, 0.0)
        assertEquals(-7.0, shutterLocked.shutterCoordinate, 0.0)
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
