package com.lightmeter.rawmeter

/** Retained record metadata; never used as a live distance for flash or depth of field. */
internal class ParameterRecordDistanceMemory {
    private var latest: RecordedDistanceSnapshot? = null
    private var metered: RecordedDistanceSnapshot? = null

    fun observe(state: DistanceMeasurementState) {
        ParameterRecordCaptureSnapshot.distance(state)?.takeIf { it.hasDistance() }?.let {
            latest = it
        }
    }

    fun freezeAtMetering() {
        latest?.let { metered = it }
    }

    fun forRecording(previousRecord: RecordedDistanceSnapshot?): RecordedDistanceSnapshot? =
        metered ?: latest ?: previousRecord?.takeIf { it.hasDistance() }
}

internal fun RecordedDistanceSnapshot.hasDistance(): Boolean =
    meters?.let { it.isFinite() && it > 0.0 } == true
