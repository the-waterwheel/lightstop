package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.pow

/** Main-thread state for the small automatic meter; never overwrites Normal/Zone readings. */
internal class AutoMeterSession(private val nanoClock: () -> Long = System::nanoTime) {
    var reading: MeterReading? = null
        private set
    var locked = false
        private set
    var rawPending = false
        private set
    var error: String? = null
        private set
    var simulation = true
    var continuousMetering = true
        set(value) {
            field=value
            if(!value) { locked=false;recoveryCameraEv100=null;signalLimited=false;resetConfirmation() }
        }
    var sampleIntervalMs = 350L
    var showInterfaceText = true
    var smoothMetering = false
    private var manualExpectsRaw = true
    private var restoreLockOnFailure = false
    val manualUsesRaw: Boolean get() = rawPending && manualExpectsRaw
    fun shouldUseRaw(rawSupported: Boolean): Boolean = rawSupported && !smoothMetering
    var signalLimited = false
        private set
    var recoveryCameraEv100: Double? = null
        private set
    private var filteredEv: Double? = null
    private var filteredExposureEv: Double? = null
    private var lastSampleNs: Long? = null
    private val sceneConfirmation = ContinuousEvConfirmation()
    private val targetConfirmation = ContinuousEvConfirmation()

    fun accept(reading: MeterReading): Boolean {
        if (!continuousMetering || locked || rawPending || !reading.sceneEv100.isFinite()) return false
        if (reading.clippedFraction > .7 || reading.rawLuma < .001) {
            val cameraEv = ExposurePreviewMath.cameraEv100(reading.aperture.toDouble(),reading.exposureTimeNs,reading.captureIso) ?: return false
            return acceptSignalLimited(MeteringSignalEvidence(cameraEv, reading.rawLuma,
                reading.clippedFraction, 0.0, reading.source))
        }
        if (recoveryCameraEv100 != null) { filteredEv = null; filteredExposureEv = null }
        recoveryCameraEv100 = null
        val wasLimited = signalLimited
        signalLimited = false
        val previous = filteredEv
        val decision = reading.evaluative
        val neural = decision?.modelEpoch != null
        val target = reading.sceneEv100 + (decision?.exposureReductionEv ?: 0.0)
        if (!target.isFinite()) return false
        val sameSource = this.reading?.source == reading.source
        val sameModel = this.reading?.evaluative?.modelEpoch == decision?.modelEpoch
        if (!sameSource || !sameModel) resetConfirmation()
        val now = nanoClock()
        val elapsedSeconds = lastSampleNs?.let { (now - it).coerceIn(0L, 1_000_000_000L) / 1e9 }
            ?: REFERENCE_INTERVAL_SECONDS
        lastSampleNs = now
        val next = sceneConfirmation.update(previous, reading.sceneEv100, elapsedSeconds)
        val previousTarget = filteredExposureEv.takeIf { sameSource && sameModel }
        val nextTarget = if (neural) targetConfirmation.update(previousTarget, target, elapsedSeconds) else target
        val sameRecommendation = if (neural) nextTarget == previousTarget else
            abs((this.reading?.evaluative?.exposureReductionEv ?: 0.0)-(decision?.exposureReductionEv ?: 0.0)) < .03
        if (next == previous && sameSource && sameModel && sameRecommendation) {
            val changed = error != null || wasLimited
            error = null
            return changed
        }
        val base = next
        filteredEv = base
        filteredExposureEv = if (neural) nextTarget else base + (decision?.exposureReductionEv ?: 0.0)
        // The offset belongs to its own base. Rebase after smoothing so an unchanged neural
        // exposure target cannot acquire a darker EV merely because the sampled base moved.
        this.reading = reading.copy(sceneEv100 = base, evaluative = decision?.copy(
            baseEv100 = base,
            exposureReductionEv = if (neural) checkNotNull(filteredExposureEv) - base else decision.exposureReductionEv,
            highlightResidualEv = decision.highlightEv100?.let {
                (it - checkNotNull(filteredExposureEv) - EvaluativeMeteringAlgorithm.HIGHLIGHT_HEADROOM_EV).coerceAtLeast(0.0)
            } ?: 0.0,
        ))
        error = null
        return true
    }

    fun acceptSignalLimited(evidence: MeteringSignalEvidence): Boolean {
        if (!continuousMetering || locked || rawPending || !evidence.cameraEv100.isFinite() ||
            !evidence.luma.isFinite() || evidence.luma < 0.0 ||
            evidence.clippedFraction !in 0.0..1.0 || evidence.darkFraction !in 0.0..1.0) return false
        val previous = reading?.takeIf { it.source == evidence.source }
        val previousCameraEv = previous?.let {
            ExposurePreviewMath.cameraEv100(it.aperture.toDouble(), it.exposureTimeNs, it.captureIso)
        }
        val expected = if (previous != null && previousCameraEv != null)
            previous.rawLuma * 2.0.pow(previousCameraEv - evidence.cameraEv100) else .18
        val step = evidence.recoveryStepEv
        // Preserve deliberate bright/dark simulations. Recovery only moves the camera, and
        // never publishes censored pixels as a scene EV or feeds them into the model filter.
        val deliberate = step?.let { if (it > 0) expected > .75 else expected < .001 } ?: false
        recoveryCameraEv100 = if (deliberate) null else step?.let { evidence.cameraEv100 + it }
        resetConfirmation()
        signalLimited = true
        error = null
        return true
    }

    fun beginRaw(): Boolean = beginManual(true)

    fun beginManual(useRaw: Boolean): Boolean {
        if (rawPending) return false
        resetConfirmation()
        manualExpectsRaw=useRaw
        restoreLockOnFailure=locked
        locked=continuousMetering
        rawPending = true
        recoveryCameraEv100 = null
        signalLimited = false
        error = null
        return true
    }

    fun finishRaw(reading: MeterReading): Boolean = reading.source == MeteringSource.RAW && finishManual(reading)

    fun finishManual(reading: MeterReading): Boolean {
        if (!rawPending || (reading.source == MeteringSource.RAW) != manualExpectsRaw || !reading.sceneEv100.isFinite()) return false
        rawPending = false
        filteredEv = reading.sceneEv100
        filteredExposureEv = reading.sceneEv100 + (reading.evaluative?.exposureReductionEv ?: 0.0)
        this.reading = reading
        error = null
        return true
    }

    fun fail(message: String) {
        if(rawPending) locked=restoreLockOnFailure
        rawPending = false; error = message
    }
    fun releaseManualLock():Boolean {
        if(!continuousMetering||!locked)return false
        locked=false;restoreLockOnFailure=false;error=null
        return true
    }
    fun unlock(clear: Boolean = false, resumeContinuous: Boolean = true) {
        resetConfirmation()
        locked = false
        continuousMetering=resumeContinuous
        signalLimited = false
        recoveryCameraEv100 = null
        error = null
        if (clear) { reading = null; filteredEv = null; filteredExposureEv = null }
    }

    private fun resetConfirmation() {
        lastSampleNs = null
        sceneConfirmation.reset()
        targetConfirmation.reset()
    }

    private class ContinuousEvConfirmation {
        private var pendingDirection = 0

        fun reset() { pendingDirection = 0 }

        fun update(previous: Double?, sample: Double, elapsedSeconds: Double): Double {
            if (previous == null) {
                reset()
                return sample
            }
            val change = sample - previous
            if (abs(change) < .12) {
                reset()
                return previous
            }
            // Small reversals are common in the live exposure feedback loop. Confirm
            // the direction across two samples before publishing a new exposure target.
            if (abs(change) < .5) {
                val direction = if (change > 0) 1 else -1
                val confirmed = pendingDirection == direction
                pendingDirection = direction
                if (!confirmed) return previous
            } else {
                reset()
            }
            // Match the previous filter weight at 350 ms, with a time-based rate limit.
            val weight = 1.0 - .45.pow(elapsedSeconds / REFERENCE_INTERVAL_SECONDS)
            val maximumStep = MAX_CHANGE_EV_PER_SECOND * elapsedSeconds
            return previous + (change * weight).coerceIn(-maximumStep, maximumStep)
        }
    }

    private companion object {
        const val REFERENCE_INTERVAL_SECONDS = .35
        const val MAX_CHANGE_EV_PER_SECOND = 3.0
    }
}
