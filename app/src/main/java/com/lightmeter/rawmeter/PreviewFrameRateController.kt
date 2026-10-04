package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min

/**
 * Mutable viewfinder frame-rate state kept outside [CameraController].
 *
 * The user preference is an upper bound, not a promise of a constant frame rate. In low light we
 * prefer an advertised variable AE range so the HAL can lengthen exposure instead of only raising
 * ISO. Hysteresis prevents a lamp, screen, or brief camera movement from repeatedly changing the
 * repeating request.
 */
internal class PreviewFrameRateController(
    initialMode: PreviewFrameRateMode = PreviewFrameRateMode.LOW,
    private val lowLightMonitor: PreviewLowLightMonitor = PreviewLowLightMonitor(),
) {
    var mode: PreviewFrameRateMode = initialMode
        private set

    var requestCeiling: Int? = initialMode.requestedCeiling
        private set

    val isLowLight: Boolean
        get() = lowLightMonitor.isLowLight

    fun setMode(next: PreviewFrameRateMode): Boolean {
        if (mode == next) return false
        mode = next
        requestCeiling = next.requestedCeiling
        return true
    }

    fun resetForCamera() {
        requestCeiling = mode.requestedCeiling
        lowLightMonitor.reset()
    }

    /** Returns true when a new request should be submitted. */
    fun observeExposure(
        exposureTimeNs: Long?,
        sensitivity: Int?,
        aperture: Float?,
        timestampNs: Long?,
        aeRequestsFlash: Boolean,
    ): Boolean {
        val timestamp = timestampNs?.takeIf { it > 0L } ?: return false
        val ev100 = PreviewExposureValue.ev100(exposureTimeNs, sensitivity, aperture)
            ?: return false
        return lowLightMonitor.observe(ev100, timestamp, aeRequestsFlash)
    }

    /** Keeps health/request failures independent from the selected stream workflow. */
    fun useNextCompatibilityCeiling(): Boolean {
        val current = requestCeiling ?: return false
        requestCeiling = CameraFrameRatePolicy.nextFallbackCeiling(current)
        return true
    }

    fun limitToStandardRate(): Boolean {
        val current = requestCeiling ?: return false
        if (current <= PreviewFrameRateMode.LOW.requestedCeiling) return false
        requestCeiling = PreviewFrameRateMode.LOW.requestedCeiling
        return true
    }
}

internal object PreviewExposureValue {
    /** Camera exposure value at ISO 100; under neutral AE this tracks scene brightness. */
    fun ev100(exposureTimeNs: Long?, sensitivity: Int?, aperture: Float?): Double? {
        val exposure = exposureTimeNs?.takeIf { it > 0L } ?: return null
        val iso = sensitivity?.takeIf { it > 0 } ?: return null
        val fNumber = aperture?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val seconds = exposure / 1_000_000_000.0
        return log2(fNumber * fNumber / seconds * 100.0 / iso)
            .takeIf(Double::isFinite)
    }
}

/** Time-based low-light hysteresis, independent from preview frame rate. */
internal class PreviewLowLightMonitor(
    private val enterThresholdEv100: Double = 5.0,
    private val exitThresholdEv100: Double = 6.25,
    private val enterDurationNs: Long = 750_000_000L,
    private val exitDurationNs: Long = 2_000_000_000L,
) {
    var isLowLight: Boolean = false
        private set

    private var candidateSinceNs: Long? = null
    private var lastTimestampNs: Long? = null

    fun observe(ev100: Double, timestampNs: Long, aeRequestsFlash: Boolean): Boolean {
        if (!ev100.isFinite() || timestampNs <= 0L) return false
        val previousTimestamp = lastTimestampNs
        lastTimestampNs = timestampNs
        if (previousTimestamp != null && timestampNs <= previousTimestamp) {
            candidateSinceNs = null
            return false
        }

        val wantsTransition = if (isLowLight) {
            !aeRequestsFlash && ev100 >= exitThresholdEv100
        } else {
            aeRequestsFlash || ev100 <= enterThresholdEv100
        }
        if (!wantsTransition) {
            candidateSinceNs = null
            return false
        }

        val since = candidateSinceNs ?: timestampNs.also { candidateSinceNs = it }
        val requiredDuration = if (isLowLight) exitDurationNs else enterDurationNs
        if (timestampNs - since < requiredDuration) return false
        isLowLight = !isLowLight
        candidateSinceNs = null
        return true
    }

    fun reset() {
        isLowLight = false
        candidateSinceNs = null
        lastTimestampNs = null
    }
}

/** Pure advertised-range policy used by both Camera2 selection and JVM tests. */
internal object CameraFrameRatePolicy {
    fun selectRangeBounds(
        ranges: List<Pair<Int, Int>>,
        requestedCeiling: Int,
        lowLight: Boolean,
    ): Pair<Int, Int>? {
        if (ranges.isEmpty()) return null
        val ceiling = requestedCeiling.coerceAtLeast(1)
        val withinUserLimit = ranges.filter { (lower, upper) ->
            lower > 0 && upper >= lower && lower <= ceiling && upper <= ceiling
        }
        if (withinUserLimit.isEmpty()) return null
        if (!lowLight) {
            return withinUserLimit.maxWithOrNull(
                compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first },
            )
        }

        // Prefer a range capped at 30 fps. If a 60-fps preference is active and the HAL only
        // exposes a wider variable range, retain that advertised range rather than inventing one.
        val lowRate = withinUserLimit.filter { it.second <= LOW_LIGHT_MAX_FPS }
            .ifEmpty { withinUserLimit.filter { it.first < it.second } }
        if (lowRate.isEmpty()) return withinUserLimit.minByOrNull { it.second }
        val variable = lowRate.filter { it.first < it.second }
        if (variable.isEmpty()) {
            return lowRate.minWithOrNull(
                compareBy<Pair<Int, Int>> { abs(it.second - LOW_LIGHT_TARGET_FPS) }
                    .thenBy { it.second },
            )
        }
        val preferredUpper = variable.maxOf { it.second }
        return variable
            .filter { it.second == preferredUpper }
            .minWithOrNull(
                compareBy<Pair<Int, Int>> { abs(it.first - LOW_LIGHT_TARGET_FPS) }
                    .thenByDescending { it.first },
            )
    }

    fun effectiveCeiling(
        requestedCeiling: Int,
        streamCeiling: Int,
    ): Int = min(MAX_REGULAR_PREVIEW_FPS, min(requestedCeiling, streamCeiling))

    fun nextFallbackCeiling(currentCeiling: Int): Int? = when {
        currentCeiling > LOW_PREVIEW_FPS_CEILING -> LOW_PREVIEW_FPS_CEILING
        currentCeiling > CONSERVATIVE_PREVIEW_FPS_CEILING ->
            CONSERVATIVE_PREVIEW_FPS_CEILING
        else -> null
    }

    private const val MAX_REGULAR_PREVIEW_FPS = 60
    private const val LOW_PREVIEW_FPS_CEILING = 30
    private const val CONSERVATIVE_PREVIEW_FPS_CEILING = 24
    private const val LOW_LIGHT_MAX_FPS = 30
    private const val LOW_LIGHT_TARGET_FPS = 15
}
