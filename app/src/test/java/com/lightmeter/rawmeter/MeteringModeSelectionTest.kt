package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class MeteringModeSelectionTest {
    @Test fun globalReturnsAfterEveryRoundTripThroughZone() {
        val selection = MeteringModeSelection(MeteringMode.AVERAGE)
        repeat(3) {
            assertEquals(MeteringMode.AVERAGE, selection.current)
            selection.setZoneActive(true)
            assertEquals(MeteringMode.ANGLE, selection.current)
            assertEquals(MeteringMode.AVERAGE, selection.normalMode)
            selection.setZoneActive(false)
        }
        assertEquals(MeteringMode.AVERAGE, selection.current)
    }

    @Test fun aTemporaryZoneChoiceNeverReplacesNormalGlobal() {
        val selection = MeteringModeSelection(MeteringMode.AVERAGE)
        selection.setZoneActive(true)
        selection.current = MeteringMode.SPOT
        assertEquals(MeteringMode.SPOT, selection.current)
        assertEquals(MeteringMode.AVERAGE, selection.normalMode)
        selection.setZoneActive(true) // Repeated layout notification preserves the active choice.
        assertEquals(MeteringMode.SPOT, selection.current)
        selection.setZoneActive(false)
        assertEquals(MeteringMode.AVERAGE, selection.current)
        selection.setZoneActive(true)
        assertEquals(MeteringMode.ANGLE, selection.current)
    }

    @Test fun choosingGlobalInsideZoneUsesAngleAndSavesGlobalForNormal() {
        val selection = MeteringModeSelection(MeteringMode.SPOT)
        selection.setZoneActive(true)
        selection.current = MeteringMode.AVERAGE
        assertEquals(MeteringMode.ANGLE, selection.current)
        assertEquals(MeteringMode.AVERAGE, selection.normalMode)
        selection.setZoneActive(false)
        assertEquals(MeteringMode.AVERAGE, selection.current)
    }

    @Test fun existingLocalModeSelectionsKeepTheirSharedBehavior() {
        for (mode in listOf(MeteringMode.CENTER_WEIGHTED, MeteringMode.SPOT, MeteringMode.ANGLE)) {
            val selection = MeteringModeSelection(mode)
            selection.setZoneActive(true)
            assertEquals(mode, selection.current)
            selection.current = MeteringMode.SPOT
            selection.setZoneActive(false)
            assertEquals(MeteringMode.SPOT, selection.current)
        }
    }

    @Test fun globalUsesAngleForZoneAndSpotOnlyForSourceCalibration() {
        assertEquals(MeteringMode.ANGLE, MeteringAreaPolicy.zoneMode(MeteringMode.AVERAGE))
        assertEquals(MeteringMode.SPOT, MeteringAreaPolicy.localMode(MeteringMode.AVERAGE))
        for (mode in listOf(MeteringMode.CENTER_WEIGHTED, MeteringMode.SPOT, MeteringMode.ANGLE)) {
            assertEquals(mode, MeteringAreaPolicy.zoneMode(mode))
        }
    }
}
