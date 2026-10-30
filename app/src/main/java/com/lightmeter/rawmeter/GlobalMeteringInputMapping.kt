package com.lightmeter.rawmeter

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Model coordinates describe the current image exposure, not scene EV100. */
internal data class GlobalMeteringInput(
    val features: FloatArray,
    val source: MeteringSource,
    val sampleCount: Int = GlobalMeteringInputMapping.SAMPLES,
    val domainVersion: String = GlobalMeteringInputMapping.DOMAIN_VERSION,
    val featureVersion: String = GlobalMeteringInputMapping.FEATURE_VERSION,
)

/** Global-only adapters. Existing local/Zone/calibration paths never call these conversions. */
internal object GlobalMeteringInputMapping {
    const val DOMAIN_VERSION = "scene-linear-sparse-pairs-v2"
    const val FEATURE_VERSION = "relative-image-ev-grid-v1"
    const val MAPPING_VERSION = "global-input-mapping-v1"
    const val WIDTH = 96
    const val HEIGHT = 64
    const val SAMPLES = WIDTH * HEIGHT
    const val FEATURES = 776
    private const val FLOOR = 1e-6
    private val LOG_2 = ln(2.0)

    /** Planar R/G-even/G-odd/B; black/white normalization is performed by the native sampler. */
    fun fromRaw(
        mode: MeteringMode,
        rggb: FloatArray,
        gains: DoubleArray?,
        columnMajorMatrix: DoubleArray?,
        correctionEv: Double,
    ): GlobalMeteringInput? {
        if (mode != MeteringMode.AVERAGE) return null
        if (rggb.size != 4 * SAMPLES || rggb.any { !it.isFinite() } ||
            gains == null || gains.size != 4 || gains.any { !it.isFinite() || it <= 0 } ||
            columnMajorMatrix == null || columnMajorMatrix.size != 9 ||
            columnMajorMatrix.any { !it.isFinite() } || !correctionEv.isFinite()
        ) return null
        val rgb = FloatArray(3 * SAMPLES)
        val scale = 2.0.pow(correctionEv)
        if (!scale.isFinite() || scale <= 0) return null
        val m = columnMajorMatrix
        for (i in 0 until SAMPLES) {
            val red = rggb[i] * gains[0]
            val green = (rggb[SAMPLES + i] * gains[1] + rggb[2 * SAMPLES + i] * gains[2]) * .5
            val blue = rggb[3 * SAMPLES + i] * gains[3]
            for (row in 0..2) {
                val value = (m[row] * red + m[3 + row] * green + m[6 + row] * blue) * scale
                if (!value.isFinite()) return null
                rgb[row * SAMPLES + i] = value.coerceIn(0.0, 1.0).toFloat()
            }
        }
        return fromLinear(mode, rgb, MeteringSource.RAW)
    }

    /** RGB is already transfer-decoded; apply the existing source response exactly once. */
    fun fromProcessedLinear(
        mode: MeteringMode,
        linearRgb: FloatArray,
        source: MeteringSource,
        correction: (Double) -> Double,
    ): GlobalMeteringInput? {
        if (mode != MeteringMode.AVERAGE || source == MeteringSource.RAW ||
            linearRgb.size != 3 * SAMPLES || linearRgb.any { !it.isFinite() }
        ) return null
        val rgb = FloatArray(linearRgb.size)
        for (i in 0 until SAMPLES) {
            val y = .2126 * linearRgb[i] + .7152 * linearRgb[SAMPLES + i] + .0722 * linearRgb[2 * SAMPLES + i]
            val ev = correction(y)
            val scale = 2.0.pow(ev)
            if (!ev.isFinite() || !scale.isFinite() || scale <= 0) return null
            for (channel in 0..2) {
                val index = channel * SAMPLES + i
                rgb[index] = (linearRgb[index] * scale).coerceIn(0.0, 1.0).toFloat()
            }
        }
        return fromLinear(mode, rgb, source)
    }

    /** Fixed 12x8 cells, 8x8 points/cell, then 8 global values. No image-wise normalization. */
    fun fromLinear(mode: MeteringMode, rgb: FloatArray, source: MeteringSource): GlobalMeteringInput? {
        if (mode != MeteringMode.AVERAGE || rgb.size != 3 * SAMPLES ||
            rgb.any { !it.isFinite() || it < 0f || it > 1f }
        ) return null
        val ev = DoubleArray(SAMPLES)
        val clipped = BooleanArray(SAMPLES)
        val dark = BooleanArray(SAMPLES)
        val channelSums = DoubleArray(3)
        var clippedCount = 0
        var darkCount = 0
        for (i in 0 until SAMPLES) {
            val r = rgb[i].toDouble()
            val g = rgb[SAMPLES + i].toDouble()
            val b = rgb[2 * SAMPLES + i].toDouble()
            val y = .2126 * r + .7152 * g + .0722 * b
            ev[i] = log2(maxOf(y, FLOOR) / .18)
            clipped[i] = maxOf(r, g, b) >= .98
            dark[i] = y <= FLOOR
            if (clipped[i]) clippedCount++
            if (dark[i]) darkCount++
            channelSums[0] += r; channelSums[1] += g; channelSums[2] += b
        }
        val ordered = ev.sortedArray()
        val base = EvaluativeMeteringAlgorithm.intervalMean(ordered, .05, .95)
        val high = EvaluativeMeteringAlgorithm.intervalMean(ordered, .98, .995)
        val features = FloatArray(FEATURES)
        val cell = DoubleArray(64)
        for (gy in 0 until 8) for (gx in 0 until 12) {
            var sum = 0.0
            var nc = 0
            var nd = 0
            val colors = DoubleArray(3)
            for (cy in 0 until 8) for (cx in 0 until 8) {
                val i = (gy * 8 + cy) * WIDTH + gx * 8 + cx
                val e = ev[i]
                cell[cy * 8 + cx] = e; sum += e
                if (clipped[i]) nc++
                if (dark[i]) nd++
                for (c in 0..2) colors[c] += rgb[c * SAMPLES + i]
            }
            val mean = sum / 64
            val std = sqrt(cell.sumOf { (it - mean) * (it - mean) } / 64)
            cell.sort()
            val at = (gy * 12 + gx) * 8
            features[at] = (mean - base).toFloat()
            features[at + 1] = std.toFloat()
            features[at + 2] = (quantile(cell, .1) - base).toFloat()
            features[at + 3] = (quantile(cell, .9) - base).toFloat()
            features[at + 4] = nc / 64f
            features[at + 5] = nd / 64f
            features[at + 6] = ratio(colors[0] / 64, colors[1] / 64).toFloat()
            features[at + 7] = ratio(colors[2] / 64, colors[1] / 64).toFloat()
        }
        features[768] = base.toFloat()
        features[769] = (high - base).toFloat()
        features[770] = (quantile(ordered, .9) - quantile(ordered, .1)).toFloat()
        features[771] = clippedCount.toFloat() / SAMPLES
        features[772] = darkCount.toFloat() / SAMPLES
        features[773] = 1f
        features[774] = ratio(channelSums[0] / SAMPLES, channelSums[1] / SAMPLES).toFloat()
        features[775] = ratio(channelSums[2] / SAMPLES, channelSums[1] / SAMPLES).toFloat()
        return GlobalMeteringInput(features, source)
    }

    /** Grid coordinates are display-oriented; bounds are already cropped/zoomed exactly once. */
    inline fun <T> withSamplePosition(
        column: Int, row: Int, left: Int, top: Int, right: Int, bottom: Int,
        rotationDegrees: Int = 0, mirrored: Boolean = false,
        consume: (x: Int, y: Int) -> T,
    ): T {
        val u = (column + .5) / WIDTH
        val v = (row + .5) / HEIGHT
        val x = if (mirrored) 1 - u else u
        val sx: Double
        val sy: Double
        when ((rotationDegrees % 360 + 360) % 360) {
            90 -> { sx = v; sy = 1 - x }
            180 -> { sx = 1 - x; sy = 1 - v }
            270 -> { sx = 1 - v; sy = x }
            else -> { sx = x; sy = v }
        }
        return consume((left + sx * (right - left)).toInt().coerceIn(left, right - 1),
            (top + sy * (bottom - top)).toInt().coerceIn(top, bottom - 1))
    }

    private fun quantile(sorted: DoubleArray, q: Double): Double {
        val position = q * (sorted.size - 1)
        val low = floor(position).toInt()
        val high = minOf(low + 1, sorted.lastIndex)
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }
    private fun ratio(a: Double, b: Double) = log2(maxOf(a, FLOOR) / maxOf(b, FLOOR))
    private fun log2(x: Double) = ln(x) / LOG_2
}
