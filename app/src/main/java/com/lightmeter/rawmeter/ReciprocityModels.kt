package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import java.util.Locale

internal enum class ReciprocityMethodType { NONE, RANGE, BOUNDED_UNCHANGED, TABLE, FIXED_EV, POWER }

internal data class ReciprocityPoint(
    val meteredSeconds: Double,
    val correctedSeconds: Double,
    val filter: String?,
)

internal data class ReciprocityMethod(
    val id: String,
    val type: ReciprocityMethodType,
    val parameter: Double?,
    val noCompensationSeconds: Double,
    val officialMaximumSeconds: Double?,
    val evidence: String,
    val longExposureFilter: String,
    val filterRule: String,
    val warning: String,
    val sourceUrl: String,
    val points: List<ReciprocityPoint>,
) {
    val hasCalculationData: Boolean
        get() = when (type) {
            ReciprocityMethodType.POWER,
            ReciprocityMethodType.FIXED_EV,
            -> parameter?.isFinite() == true
            ReciprocityMethodType.TABLE -> points.any { point ->
                point.meteredSeconds.isFinite() && point.correctedSeconds.isFinite() &&
                    point.meteredSeconds > 0.0 && point.correctedSeconds > 0.0
            }
            ReciprocityMethodType.BOUNDED_UNCHANGED ->
                officialMaximumSeconds?.let { it.isFinite() && it > 0.0 } == true
            ReciprocityMethodType.NONE,
            ReciprocityMethodType.RANGE,
            -> false
        }
}

internal enum class ReciprocityStatus { UNCHANGED, CORRECTED, ESTIMATED, UNAVAILABLE, OUT_OF_RANGE }

internal data class ReciprocityResult(
    val inputSeconds: Double,
    val correctedSeconds: Double?,
    val filter: String?,
    val status: ReciprocityStatus,
) {
    val needsCorrection: Boolean
        get() = (status == ReciprocityStatus.CORRECTED || status == ReciprocityStatus.ESTIMATED) &&
            correctedSeconds != null &&
            correctedSeconds > inputSeconds * (1.0 + 1e-6)

    val estimated: Boolean get() = status == ReciprocityStatus.ESTIMATED
}

/** Uses exact manufacturer data in-range and explicitly marks curve estimates outside it. */
internal object ReciprocityMath {
    fun calculate(method: ReciprocityMethod?, inputSeconds: Double): ReciprocityResult {
        val input = inputSeconds.coerceAtLeast(MIN_SECONDS)
        if (method == null) return unavailable(input)
        if (input <= method.noCompensationSeconds + EPSILON) {
            return ReciprocityResult(input, input, null, ReciprocityStatus.UNCHANGED)
        }
        val estimated = method.officialMaximumSeconds?.let { input > it + EPSILON } == true
        return when (method.type) {
            ReciprocityMethodType.NONE -> unavailable(input)
            ReciprocityMethodType.RANGE -> ReciprocityResult(
                input,
                null,
                null,
                ReciprocityStatus.OUT_OF_RANGE,
            )
            ReciprocityMethodType.BOUNDED_UNCHANGED -> {
                val maximum = method.officialMaximumSeconds ?: return unavailable(input)
                if (input <= maximum + EPSILON) {
                    ReciprocityResult(input, input, null, ReciprocityStatus.UNCHANGED)
                } else {
                    ReciprocityResult(input, null, null, ReciprocityStatus.OUT_OF_RANGE)
                }
            }
            ReciprocityMethodType.FIXED_EV -> fixedEv(method, input, estimated)
            ReciprocityMethodType.POWER -> power(method, input, estimated)
            ReciprocityMethodType.TABLE -> table(method, input, estimated)
        }
    }

    private fun fixedEv(method: ReciprocityMethod, input: Double, estimated: Boolean): ReciprocityResult {
        val ev = method.parameter ?: return unavailable(input)
        return corrected(input, input * 2.0.pow(ev), filterFor(method, input), estimated)
    }

    private fun power(method: ReciprocityMethod, input: Double, estimated: Boolean): ReciprocityResult {
        val exponent = method.parameter ?: return unavailable(input)
        // Reciprocity correction must never recommend less exposure than the meter reading.
        // Keep this invariant here as a final guard against a bad threshold or imported curve.
        return corrected(input, input.pow(exponent).coerceAtLeast(input), filterFor(method, input), estimated)
    }

    private fun table(method: ReciprocityMethod, input: Double, outsideOfficialRange: Boolean): ReciprocityResult {
        val nodes = method.points.sortedBy(ReciprocityPoint::meteredSeconds)
        if (nodes.isEmpty()) return unavailable(input)
        nodes.firstOrNull { abs(it.meteredSeconds - input) <= EPSILON }?.let { node ->
            return corrected(input, node.correctedSeconds, normalizedFilter(node.filter), outsideOfficialRange)
        }
        val boundary = ReciprocityPoint(
            meteredSeconds = method.noCompensationSeconds,
            correctedSeconds = method.noCompensationSeconds,
            filter = null,
        )
        val output = ReciprocityCurveFitter.evaluate(listOf(boundary) + nodes, input)
            ?: return unavailable(input)
        val estimated = outsideOfficialRange || input > nodes.last().meteredSeconds + EPSILON
        return corrected(input, output, filterFor(method, input), estimated)
    }

    private fun corrected(input: Double, output: Double, filter: String?, estimated: Boolean): ReciprocityResult {
        if (!output.isFinite() || output <= 0.0) return unavailable(input)
        val status = if (estimated) {
            ReciprocityStatus.ESTIMATED
        } else if (output > input * (1.0 + EPSILON)) {
            ReciprocityStatus.CORRECTED
        } else {
            ReciprocityStatus.UNCHANGED
        }
        return ReciprocityResult(input, output, filter, status)
    }

    /** Color filters are discrete recommendations, so use the closest published exposure node. */
    private fun filterFor(method: ReciprocityMethod, input: Double): String? = method.points
        .minByOrNull { abs(ln(input / it.meteredSeconds)) }
        ?.filter
        .let(::normalizedFilter)

    private fun normalizedFilter(value: String?): String? = value
        ?.trim()
        ?.takeIf { FILTER_CODE.containsMatchIn(it) }

    private fun unavailable(input: Double) = ReciprocityResult(
        input,
        null,
        null,
        ReciprocityStatus.UNAVAILABLE,
    )

    private val FILTER_CODE = Regex(
        pattern = "(?:CC\\d+(?:\\.\\d+)?[RGBMYC]|\\d+(?:\\.\\d+)?\\s*[RGBMYC])",
        option = RegexOption.IGNORE_CASE,
    )
    private const val EPSILON = 1e-9
    private const val MIN_SECONDS = 1.0 / 8000.0
}

internal object ReciprocityTimeFormatter {
    /** Total minutes and remaining seconds; the result intentionally contains no unit text. */
    fun minutesAndSeconds(seconds: Double): String {
        val rounded = seconds.coerceAtLeast(0.0).roundToInt()
        return String.format(Locale.US, "%02d:%02d", rounded / 60, rounded % 60)
    }

    fun resultReadout(seconds: Double): String = ReciprocityTimeReadout.from(seconds).text

    fun scaleLabel(seconds: Double): String = when {
        seconds >= 3600.0 -> {
            val totalMinutes = (seconds / 60.0).roundToInt()
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (minutes == 0) "${hours}h" else "${hours}h${minutes}"
        }
        seconds >= 60.0 -> {
            val minutes = floor(seconds / 60.0).toInt()
            val remaining = (seconds - minutes * 60.0).roundToInt()
            if (remaining == 0) "${minutes}m" else "${minutes}m${remaining}"
        }
        else -> ExposureMath.formatShutter(seconds)
    }
}

internal data class ReciprocityShutterTick(
    val coordinate: Double,
    val nominalSeconds: Double,
    val major: Boolean,
)

/** Extended, stop-aligned shutter scale for the calculator; normal metering limits stay unchanged. */
internal object ReciprocityShutterScale {
    const val MAX_SECONDS = 24.0 * 60.0 * 60.0

    fun ticks(
        step: ExposureStep,
        maximumSeconds: Double = MAX_SECONDS,
    ): List<ReciprocityShutterTick> {
        val cappedMaximum = maximumSeconds.coerceIn(MIN_SECONDS, MAX_SAFE_SECONDS)
        val base = ExposureMath.shutterTicks(step).mapIndexed { index, tick ->
            ReciprocityShutterTick(
                coordinate = tick.coordinate,
                nominalSeconds = tick.nominalValue,
                major = index % step.denominator == 0,
            )
        }.filter { it.nominalSeconds <= cappedMaximum + 1e-9 }
        val firstLongCoordinate = ExposureMath.maxMarkedShutterLogSeconds + 1.0 / step.denominator
        val maximumCoordinate = ExposureMath.log2(cappedMaximum)
        val extended = buildList {
            var coordinate = firstLongCoordinate
            while (coordinate <= maximumCoordinate + 1e-9) {
                val seconds = 2.0.pow(coordinate)
                add(
                    ReciprocityShutterTick(
                        coordinate = coordinate,
                        nominalSeconds = roundedLongSeconds(seconds),
                        major = abs(coordinate - coordinate.roundToInt()) < 1e-6,
                    ),
                )
                coordinate += 1.0 / step.denominator
            }
            if (none { abs(it.nominalSeconds - cappedMaximum) < 1e-6 }) {
                add(
                    ReciprocityShutterTick(
                        coordinate = maximumCoordinate,
                        nominalSeconds = cappedMaximum,
                        major = true,
                    ),
                )
            }
        }
        return (base + extended).ifEmpty {
            listOf(
                ReciprocityShutterTick(
                    coordinate = ExposureMath.log2(cappedMaximum),
                    nominalSeconds = cappedMaximum,
                    major = true,
                ),
            )
        }.sortedBy(ReciprocityShutterTick::coordinate)
    }

    fun nearestCoordinate(
        target: Double,
        step: ExposureStep,
        maximumSeconds: Double = MAX_SECONDS,
    ): Double = ticks(step, maximumSeconds)
        .minByOrNull { abs(it.coordinate - target) }
        ?.coordinate
        ?: target

    fun valueForCoordinate(
        coordinate: Double,
        step: ExposureStep,
        maximumSeconds: Double = MAX_SECONDS,
    ): Double {
        val all = ticks(step, maximumSeconds)
        all.firstOrNull { abs(it.coordinate - coordinate) < 1e-4 }?.let {
            return it.nominalSeconds
        }
        return 2.0.pow(coordinate.coerceIn(all.first().coordinate, all.last().coordinate))
    }

    fun coordinateForSeconds(
        seconds: Double,
        step: ExposureStep,
        maximumSeconds: Double = MAX_SECONDS,
    ): Double {
        val coordinate = ExposureMath.log2(seconds.coerceIn(MIN_SECONDS, maximumSeconds))
        return nearestCoordinate(coordinate, step, maximumSeconds)
    }

    private fun roundedLongSeconds(seconds: Double): Double = when {
        seconds < 60.0 -> seconds.roundToInt().toDouble()
        seconds < 600.0 -> (seconds / 5.0).roundToInt() * 5.0
        seconds < 3600.0 -> (seconds / 30.0).roundToInt() * 30.0
        else -> (seconds / 60.0).roundToInt() * 60.0
    }

    private const val MIN_SECONDS = 1.0 / 8000.0
    private const val MAX_SAFE_SECONDS = 365.0 * 24.0 * 60.0 * 60.0
}
