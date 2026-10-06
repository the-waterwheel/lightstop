package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

data class FilmNegativeCurvePoint(val input: Float, val output: Float)

/** Immutable arbitrary-input point curve. Derivatives are prepared once, outside the LUT loop. */
class FilmNegativeCurve(points: List<FilmNegativeCurvePoint> = emptyList()) {
    private val anchors = normalize(points)
    private val inputs = DoubleArray(anchors.size) { anchors[it].input.toDouble() }
    private val outputs = DoubleArray(anchors.size) { anchors[it].output.toDouble() }
    private val tangents = derivatives()
    private val highlightSlope = (outputs.last() - outputs[outputs.lastIndex - 1]) /
        (inputs.last() - inputs[inputs.lastIndex - 1])

    // Callers cannot mutate a curve that is already shared with the renderer's settings snapshot.
    fun points(): List<FilmNegativeCurvePoint> = anchors.toList()

    fun add(input: Float, output: Float): FilmNegativeCurve {
        if (!input.isFinite() || !output.isFinite() || anchors.size >= MAX_POINTS ||
            input <= 0f || input >= 1f || anchors.any { abs(it.input - input) < MIN_GAP - 1e-6f }) return this
        return FilmNegativeCurve(anchors + FilmNegativeCurvePoint(input, output.coerceIn(0f, 1f)))
    }

    fun move(point: Int, input: Float, output: Float): FilmNegativeCurve {
        require(point in anchors.indices)
        val old = anchors[point]
        val x = when (point) {
            0 -> 0f
            anchors.lastIndex -> 1f
            else -> {
                val low = anchors[point - 1].input + MIN_GAP
                val high = anchors[point + 1].input - MIN_GAP
                if (low > high) old.input else (input.takeIf { it.isFinite() } ?: old.input).coerceIn(low, high)
            }
        }
        val y = (output.takeIf { it.isFinite() } ?: old.output).coerceIn(0f, 1f)
        return FilmNegativeCurve(anchors.mapIndexed { i, value -> if (i == point) FilmNegativeCurvePoint(x, y) else value })
    }

    /** Reset this input's output to the neutral diagonal; other control points remain intact. */
    fun reset(point: Int): FilmNegativeCurve {
        require(point in anchors.indices)
        return move(point, anchors[point].input, anchors[point].input)
    }

    fun remove(point: Int): FilmNegativeCurve {
        require(point in anchors.indices)
        if (point == 0 || point == anchors.lastIndex) return this
        return FilmNegativeCurve(anchors.filterIndexed { index, _ -> index != point })
    }

    fun evaluate(input: Double): Double {
        val x = if (input.isNaN()) 0.0 else input.coerceIn(0.0, 1024.0)
        if (x <= 0.0) return outputs.first()
        // Continue the final secant, retaining headroom even if a cubic end tangent is zero.
        if (x >= 1.0) return (outputs.last() + highlightSlope * (x - 1.0)).coerceAtLeast(0.0)
        var segment = 0
        while (segment < inputs.size - 2 && x > inputs[segment + 1]) segment++
        val span = inputs[segment + 1] - inputs[segment]
        val t = (x - inputs[segment]) / span
        val t2 = t * t; val t3 = t2 * t
        val value = (2 * t3 - 3 * t2 + 1) * outputs[segment] +
            (t3 - 2 * t2 + t) * span * tangents[segment] +
            (-2 * t3 + 3 * t2) * outputs[segment + 1] +
            (t3 - t2) * span * tangents[segment + 1]
        return value.coerceIn(min(outputs[segment], outputs[segment + 1]), max(outputs[segment], outputs[segment + 1]))
    }

    /** Shape-preserving cubic Hermite interpolation; peaks/valleys do not overshoot the points. */
    private fun derivatives(): DoubleArray {
        val spans = DoubleArray(inputs.size - 1) { inputs[it + 1] - inputs[it] }
        val slopes = DoubleArray(spans.size) { (outputs[it + 1] - outputs[it]) / spans[it] }
        if (inputs.size == 2) return doubleArrayOf(slopes[0], slopes[0])
        val result = DoubleArray(inputs.size)
        for (i in 1 until inputs.lastIndex) {
            val left = slopes[i - 1]; val right = slopes[i]
            if (left == 0.0 || right == 0.0 || sign(left) != sign(right)) continue
            val w1 = 2 * spans[i] + spans[i - 1]; val w2 = spans[i] + 2 * spans[i - 1]
            result[i] = (w1 + w2) / (w1 / left + w2 / right)
        }
        fun end(h0: Double, h1: Double, d0: Double, d1: Double): Double {
            val slope = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
            return when {
                sign(slope) != sign(d0) -> 0.0
                sign(d0) != sign(d1) && abs(slope) > abs(3 * d0) -> 3 * d0
                else -> slope
            }
        }
        result[0] = end(spans[0], spans[1], slopes[0], slopes[1])
        result[result.lastIndex] = end(spans.last(), spans[spans.lastIndex - 1], slopes.last(), slopes[slopes.lastIndex - 1])
        return result
    }

    override fun equals(other: Any?) = other is FilmNegativeCurve && anchors == other.anchors
    override fun hashCode() = anchors.hashCode()

    companion object {
        const val MAX_POINTS = 32
        const val MIN_GAP = .01f
        private fun normalize(points: List<FilmNegativeCurvePoint>): List<FilmNegativeCurvePoint> {
            val ordered = points.filter { it.input.isFinite() }.map {
                val x = it.input.coerceIn(0f, 1f)
                FilmNegativeCurvePoint(x, (it.output.takeIf { y -> y.isFinite() } ?: x).coerceIn(0f, 1f))
            }.sortedBy { it.input }
            val result = arrayListOf(FilmNegativeCurvePoint(0f, ordered.lastOrNull { it.input == 0f }?.output ?: 0f))
            for (point in ordered) {
                if (point.input < MIN_GAP || point.input > 1f - MIN_GAP || result.size >= MAX_POINTS - 1) continue
                if (point.input - result.last().input >= MIN_GAP - 1e-6f) result.add(point)
            }
            result.add(FilmNegativeCurvePoint(1f, ordered.lastOrNull { it.input == 1f }?.output ?: 1f))
            return result.toList()
        }
    }
}
