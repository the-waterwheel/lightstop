package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class MeteringSignalRecoveryTest {
    private val cameraEv = ExposurePreviewMath.cameraEv100(2.0, 10_000_000L, 100)!!
    private fun evidence(clipped: Double = 0.8, dark: Double = 0.0, luma: Double = .8) =
        MeteringSignalEvidence(cameraEv, luma, clipped, dark, MeteringSource.YUV_PREVIEW)
    private fun reading() = MeterReading(10.0, .18, 0.0, 1, 100, 10_000_000L, 2f,
        MeteringSource.YUV_PREVIEW)

    @Test fun recoveryUsesCurrentExposureAndNeverPublishesAnUnreliableScene() {
        val session = AutoMeterSession()
        session.accept(reading())
        val previous = session.reading
        assertTrue(session.acceptSignalLimited(evidence()))
        assertSame(previous, session.reading)
        assertEquals(cameraEv + .8, session.recoveryCameraEv100!!, 1e-9)
        assertTrue(session.acceptSignalLimited(evidence().copy(cameraEv100 = cameraEv + .7)))
        assertEquals(cameraEv + 1.5, session.recoveryCameraEv100!!, 1e-9)
        assertSame(previous, session.reading)
    }

    @Test fun rejectedFivePercentTailsCanRecoverWithoutWaitingForSeventyPercentClipping() {
        val session = AutoMeterSession()
        session.acceptSignalLimited(evidence(clipped = .1))
        assertEquals(cameraEv + .8, session.recoveryCameraEv100!!, 1e-9)
        session.acceptSignalLimited(evidence(clipped = 0.0, dark = .1, luma = .02))
        assertEquals(cameraEv - .8, session.recoveryCameraEv100!!, 1e-9)
        session.acceptSignalLimited(evidence(clipped = .1, dark = .1, luma = .1))
        assertNull(session.recoveryCameraEv100)
        assertTrue(session.signalLimited)
        assertNull(session.reading)
    }

    @Test fun intentionalClippingAndManualLocksDoNotChaseThePreviewExposure() {
        val session = AutoMeterSession()
        session.accept(reading())
        session.acceptSignalLimited(evidence().copy(cameraEv100 = cameraEv - 3))
        assertTrue(session.signalLimited)
        assertNull(session.recoveryCameraEv100)
        session.beginManual(false)
        assertFalse(session.acceptSignalLimited(evidence()))
        session.finishManual(reading())
        assertFalse(session.acceptSignalLimited(evidence()))
        session.unlock(resumeContinuous = false)
        assertFalse(session.acceptSignalLimited(evidence()))
    }

    @Test fun invalidMetadataCannotIssueARecoveryRequest() {
        val session = AutoMeterSession()
        assertFalse(session.acceptSignalLimited(evidence().copy(cameraEv100 = Double.NaN)))
        assertFalse(session.acceptSignalLimited(evidence(clipped = Double.NaN)))
        assertFalse(session.acceptSignalLimited(evidence(luma = -1.0)))
        assertNull(session.recoveryCameraEv100)
        assertFalse(session.signalLimited)
    }
}
