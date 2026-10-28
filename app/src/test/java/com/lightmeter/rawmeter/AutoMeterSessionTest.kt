package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class AutoMeterSessionTest {
    private fun reading(ev: Double,source: MeteringSource = MeteringSource.YUV_PREVIEW) = MeterReading(ev,.18,0.0,1,100,10_000_000,2f,source)
    @Test fun liveNoiseHasADeadBandButSceneChangesStillConverge() {
        val s=AutoMeterSession()
        assertTrue(s.accept(reading(10.0)))
        repeat(20) { assertFalse(s.accept(reading(10.04))) }
        assertEquals(10.0,s.reading!!.sceneEv100,0.0)
        repeat(12) { s.accept(reading(13.0)) }
        assertEquals(13.0,s.reading!!.sceneEv100,.12)
        assertFalse(s.locked)
    }
    @Test fun onlyARawResultFromAnExplicitPendingCaptureCanLock() {
        val s=AutoMeterSession(); s.accept(reading(9.0))
        assertFalse(s.finishRaw(reading(8.0,MeteringSource.RAW)))
        assertTrue(s.beginRaw()); assertFalse(s.beginRaw())
        assertFalse(s.accept(reading(11.0)))
        assertFalse(s.finishRaw(reading(7.0)))
        assertTrue(s.finishRaw(reading(8.0,MeteringSource.RAW)))
        assertTrue(s.locked); assertFalse(s.rawPending)
        assertFalse(s.accept(reading(13.0)))
        assertEquals(8.0,s.reading!!.sceneEv100,0.0)
        s.unlock(clear=true); assertNull(s.reading)
        assertTrue(s.accept(reading(12.0,MeteringSource.ISP_PREVIEW)))
        assertEquals(12.0,s.reading!!.sceneEv100,0.0)
    }
    @Test fun rawErrorsDoNotPretendToHaveLockedAndCanResume() {
        val s=AutoMeterSession(); s.accept(reading(10.0)); s.beginRaw()
        s.fail("Camera busy")
        assertFalse(s.locked); assertFalse(s.rawPending)
        assertEquals("Camera busy",s.error)
        assertFalse(s.accept(reading(Double.NaN)))
        assertTrue(s.accept(reading(11.0))); assertNull(s.error)
    }
    @Test fun newlyClippedOrDarkScenesRecoverWithoutPublishingInventedSceneEvs() {
        val s=AutoMeterSession();s.accept(reading(10.0))
        val clipped=reading(7.0).copy(rawLuma=1.0,clippedFraction=.99)
        assertTrue(s.accept(clipped));assertTrue(s.signalLimited)
        val cameraEv=ExposurePreviewMath.cameraEv100(2.0,10_000_000L,100)!!
        assertEquals(cameraEv+.8,s.recoveryCameraEv100!!,.00001)
        assertEquals(10.0,s.reading!!.sceneEv100,0.0)
        s.accept(reading(14.0));assertNull(s.recoveryCameraEv100)
        assertEquals(14.0,s.reading!!.sceneEv100,0.0)
        s.accept(reading(0.0).copy(rawLuma=.00001))
        assertEquals(cameraEv-.8,s.recoveryCameraEv100!!,.00001)
    }
    @Test fun deliberateClippingHoldsTheLastValidSceneAndDoesNotChaseItsOwnSimulation() {
        val s=AutoMeterSession();s.accept(reading(10.0))
        // Three stops more exposure predicts clipping even if the scene never changed.
        s.accept(reading(7.0).copy(exposureTimeNs=80_000_000L,rawLuma=1.0,clippedFraction=.99))
        assertTrue(s.signalLimited);assertNull(s.recoveryCameraEv100)
        assertEquals(10.0,s.reading!!.sceneEv100,0.0)
    }
    @Test fun highlightProtectionAndSourceChangesStillUpdateInsideTheEvDeadBand() {
        val s=AutoMeterSession();s.accept(reading(10.0))
        assertTrue(s.accept(reading(10.01).copy(evaluative=EvaluativeMeteringResult(10.01,14.0,.4,0.0))))
        assertEquals(.4,s.reading!!.evaluative!!.exposureReductionEv,0.0)
        assertTrue(s.accept(reading(10.01,MeteringSource.ISP_PREVIEW)))
        assertEquals(MeteringSource.ISP_PREVIEW,s.reading!!.source)
    }
}
