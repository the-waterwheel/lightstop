package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedResponseCalibrationTest {
    @Test
    fun `empty response falls back to the single offset`() {
        val response = ProcessedResponseCalibration(emptyList())
        assertEquals(0.7, response.correctionEv(0.5, 0.7), 1e-9)
    }

    @Test
    fun `single anchor is constant`() {
        val response = ProcessedResponseCalibration(listOf(ProcessedResponseAnchor(0.5, 0.3)))
        assertEquals(0.3, response.correctionEv(0.1, 0.0), 1e-9)
        assertEquals(0.3, response.correctionEv(0.9, 0.0), 1e-9)
    }

    @Test
    fun `interpolates and clamps between anchors`() {
        val response = ProcessedResponseCalibration(
            listOf(
                ProcessedResponseAnchor(0.2, 0.0),
                ProcessedResponseAnchor(0.6, 1.0),
            ),
        )
        assertEquals(0.0, response.correctionEv(0.1, 9.0), 1e-9)
        assertEquals(0.5, response.correctionEv(0.4, 9.0), 1e-9)
        assertEquals(1.0, response.correctionEv(0.9, 9.0), 1e-9)
    }

    @Test
    fun `merge replaces nearby anchors and stays sorted`() {
        val response = ProcessedResponseCalibration(emptyList())
            .withAnchor(ProcessedResponseAnchor(0.2, 0.0))
            .withAnchor(ProcessedResponseAnchor(0.6, 1.0))
            .withAnchor(ProcessedResponseAnchor(0.21, 0.1))
        assertEquals(2, response.anchors.size)
        assertEquals(0.1, response.anchors.first().correctionEv, 1e-9)
    }

    @Test
    fun `serialization round trips`() {
        val response = ProcessedResponseCalibration(
            listOf(
                ProcessedResponseAnchor(0.2, -0.5),
                ProcessedResponseAnchor(0.8, 0.25),
            ),
            residualEv = 0.12,
        )
        val parsed = ProcessedResponseCalibration.parse(response.serialize())
        assertTrue(parsed != null)
        assertEquals(response.anchors.size, parsed!!.anchors.size)
        assertEquals(-0.5, parsed.correctionEv(0.2, 0.0), 1e-4)
        assertEquals(0.25, parsed.correctionEv(0.8, 0.0), 1e-4)
    }

    @Test
    fun `blank or empty serialization parses to null`() {
        assertNull(ProcessedResponseCalibration.parse(null))
        assertNull(ProcessedResponseCalibration.parse(""))
        assertNull(ProcessedResponseCalibration.parse("0.000000"))
    }
}
