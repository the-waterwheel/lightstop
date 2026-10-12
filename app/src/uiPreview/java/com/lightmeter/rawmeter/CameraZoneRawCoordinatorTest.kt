package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Verifies the cross-session contract without relying on a device HAL or real RAW requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CameraZoneRawCoordinatorTest {
    @Test fun readingIsDeliveredBeforeRestoreFinishesButTheOperationStaysLocked() {
        val f = Fixture()
        f.beginMetering()
        val reading = reading()
        assertTrue(f.owner.onReading(reading))
        assertTrue(f.owner.isRestoring)
        assertTrue(f.owner.isActive)
        assertTrue(f.host.finishes.isEmpty())
        assertTrue(f.listener.events.isEmpty()) // UI delivery is queued on the main handler.
        drain()
        assertEquals(listOf("restore:true", "reading"), f.listener.events)
        assertSame(reading, f.listener.readings.single())
        assertEquals(listOf(CameraSessionProfile.RAW_ISOLATED, CameraSessionProfile.COMPATIBLE), f.host.sessions)

        f.owner.onResidentSessionConfigured()
        f.owner.onResidentSessionConfigured() // Duplicate restore completion must not deliver again.
        drain()
        assertFalse(f.owner.isActive)
        assertEquals(listOf(true), f.host.finishes)
        assertEquals(listOf("restore:true", "reading", "restore:false"), f.listener.events)
    }

    @Test fun batchKeepsOneResultListAndRestoresTheLiveResidentPolicy() {
        val f = Fixture()
        f.beginMetering(plan(batch = true))
        f.host.residentProfile = CameraSessionProfile.PREVIEW_ONLY
        val results = listOf(ZoneMeteringResult(1, reading()), ZoneMeteringResult(2, reading()))
        assertTrue(f.owner.onBatchReading(results))
        drain()
        assertSame(results, f.listener.batches.single())
        assertEquals(CameraSessionProfile.PREVIEW_ONLY, f.host.sessions.last())
        f.owner.onResidentSessionConfigured()
        drain()
        assertEquals(listOf("restore:true", "batch", "restore:false"), f.listener.events)
        assertTrue(f.host.fallbacks.isEmpty())
    }

    @Test fun rawConfigurationFailureRestoresPreviewBeforeCompatibleFallback() {
        val f = Fixture()
        f.owner.begin(plan())
        assertTrue(f.owner.onSessionFailure(IllegalStateException("HAL rejected RAW")))
        assertTrue(f.owner.isRestoring)
        assertTrue(f.host.fallbacks.isEmpty())
        assertFalse(f.owner.onRawSessionConfigured(CameraSessionProfile.RAW_ISOLATED))
        f.owner.onResidentSessionConfigured()
        assertFalse(f.owner.isActive)
        assertEquals(1, f.host.fallbacks.size)
        assertTrue(f.host.finishes.isEmpty()) // The compatible operation now owns completion.
        assertTrue(f.listener.events.isEmpty())
    }

    @Test fun immediateRawSwitchFailureAlsoRestoresBeforeFallback() {
        val f = Fixture()
        f.host.rejectedProfile = CameraSessionProfile.RAW_ISOLATED
        f.owner.begin(plan())
        assertTrue(f.owner.isRestoring)
        assertTrue(f.host.fallbacks.isEmpty())
        f.owner.onResidentSessionConfigured()
        assertEquals("Unable to start RAW metering", f.host.fallbacks.single().message)
    }

    @Test fun failedPointKeepsItsTargetAndRoiForCompatibleFallback() {
        val f = Fixture()
        f.beginMetering()
        val failure = ZoneRawFailure("capture failed", MeteringMode.ANGLE, target(0.25f), 0.12f)
        assertTrue(f.owner.onMeteringError(failure))
        f.owner.onResidentSessionConfigured()
        assertSame(failure, f.host.fallbacks.single())
        assertTrue(f.listener.events.isEmpty())
    }

    @Test fun confirmedRawWorkflowReportsFailureWithoutCompatibleFallback() {
        val f = Fixture()
        f.host.confirmedRawWorkflow = true
        f.beginMetering()
        f.owner.onMeteringError(ZoneRawFailure("failed confirmed RAW", MeteringMode.CENTER_WEIGHTED, null, null))
        f.owner.onResidentSessionConfigured()
        assertEquals(listOf(false), f.host.finishes)
        assertEquals(listOf("failed confirmed RAW"), f.host.errors)
        assertTrue(f.host.fallbacks.isEmpty())
    }

    @Test fun batchPhysicalLensSwitchDiscardsAllReadingsWithoutFallback() {
        val f = Fixture()
        f.beginMetering(plan(batch = true))
        f.owner.observePhysicalCamera("telephoto")
        f.owner.onBatchReading(listOf(ZoneMeteringResult(1, reading())))
        f.owner.onResidentSessionConfigured()
        drain()
        assertTrue(f.listener.batches.isEmpty())
        assertTrue(f.host.fallbacks.isEmpty())
        assertEquals(listOf(false), f.host.finishes)
        assertEquals("RAW switched physical lenses; this batch was not applied", f.host.errors.single())
    }

    @Test fun pointPhysicalLensSwitchDiscardsReadingAndFallsBackAfterRestore() {
        val f = Fixture()
        f.beginMetering()
        f.owner.observePhysicalCamera("telephoto")
        f.owner.onReading(reading())
        f.owner.onResidentSessionConfigured()
        drain()
        assertTrue(f.listener.readings.isEmpty())
        assertEquals(1, f.host.fallbacks.size)
        assertTrue(f.host.finishes.isEmpty())
    }

    @Test fun missingPhysicalMetadataDoesNotInvalidateAReading() {
        val f = Fixture()
        f.beginMetering()
        f.owner.observePhysicalCamera(null)
        f.owner.onReading(reading())
        f.owner.onResidentSessionConfigured()
        drain()
        assertEquals(1, f.listener.readings.size)
        assertEquals(listOf(true), f.host.finishes)
    }

    @Test fun restoreFailureAfterDeliveryKeepsReadingAndUnlocksBeforeSafeRecovery() {
        val f = Fixture()
        f.beginMetering()
        f.host.rejectedProfile = CameraSessionProfile.COMPATIBLE
        f.owner.onReading(reading())
        drain()
        assertFalse(f.owner.isActive)
        assertEquals(listOf("restore:true", "reading", "restore:false"), f.listener.events)
        assertEquals(listOf(false), f.host.finishes)
        assertEquals(listOf("finish:false", "error", "recover"), f.host.terminalEvents)
        assertEquals(1, f.listener.readings.size)
        assertTrue(f.host.fallbacks.isEmpty())
    }

    @Test fun asynchronousRestoreFailureWhileStoppedDoesNotReopenCamera() {
        val f = Fixture()
        f.beginMetering()
        f.owner.onMeteringError(ZoneRawFailure("RAW failed", MeteringMode.CENTER_WEIGHTED, null, null))
        f.host.started = false
        assertTrue(f.owner.onSessionFailure(IllegalStateException("restore failed")))
        assertFalse(f.owner.isActive)
        assertEquals(listOf("finish:false", "error"), f.host.terminalEvents)
        assertEquals(listOf("RAW failed"), f.host.errors)
        assertTrue(f.listener.events.isEmpty())
    }

    @Test fun unavailableCameraFinishesWithoutAttemptingSessionReplacement() {
        val f = Fixture()
        f.host.rawSessionReady = false
        f.owner.begin(plan())
        assertFalse(f.owner.isActive)
        assertTrue(f.host.sessions.isEmpty())
        assertEquals(listOf(false), f.host.finishes)
        assertEquals(listOf("The RAW stream is not ready"), f.host.errors)
    }

    @Test fun closeDiscardsTransactionAndSubsequentSessionCallbacksCannotRestoreIt() {
        val f = Fixture()
        f.beginMetering()
        f.owner.reset()
        f.owner.onResidentSessionConfigured()
        assertFalse(f.owner.onSessionFailure(IllegalStateException("old session")))
        assertFalse(f.owner.onRawSessionConfigured(CameraSessionProfile.RAW_ISOLATED))
        assertFalse(f.owner.isActive)
        assertEquals(listOf(CameraSessionProfile.RAW_ISOLATED), f.host.sessions)
        assertTrue(f.host.finishes.isEmpty())
    }

    @Test fun batchReferencesAreFrozenFromOneBorrowedFrameBeforeTheRawSwitch() {
        val f = Fixture()
        val frame = ZoneTrackingFrame(128, 128, ByteArray(128 * 128) { (it % 251).toByte() }, 0)
        f.referenceFrame = frame
        val original = plan(batch = true)
        f.owner.begin(original)
        assertEquals(1, f.referenceReads)
        assertTrue(f.owner.onRawSessionConfigured(CameraSessionProfile.RAW_ISOLATED))
        val frozen = f.host.plans.single()
        assertEquals(listOf(1, 2), frozen.zoneBatchTargets.map { it.markerId })
        assertTrue(frozen.zoneBatchTargets.all { it.previewReference?.samples?.size == 121 })
        val firstSamples = frozen.zoneBatchTargets.first().previewReference!!.samples.copyOf()
        frame.luma.fill(0) // After the switch, changes to the producer cannot change frozen samples.
        assertArrayEquals(firstSamples, frozen.zoneBatchTargets.first().previewReference!!.samples, 0f)
        frame.close()
    }

    private class Fixture {
        val host = FakeHost()
        val listener = FakeListener()
        var referenceFrame: ZoneTrackingFrame? = null
        var referenceReads = 0
        val owner = CameraZoneRawCoordinator(
            Handler(Looper.getMainLooper()), host, listener,
            latestReferenceFrame = { referenceReads++; referenceFrame },
            localized = { _, english -> english },
        )
        fun beginMetering(plan: MeteringPlan = plan()) {
            owner.begin(plan)
            assertTrue(owner.onRawSessionConfigured(CameraSessionProfile.RAW_ISOLATED))
        }
    }

    private class FakeHost : CameraZoneRawHost {
        override var rawSessionReady = true
        override var started = true
        override var cameraInfo = CameraUiInfo(cameraId = "0", activePhysicalCameraId = "wide")
        override val characteristics: CameraCharacteristics? = null
        override val rawHardwareAvailable = true
        override var confirmedRawWorkflow = false
        var residentProfile = CameraSessionProfile.COMPATIBLE
        var rejectedProfile: CameraSessionProfile? = null
        val sessions = mutableListOf<CameraSessionProfile>()
        val plans = mutableListOf<MeteringPlan>()
        val finishes = mutableListOf<Boolean>()
        val fallbacks = mutableListOf<ZoneRawFailure>()
        val errors = mutableListOf<String>()
        val terminalEvents = mutableListOf<String>()
        override fun resetCaptureState() = Unit
        override fun clearPendingResidentSession() = Unit
        override fun desiredResidentProfile() = residentProfile
        override fun restoreCharacteristics(characteristics: CameraCharacteristics?) = Unit
        override fun reconfigureSession(profile: CameraSessionProfile): Boolean {
            sessions.add(profile)
            return profile != rejectedProfile
        }
        override fun startMeasurement(plan: MeteringPlan) { plans.add(plan) }
        override fun postInfo(info: CameraUiInfo) { cameraInfo = info }
        override fun finishOperation(rawSucceeded: Boolean) {
            finishes.add(rawSucceeded)
            terminalEvents.add("finish:$rawSucceeded")
        }
        override fun fallbackToCompatible(failure: ZoneRawFailure) { fallbacks.add(failure) }
        override fun postMeterError(message: String) { errors.add(message); terminalEvents.add("error") }
        override fun recoverSafePreview() { terminalEvents.add("recover") }
    }

    private class FakeListener : CameraZoneRawListener {
        val events = mutableListOf<String>()
        val readings = mutableListOf<MeterReading>()
        val batches = mutableListOf<List<ZoneMeteringResult>>()
        override fun onRestoreStateChanged(restoring: Boolean) { events.add("restore:$restoring") }
        override fun onReading(reading: MeterReading) { readings.add(reading); events.add("reading") }
        override fun onBatchReading(results: List<ZoneMeteringResult>) { batches.add(results); events.add("batch") }
    }

    private companion object {
        fun drain() = shadowOf(Looper.getMainLooper()).idle()
        fun reading() = MeterReading(8.0, 0.12, 0.0, 2, 100, 10_000_000L, 2f)
        fun target(x: Float) = ZoneMeteringTarget(x, 0.5f, x, 0.5f, 1f, 1f)
        fun plan(batch: Boolean = false) = MeteringPlan(
            FrameFormat.ALL.first(), 1f, MeteringMode.CENTER_WEIGHTED, target(0.5f),
            AngleMeteringMath.DEFAULT_DEGREES, null, 1f, 90, 1f, null, null,
            zoneBatchTargets = if (batch) listOf(
                ZoneRawBatchTarget(1, target(0.25f), null), ZoneRawBatchTarget(2, target(0.75f), null),
            ) else emptyList(),
        )
    }
}
