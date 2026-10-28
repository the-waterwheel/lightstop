package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.pow

/** Main-thread state for the small automatic meter; never overwrites Normal/Zone readings. */
internal class AutoMeterSession {
    var reading: MeterReading? = null
        private set
    var locked = false
        private set
    var rawPending = false
        private set
    var error: String? = null
        private set
    var simulation = true
    var signalLimited = false
        private set
    var recoveryCameraEv100: Double? = null
        private set
    private var filteredEv: Double? = null

    fun accept(reading: MeterReading): Boolean {
        if (locked || rawPending || !reading.sceneEv100.isFinite()) return false
        if (reading.clippedFraction > .7 || reading.rawLuma < .001) {
            val cameraEv = ExposurePreviewMath.cameraEv100(reading.aperture.toDouble(),reading.exposureTimeNs,reading.captureIso) ?: return false
            val previousReading = this.reading
            val previousCameraEv = previousReading?.let {
                ExposurePreviewMath.cameraEv100(it.aperture.toDouble(),it.exposureTimeNs,it.captureIso)
            }
            val expected = if (previousReading != null && previousCameraEv != null)
                previousReading.rawLuma * 2.0.pow(previousCameraEv-cameraEv) else .18
            // Deliberate exposure compensation may itself clip. Hold the last valid scene EV;
            // do not feed clipped pixels back into that selection. A newly out-of-range scene
            // instead walks camera exposure toward usable signal, through the ordinary ramp.
            val deliberate = if(reading.clippedFraction>.7) expected>.75 else expected<.001
            recoveryCameraEv100 = if(deliberate) null else cameraEv + if(reading.clippedFraction>.7) .8 else -.8
            signalLimited = true
            return true
        }
        if (recoveryCameraEv100 != null) filteredEv = null
        recoveryCameraEv100 = null
        val wasLimited = signalLimited
        signalLimited = false
        // A small dead band prevents preview noise from continually restarting the exposure ramp.
        val previous = filteredEv
        val next = if (previous == null) reading.sceneEv100 else
            previous + (reading.sceneEv100 - previous).coerceIn(-2.0, 2.0) * .55
        val stable = previous != null && abs(next - previous) < .06
        if (stable && this.reading?.source == reading.source &&
            abs((this.reading?.evaluative?.exposureReductionEv ?: 0.0)-(reading.evaluative?.exposureReductionEv ?: 0.0)) < .03) {
            val changed = error != null || wasLimited
            error = null
            return changed
        }
        filteredEv = if(stable) previous else next
        this.reading = reading.copy(sceneEv100 = checkNotNull(filteredEv))
        error = null
        return true
    }

    fun beginRaw(): Boolean {
        if (rawPending) return false
        rawPending = true
        recoveryCameraEv100 = null
        signalLimited = false
        error = null
        return true
    }

    fun finishRaw(reading: MeterReading): Boolean {
        if (!rawPending || reading.source != MeteringSource.RAW || !reading.sceneEv100.isFinite()) return false
        rawPending = false
        locked = true
        filteredEv = reading.sceneEv100
        this.reading = reading
        error = null
        return true
    }

    fun fail(message: String) { rawPending = false; error = message }
    fun unlock(clear: Boolean = false) {
        locked = false
        signalLimited = false
        recoveryCameraEv100 = null
        error = null
        if (clear) { reading = null; filteredEv = null }
    }
}
