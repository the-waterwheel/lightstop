package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParameterRecordDistanceRetentionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun normalAndZoneReadingSettersFreezeDistanceBeforeLaterPreviewChanges() {
        val state = MeterState(context)
        state.distanceMeasurementState = observation(2.0)
        state.lastReading = MeterReading(9.0, 0.1, 0.0, 1, 100, 10_000_000L, 2f)
        state.distanceMeasurementState = observation(8.0)
        state.distanceMeasurementState = DistanceMeasurementState()
        assertEquals(2.0, state.parameterRecordDistanceMemory.forRecording(null)!!.meters!!, 0.0)
        state.lastReading = MeterReading(10.0, 0.2, 0.0, 1, 100, 10_000_000L, 2f)
        assertEquals(8.0, state.parameterRecordDistanceMemory.forRecording(null)!!.meters!!, 0.0)
    }

    @Test fun restartFindsLatestSavedDistanceEvenWithoutTimestampsOrAcrossCategories() {
        val repository = ParameterRecordRepository(context)
        for ((group, meters) in listOf(2.0, 4.0).withIndex()) {
            repository.startCategory(group.toLong())
            val distance = ParameterRecordCaptureSnapshot.distance(observation(meters))!!
            val draft = RecordPreviewFixtures.draft(repository)
            repository.save(draft.copy(capturedAtEpochMs = null,
                snapshot = draft.snapshot.copy(distance = distance)))
            repository.finishActiveCategory()
        }
        repository.startCategory(3L)
        repository.save(RecordPreviewFixtures.draft(repository).copy(capturedAtEpochMs = null))
        // Android AtomicFile uses renameTo to replace its destination. On Windows the host
        // implementation cannot overwrite an existing file, unlike Android's POSIX rename.
        // Finish that host-only rename before simulating a fresh repository instance.
        if (System.getProperty("os.name")?.startsWith("Windows") == true) {
            val index = java.io.File(context.filesDir, "parameter_records/index.json")
            val pending = java.io.File(index.path + ".new")
            if (pending.isFile) java.nio.file.Files.move(pending.toPath(), index.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val restored = ParameterRecordRepository(context)
        assertEquals("Reloaded categories", 3, restored.categories().size)
        assertEquals("Reloaded distance snapshots", 2,
            restored.categories().sumOf { it.records.count { record -> record.distance != null } })
        val memory = MeterState(context).parameterRecordDistanceMemory
        assertEquals(4.0, memory.forRecording(restored.latestRecordedDistance())!!.meters!!, 0.0)
    }

    @Test fun oldStaleRecordsDisplayTheirDistanceInsteadOfAnExpiryMessage() {
        val repository = ParameterRecordRepository(context)
        repository.startCategory()
        val draft = RecordPreviewFixtures.draft(repository)
        val stale = ParameterRecordCaptureSnapshot.distance(observation(2.43))!!
            .copy(status = DistanceMeasurementStatus.STALE, isFreshAtCapture = false)
        repository.save(draft.copy(snapshot = draft.snapshot.copy(distance = stale)))
        val view = RecordPreviewFixtures.history(context, MeterState(context), repository,
            720, 1440, "history-detail")
        val method = ParameterHistoryView::class.java.getDeclaredMethod("formatRecordedDistance",
            RecordedDistanceSnapshot::class.java).apply { isAccessible = true }
        val label = method.invoke(view, stale) as String
        assertTrue(label.contains("2.4 m"))
        assertFalse(label.contains("过期"))
        assertFalse(label.contains("stale"))
        assertFalse(repository.categories().first().records.first().distance!!.isFreshAtCapture)
    }

    private fun observation(meters: Double) = DistanceMeasurementState(
        DistanceEstimate(meters, null, null, 0.7, DistanceQuality.MEDIUM,
            DistanceSource.FOCUS_APPROXIMATE, 1L, "0@2", NormalizedPoint.CENTER, 5, true),
        DistanceMeasurementStatus.AVAILABLE,
    )
}
