package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * Shape-preserving cubic fit in exposure-stop space:
 * log2(metered time) -> log2(corrected time / metered time).
 *
 * Fitting the correction in EV, instead of linear seconds or corrected time alone, keeps every
 * published node exact and prevents the compensation from dipping between increasing nodes.
 */
internal object ReciprocityCurveFitter {
    fun evaluate(points: List<ReciprocityPoint>, inputSeconds: Double): Double? {
        val samples = normalized(points)
        if (samples.size < 2 || inputSeconds <= 0.0) return null

        samples.firstOrNull { abs(it.meteredSeconds - inputSeconds) <= EPSILON }?.let {
            return it.correctedSeconds
        }

        val x = samples.map { log2(it.meteredSeconds) }
        val correctionStops = samples.map { log2(it.correctedSeconds / it.meteredSeconds) }
        val tangents = monotoneTangents(x, correctionStops)
        // The preceding no-compensation section is a constant 0 EV. Matching its zero slope
        // makes the transition C1-continuous instead of introducing a visible shoulder.
        tangents[0] = 0.0
        val target = log2(inputSeconds)
        val segment = when {
            target <= x.first() -> 0
            target >= x.last() -> x.lastIndex - 1
            else -> x.indexOfFirst { it > target } - 1
        }

        val fittedStops = if (target < x.first()) {
            correctionStops.first() + tangents.first() * (target - x.first())
        } else if (target > x.last()) {
            correctionStops.last() + tangents.last() * (target - x.last())
        } else {
            val width = x[segment + 1] - x[segment]
            val ratio = (target - x[segment]) / width
            val ratio2 = ratio * ratio
            val ratio3 = ratio2 * ratio
            val h00 = 2.0 * ratio3 - 3.0 * ratio2 + 1.0
            val h10 = ratio3 - 2.0 * ratio2 + ratio
            val h01 = -2.0 * ratio3 + 3.0 * ratio2
            val h11 = ratio3 - ratio2
            h00 * correctionStops[segment] + h10 * width * tangents[segment] +
                h01 * correctionStops[segment + 1] + h11 * width * tangents[segment + 1]
        }
        val fitted = inputSeconds * 2.0.pow(fittedStops)
        return fitted.takeIf(Double::isFinite)?.coerceAtLeast(inputSeconds)
    }

    private fun normalized(points: List<ReciprocityPoint>): List<ReciprocityPoint> = points
        .asSequence()
        .filter { point ->
            point.meteredSeconds.isFinite() && point.correctedSeconds.isFinite() &&
                point.meteredSeconds > 0.0 && point.correctedSeconds > 0.0
        }
        .sortedBy(ReciprocityPoint::meteredSeconds)
        .fold(mutableListOf()) { result, point ->
            if (result.lastOrNull()?.let { abs(it.meteredSeconds - point.meteredSeconds) <= EPSILON } == true) {
                result[result.lastIndex] = point
            } else {
                result += point
            }
            result
        }

    private fun monotoneTangents(x: List<Double>, y: List<Double>): DoubleArray {
        val count = x.size
        val widths = DoubleArray(count - 1) { index -> x[index + 1] - x[index] }
        val slopes = DoubleArray(count - 1) { index -> (y[index + 1] - y[index]) / widths[index] }
        if (count == 2) return doubleArrayOf(slopes[0], slopes[0])

        val tangents = DoubleArray(count)
        tangents[0] = endpointTangent(widths[0], widths[1], slopes[0], slopes[1])
        for (index in 1 until count - 1) {
            val before = slopes[index - 1]
            val after = slopes[index]
            tangents[index] = if (before == 0.0 || after == 0.0 || before.sign != after.sign) {
                0.0
            } else {
                val beforeWeight = 2.0 * widths[index] + widths[index - 1]
                val afterWeight = widths[index] + 2.0 * widths[index - 1]
                (beforeWeight + afterWeight) / (beforeWeight / before + afterWeight / after)
            }
        }
        tangents[count - 1] = endpointTangent(
            widths[count - 2],
            widths[count - 3],
            slopes[count - 2],
            slopes[count - 3],
        )
        return tangents
    }

    private fun endpointTangent(
        adjacentWidth: Double,
        nextWidth: Double,
        adjacentSlope: Double,
        nextSlope: Double,
    ): Double {
        var tangent = ((2.0 * adjacentWidth + nextWidth) * adjacentSlope - adjacentWidth * nextSlope) /
            (adjacentWidth + nextWidth)
        if (tangent.sign != adjacentSlope.sign) {
            tangent = 0.0
        } else if (adjacentSlope.sign != nextSlope.sign && abs(tangent) > abs(3.0 * adjacentSlope)) {
            tangent = 3.0 * adjacentSlope
        }
        return tangent
    }

    private val Double.sign: Int
        get() = when {
            this > 0.0 -> 1
            this < 0.0 -> -1
            else -> 0
        }

    private const val EPSILON = 1e-9
}
