package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import java.util.Locale

internal enum class ReciprocityMethodType { NONE, RANGE, TABLE, FIXED_EV, POWER }

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
)

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

/** Uses exact manufacturer data in-range and explicitly marked curve estimates up to 24 hours. */
internal object ReciprocityMath {
    fun calculate(method: ReciprocityMethod?, inputSeconds: Double): ReciprocityResult {
        val input = inputSeconds.coerceAtLeast(MIN_SECONDS)
        if (method == null) return unavailable(input)
        if (input > ReciprocityShutterScale.MAX_SECONDS + EPSILON) {
            return ReciprocityResult(input, null, null, ReciprocityStatus.OUT_OF_RANGE)
        }
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
        return corrected(input, input.pow(exponent), filterFor(method, input), estimated)
    }

    private fun table(method: ReciprocityMethod, input: Double, outsideOfficialRange: Boolean): ReciprocityResult {
        val nodes = method.points.sortedBy(ReciprocityPoint::meteredSeconds)
        if (nodes.isEmpty()) return unavailable(input)
        nodes.firstOrNull { abs(it.meteredSeconds - input) <= EPSILON }?.let { node ->
            return corrected(input, node.correctedSeconds, normalizedFilter(node.filter), outsideOfficialRange)
        }
        val upperIndex = nodes.indexOfFirst { it.meteredSeconds > input }
        val boundary = ReciprocityPoint(
            meteredSeconds = method.noCompensationSeconds,
            correctedSeconds = method.noCompensationSeconds,
            filter = null,
        )
        val upper: ReciprocityPoint
        val lower: ReciprocityPoint
        val estimated: Boolean
        if (upperIndex < 0) {
            upper = nodes.last()
            lower = nodes.getOrNull(nodes.lastIndex - 1) ?: boundary
            estimated = true
        } else {
            upper = nodes[upperIndex]
            lower = if (upperIndex == 0) boundary else nodes[upperIndex - 1]
            estimated = outsideOfficialRange
        }
        if (lower.meteredSeconds >= upper.meteredSeconds) {
            return unavailable(input)
        }
        if (upperIndex == 0 && lower.meteredSeconds <= 0.0) {
            return unavailable(input)
        }
        if (lower.meteredSeconds <= 0.0 || lower.correctedSeconds <= 0.0) return unavailable(input)
        val exponent = ln(upper.correctedSeconds / lower.correctedSeconds) /
            ln(upper.meteredSeconds / lower.meteredSeconds)
        val output = lower.correctedSeconds * (input / lower.meteredSeconds).pow(exponent)
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
        ?.takeIf { it.isNotEmpty() && it !in NO_FILTER_VALUES }

    private fun unavailable(input: Double) = ReciprocityResult(
        input,
        null,
        null,
        ReciprocityStatus.UNAVAILABLE,
    )

    private val NO_FILTER_VALUES = setOf("无", "无滤镜", "不适用", "—", "-")
    private const val EPSILON = 1e-9
    private const val MIN_SECONDS = 1.0 / 8000.0
}

internal object ReciprocityTimeFormatter {
    /** Total minutes and remaining seconds; the result intentionally contains no unit text. */
    fun minutesAndSeconds(seconds: Double): String {
        val rounded = seconds.coerceAtLeast(0.0).roundToInt()
        return String.format(Locale.US, "%02d:%02d", rounded / 60, rounded % 60)
    }

    fun resultReadout(seconds: Double): String = if (seconds > 0.0 && seconds < 1.0) {
        val inverse = 1.0 / seconds.coerceAtLeast(1e-9)
        val denominator = if (inverse < 10.0 && abs(inverse - inverse.roundToInt()) > 0.04) {
            String.format(Locale.US, "%.1f", inverse)
        } else {
            inverse.roundToInt().toString()
        }
        "1/$denominator"
    } else {
        minutesAndSeconds(seconds)
    }

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

    fun ticks(step: ExposureStep): List<ReciprocityShutterTick> {
        val base = ExposureMath.shutterTicks(step).mapIndexed { index, tick ->
            ReciprocityShutterTick(
                coordinate = tick.coordinate,
                nominalSeconds = tick.nominalValue,
                major = index % step.denominator == 0,
            )
        }
        val firstLongCoordinate = ExposureMath.maxMarkedShutterLogSeconds + 1.0 / step.denominator
        val maximumCoordinate = ceil(ExposureMath.log2(MAX_SECONDS) * step.denominator) / step.denominator
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
        }
        return (base + extended).sortedBy(ReciprocityShutterTick::coordinate)
    }

    fun nearestCoordinate(target: Double, step: ExposureStep): Double = ticks(step)
        .minByOrNull { abs(it.coordinate - target) }
        ?.coordinate
        ?: target

    fun valueForCoordinate(coordinate: Double, step: ExposureStep): Double {
        val all = ticks(step)
        all.firstOrNull { abs(it.coordinate - coordinate) < 1e-4 }?.let {
            return it.nominalSeconds
        }
        return 2.0.pow(coordinate.coerceIn(all.first().coordinate, all.last().coordinate))
    }

    fun coordinateForSeconds(seconds: Double, step: ExposureStep): Double {
        val coordinate = ExposureMath.log2(seconds.coerceIn(1.0 / 8000.0, MAX_SECONDS))
        return nearestCoordinate(coordinate, step)
    }

    private fun roundedLongSeconds(seconds: Double): Double = when {
        seconds < 60.0 -> seconds.roundToInt().toDouble()
        seconds < 600.0 -> (seconds / 5.0).roundToInt() * 5.0
        seconds < 3600.0 -> (seconds / 30.0).roundToInt() * 30.0
        else -> (seconds / 60.0).roundToInt() * 60.0
    }
}
