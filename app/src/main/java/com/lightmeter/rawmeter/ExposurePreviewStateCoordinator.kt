package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns the ordinary exposure-preview selection and the Camera2 exposure state derived from it.
 * Auxiliary preview calibration remains a separate workflow and can temporarily replace the
 * applied state through [applyCalibrationComparison].
 */
internal class ExposurePreviewStateCoordinator(
    private val onRequestChanged: () -> Unit,
    private val onUnavailable: () -> Unit,
    private val onMeteringStalled: () -> Unit = {},
    private val onLimitChanged: (ExposurePreviewLimit?) -> Unit = {},
    private val nanoClock: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    @Volatile
    private var requestedSelection: ExposurePreviewSelection? = null

    var manualExposure: ExposurePreviewManualExposure? = null
        private set

    var compensationSteps: Int = 0
        private set

    private var unsupportedReported = false
    private val ramp = ExposurePreviewRamp()
    private var manualTarget: ExposurePreviewManualExposure? = null
    private var manualStart: ExposurePreviewManualExposure? = null
    private var fallbackManualStart: ExposurePreviewManualExposure? = null
    private var manualAperture = 0.0
    private var minimumManualTimeNs = 1L
    private var maximumManualTimeNs = Long.MAX_VALUE
    private var minimumManualIso = 1
    private var maximumManualIso = Int.MAX_VALUE
    private var compensationTarget: Int? = null
    private var compensationStepEv = 1.0
    private var lastPreviewResult: CaptureResult? = null
    private var lastFrameTimestampNs = 0L
    private var nextRequestTimestampNs = 0L
    private var pendingManualFrames = 0
    private var nonNeutralFramesPending = false
    private var resolvedSelection: ExposurePreviewSelection? = null
    private var resolvedCharacteristics: CameraCharacteristics? = null
    private var resolvedAperture = Float.NaN
    private var resolvedManualAvailable = false
    private var needsResolve = true
    private var limit: ExposurePreviewLimit? = null
    private var samplingWaitStartedNs = nanoClock()
    private var requestPublishedFrameNs = 0L
    private var lastFrameReceivedNs = 0L
    private var lastFrameMatchesCurrentRequest = false
    private var stableActualEv: Double? = null
    private var stableActualFrames = 0
    private var samplingStallReported = false

    val isTransitioning: Boolean get() = ramp.running || manualTarget != null && manualStart == null

    /**
     * A finished ramp can still have old exposures in the camera's in-flight frame queue.
     * If the HAL persistently quantizes a request, admit fresh, stable actual exposure after a
     * bounded wait. Metering uses that frame's own exposure metadata, not the requested values.
     */
    val readyForContinuousMetering: Boolean
        get() {
            val now = nanoClock()
            val timedOut = now - samplingWaitStartedNs >= SAMPLING_WAIT_NS
            if (isTransitioning) {
                if (timedOut) reportSamplingStall()
                return false
            }
            val result = lastPreviewResult
            val manual = manualExposure
            val fresh = lastFrameMatchesCurrentRequest && lastFrameTimestampNs > requestPublishedFrameNs &&
                now - lastFrameReceivedNs in 0..MAX_FRAME_AGE_NS
            val modeMatches = result?.let(::exposureModeMatches) == true
            val matchesExposure = if (manual != null) {
                result?.let(::actualCameraEv)?.let { abs(it - manual.appliedCameraEv100) <= .1 } == true
            } else {
                result != null && result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) == compensationSteps
            }
            if (fresh && modeMatches && result?.let(::actualCameraEv) != null && (matchesExposure ||
                    timedOut && stableActualFrames >= REQUIRED_STABLE_ACTUAL_FRAMES)) return true
            if (timedOut) reportSamplingStall()
            return false
        }

    val isNeutral: Boolean
        get() = manualExposure == null && compensationSteps == 0 && !isTransitioning && !nonNeutralFramesPending

    fun updateSelection(selection: ExposurePreviewSelection?): Boolean {
        val accepted = selection?.takeIf {
            it.previewCalibratedSceneEv100.isFinite() &&
                it.selectedExposureEv100.isFinite() &&
                it.previewCorrectionEv.isFinite() &&
                it.executionCorrectionEv.isFinite()
        }
        if (requestedSelection == accepted) return false
        requestedSelection = accepted
        return true
    }

    fun clearRequestedSelection() {
        requestedSelection = null
        cancelTransition()
        resetSamplingWait(newTarget = true)
        publishLimit(null)
        needsResolve = true
    }

    fun apply(
        characteristics: CameraCharacteristics?,
        cameraInfo: CameraUiInfo,
    ) {
        val selection = requestedSelection
        if (selection != null && characteristics == null) return
        if (!needsResolve && selection == resolvedSelection && characteristics === resolvedCharacteristics &&
            cameraInfo.aperture == resolvedAperture && cameraInfo.manualSensorAvailable == resolvedManualAvailable) return
        resolvedSelection = selection
        resolvedCharacteristics = characteristics
        resolvedAperture = cameraInfo.aperture
        resolvedManualAvailable = cameraInfo.manualSensorAvailable
        needsResolve = false
        val range = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = characteristics?.get(
            CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
        )?.toDouble()
        val requestedCompensation = selection?.let(ExposurePreviewMath::requestedCompensationEv)
        val requestedManualExposure = selection?.let { requested ->
            val exposureRange = characteristics?.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
            )
            val sensitivityRange = characteristics?.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
            )
            val maximumFrameDuration = characteristics?.get(
                CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION,
            )
            val maximumExposureTime = if (exposureRange != null) {
                minOf(exposureRange.upper, maximumFrameDuration ?: exposureRange.upper)
            } else {
                null
            }
            if (!cameraInfo.manualSensorAvailable || exposureRange == null ||
                sensitivityRange == null || maximumExposureTime == null
            ) {
                null
            } else {
                minimumManualTimeNs = exposureRange.lower
                maximumManualTimeNs = maximumExposureTime
                minimumManualIso = sensitivityRange.lower
                maximumManualIso = sensitivityRange.upper
                fun exposure(ev: Double) = ExposurePreviewMath.manualExposure(
                    targetCameraEv100 = ev,
                    cameraAperture = cameraInfo.aperture.toDouble(),
                    preferredSensitivity = lastPreviewResult
                        ?.get(CaptureResult.SENSOR_SENSITIVITY) ?: sensitivityRange.lower,
                    minimumExposureTimeNs = exposureRange.lower,
                    maximumExposureTimeNs = maximumExposureTime,
                    minimumSensitivity = sensitivityRange.lower,
                    maximumSensitivity = sensitivityRange.upper,
                )
                fallbackManualStart = exposure(requested.previewCalibratedSceneEv100 - requested.previewCorrectionEv)
                exposure(ExposurePreviewMath.targetCameraEv100(requested))
            }
        }
        val compensation = if (requestedCompensation == null || range == null || step == null) {
            ExposurePreviewCompensation(
                requestedEv = requestedCompensation ?: 0.0,
                appliedEv = 0.0,
                steps = 0,
                supported = requestedCompensation == null,
                clamped = false,
            )
        } else {
            ExposurePreviewMath.quantizeCompensation(
                requestedEv = requestedCompensation,
                minimumSteps = range.lower,
                maximumSteps = range.upper,
                stepEv = step,
            )
        }
        val supported = selection == null || requestedManualExposure != null || compensation.supported
        publishLimit(when {
            selection == null -> null
            requestedManualExposure != null -> ExposurePreviewLimit.manual(selection, requestedManualExposure)
            compensation.supported -> ExposurePreviewLimit.compensation(compensation)
            else -> null
        })
        if (selection == null) {
            unsupportedReported = false
        } else if (!supported && !unsupportedReported) {
            unsupportedReported = true
            onUnavailable()
        }
        val requestedSteps = if (requestedManualExposure == null) compensation.steps else 0
        if (selection == null) {
            cancelTransition()
            publish(null, 0)
        } else if (requestedManualExposure != null) {
            compensationTarget = null
            manualAperture = cameraInfo.aperture.toDouble()
            if (manualTarget != requestedManualExposure) {
                manualTarget = requestedManualExposure
                manualStart = null
                pendingManualFrames = 0
                resetSamplingWait(newTarget = true)
                beginManualTransition()
            }
        } else {
            manualTarget = null
            manualStart = null
            val stepEv = step?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            if (compensationTarget != requestedSteps || compensationStepEv != stepEv) {
                compensationTarget = requestedSteps
                compensationStepEv = stepEv
                resetSamplingWait(newTarget = true)
                ramp.start(compensationSteps * stepEv, requestedSteps * stepEv, lastFrameTimestampNs)
            }
            publish(null, if (ramp.running) compensationSteps else requestedSteps)
        }
        Log.i(
            TAG,
            "Exposure preview: selected=${selection?.selectedExposureEv100}EV100 " +
                "targetCamera=${selection?.let(ExposurePreviewMath::targetCameraEv100)}EV100 " +
                "manual=$requestedManualExposure requested=${compensation.requestedEv}EV " +
                "applied=${compensation.appliedEv}EV steps=${compensation.steps} " +
                "supported=$supported " +
                "clamped=${requestedManualExposure?.clamped ?: compensation.clamped}",
        )
    }

    /** Only current-session preview results belong here; RAW metadata must never seed a fade. */
    fun onPreviewFrame(
        result: CaptureResult,
        timestampNs: Long,
        advance: Boolean = true,
        matchesCurrentRequest: Boolean = true,
    ) {
        if (timestampNs <= lastFrameTimestampNs) return
        lastFrameTimestampNs = timestampNs
        lastPreviewResult = result
        val receivedNs = nanoClock()
        if (receivedNs - lastFrameReceivedNs > MAX_FRAME_AGE_NS) {
            stableActualEv = null
            stableActualFrames = 0
        }
        lastFrameReceivedNs = receivedNs
        lastFrameMatchesCurrentRequest = advance && matchesCurrentRequest
        nonNeutralFramesPending = result.get(CaptureResult.CONTROL_AE_MODE) == CaptureResult.CONTROL_AE_MODE_OFF ||
            (result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0) != 0
        observeActualExposure(result)
        if (!advance) return
        if (manualTarget != null && manualStart == null) {
            pendingManualFrames++
            beginManualTransition()
            return
        }
        if (!ramp.running) return
        val value = ramp.advance(timestampNs)
        if (ramp.running && timestampNs < nextRequestTimestampNs) return
        // Preserve the pacing phase: 30.1-fps metadata must not accidentally halve the fade's
        // request rate. A long suspension starts a fresh deadline instead of a catch-up burst.
        nextRequestTimestampNs = if (timestampNs - nextRequestTimestampNs > MIN_REQUEST_INTERVAL_NS)
            timestampNs + MIN_REQUEST_INTERVAL_NS else nextRequestTimestampNs + MIN_REQUEST_INTERVAL_NS
        val target = manualTarget
        val start = manualStart
        if (target != null && start != null) {
            publish(ExposurePreviewRamp.manualAt(start, target, value, manualAperture), 0)
        } else compensationTarget?.let { steps ->
            publish(null, if (ramp.running) (value / compensationStepEv).roundToInt() else steps)
        }
    }

    private fun beginManualTransition() {
        if (lastFrameTimestampNs <= 0L) return // Seed from the first live frame after restoration.
        val target = manualTarget ?: return
        val observed = lastPreviewResult?.let { result ->
            val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return@let null
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return@let null
            ExposurePreviewMath.cameraEv100(manualAperture, time, iso)?.let { ev ->
                ExposurePreviewManualExposure(time, iso, ev, false)
            }
        }
        val source = manualExposure ?: observed ?: fallbackManualStart?.takeIf { pendingManualFrames >= 3 } ?: return
        val start = ExposurePreviewRamp.legalSeed(source, manualAperture, minimumManualTimeNs, maximumManualTimeNs, minimumManualIso, maximumManualIso)
        manualStart = start
        ramp.start(start.appliedCameraEv100, target.appliedCameraEv100, lastFrameTimestampNs)
        nextRequestTimestampNs = lastFrameTimestampNs + MIN_REQUEST_INTERVAL_NS
        publish(if (ramp.running) start else target, 0)
    }

    private fun publish(manual: ExposurePreviewManualExposure?, steps: Int) {
        if (manualExposure == manual && compensationSteps == steps) return
        manualExposure = manual
        compensationSteps = steps
        resetSamplingWait()
        if (manual != null || steps != 0) nonNeutralFramesPending = true
        onRequestChanged()
    }

    private fun exposureModeMatches(result: CaptureResult): Boolean {
        val mode = result.get(CaptureResult.CONTROL_AE_MODE) ?: return false
        return if (manualExposure != null) mode == CaptureResult.CONTROL_AE_MODE_OFF
            else mode != CaptureResult.CONTROL_AE_MODE_OFF
    }

    private fun actualCameraEv(result: CaptureResult): Double? {
        return ExposurePreviewMath.cameraEv100(
            if (manualExposure != null) manualAperture else
                result.get(CaptureResult.LENS_APERTURE)?.toDouble() ?: resolvedAperture.toDouble(),
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null,
            result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null,
        )
    }

    private fun observeActualExposure(result: CaptureResult) {
        val ev = actualCameraEv(result)?.takeIf {
            lastFrameMatchesCurrentRequest && !isTransitioning &&
                lastFrameTimestampNs > requestPublishedFrameNs && exposureModeMatches(result)
        }
        if (ev == null) {
            stableActualEv = null
            stableActualFrames = 0
        } else if (stableActualEv?.let { abs(ev - it) <= ACTUAL_STABILITY_EV } == true) {
            stableActualFrames++
        } else {
            stableActualEv = ev
            stableActualFrames = 1
        }
    }

    private fun resetSamplingWait(newTarget: Boolean = false) {
        samplingWaitStartedNs = nanoClock()
        requestPublishedFrameNs = lastFrameTimestampNs
        lastFrameMatchesCurrentRequest = false
        stableActualEv = null
        stableActualFrames = 0
        if (newTarget) samplingStallReported = false
    }

    private fun reportSamplingStall() {
        if (samplingStallReported) return
        samplingStallReported = true
        onMeteringStalled()
    }

    private fun publishLimit(value: ExposurePreviewLimit?) {
        if (limit == value) return
        limit = value
        onLimitChanged(value)
    }

    private fun cancelTransition() {
        ramp.cancel()
        manualTarget = null
        manualStart = null
        compensationTarget = null
        fallbackManualStart = null
        pendingManualFrames = 0
    }

    /** Applies the auxiliary calibration comparison without changing the ordinary selection. */
    fun applyCalibrationComparison(
        manualExposure: ExposurePreviewManualExposure?,
        compensationSteps: Int,
    ) {
        cancelTransition()
        needsResolve = true
        this.manualExposure = manualExposure
        this.compensationSteps = compensationSteps
        resetSamplingWait(newTarget = true)
        publishLimit(null)
        if (manualExposure != null || compensationSteps != 0) nonNeutralFramesPending = true
        onRequestChanged()
    }

    /** Clears preview correction but lets the controller submit it with a fresh baseline tag. */
    fun neutralizeForMetering(): Boolean {
        val changed = !isNeutral
        clearRequestedSelection()
        manualExposure = null
        compensationSteps = 0
        resetSamplingWait(newTarget = true)
        return changed
    }

    fun reset(preserveSelection: Boolean = false) {
        if (!preserveSelection) requestedSelection = null
        cancelTransition()
        manualExposure = null
        compensationSteps = 0
        lastPreviewResult = null
        lastFrameTimestampNs = 0L
        lastFrameReceivedNs = 0L
        nextRequestTimestampNs = 0L
        nonNeutralFramesPending = false
        needsResolve = true
        resetSamplingWait(newTarget = true)
        publishLimit(null)
        if (!preserveSelection) unsupportedReported = false
    }

    companion object {
        private const val TAG = "lightstop"
        private const val MIN_REQUEST_INTERVAL_NS = 33_333_333L
        private const val SAMPLING_WAIT_NS = 1_500_000_000L
        private const val MAX_FRAME_AGE_NS = 500_000_000L
        private const val REQUIRED_STABLE_ACTUAL_FRAMES = 3
        private const val ACTUAL_STABILITY_EV = .05
    }
}
