package com.lightmeter.rawmeter

import kotlin.math.log10
import kotlin.math.max

internal data class FilmNegativeRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class FilmNegativeFrozenFrame(val width: Int, val height: Int, val pixels: IntArray,
    val evidence: FilmNegativeFrameEvidence)
internal data class FilmBaseCandidate(val region: FilmNegativeRegion, val rgb: FloatArray, val confidence: Float)
data class FilmNegativeDensityWindow(val lowRed: Float, val lowGreen: Float, val lowBlue: Float,
    val highRed: Float, val highGreen: Float, val highBlue: Float) {
    fun low(c: Int) = when(c) { 0 -> lowRed; 1 -> lowGreen; else -> lowBlue }
    fun high(c: Int) = when(c) { 0 -> highRed; 1 -> highGreen; else -> highBlue }
}

/** Independent preview estimators: robust regional base and co-located density endpoints. */
internal object FilmNegativeFrameAnalysis {
    private fun channels(pixel: Int) = intArrayOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255)
    private fun samples(frame: FilmNegativeFrozenFrame, r: FilmNegativeRegion, ellipse: Boolean): List<FloatArray> {
        val result = ArrayList<FloatArray>()
        val x0 = (r.left.coerceIn(0f, 1f) * frame.width).toInt()
        val y0 = (r.top.coerceIn(0f, 1f) * frame.height).toInt()
        val x1 = (r.right.coerceIn(0f, 1f) * frame.width).toInt()
        val y1 = (r.bottom.coerceIn(0f, 1f) * frame.height).toInt()
        if (x1 <= x0 || y1 <= y0) return result
        val step = max(1, max(x1 - x0, y1 - y0) / 256)
        for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
            if (ellipse) {
                val dx = (x + 0.5f - (x0 + x1) / 2f) / ((x1 - x0) / 2f)
                val dy = (y + 0.5f - (y0 + y1) / 2f) / ((y1 - y0) / 2f)
                if (dx * dx + dy * dy > 1f) continue
            }
            val rgb = channels(frame.pixels[y * frame.width + x])
            result.add(FloatArray(3) { rgb[it] / 255f })
        }
        return result
    }

    fun base(frame: FilmNegativeFrozenFrame, region: FilmNegativeRegion): FloatArray? {
        val all = samples(frame, region, true)
        val valid = all.filter { rgb -> rgb.all { it > 2f / 255 && it < 250f / 255 } }
        if (valid.size < 32 || valid.size < all.size * 0.8) return null
        return FloatArray(3) { c ->
            val values = valid.map { it[c] }.sorted()
            val median = values[values.size / 2]
            if (values[(values.size * 0.9).toInt()] - values[(values.size * 0.1).toInt()] >
                max(10f / 255, median * 0.12f)) return null
            median
        }
    }

    /** Shares the same structural checks as the live automatic base sampler. */
    fun findBase(frame: FilmNegativeFrozenFrame, monochrome: Boolean): FilmBaseCandidate? =
        FilmNegativeBaseDetector.find(frame.width, frame.height, frame.pixels, monochrome)

    fun window(frame: FilmNegativeFrozenFrame, region: FilmNegativeRegion,
        settings: FilmNegativeSettings): FilmNegativeDensityWindow? {
        val densities = samples(frame, region, false).filter { rgb -> rgb.all { it in 0.012f..0.97f } }
            .map { rgb -> FloatArray(3) { c ->
                -log10(FilmNegativeMath.linearize(rgb[c]) /
                    FilmNegativeMath.linearize(settings.base(c)).coerceAtLeast(1e-6)).toFloat()
            } }.filter { d -> d.all { it.isFinite() && it > -0.12f } }
            .sortedBy { it[0] * 0.2126f + it[1] * 0.7152f + it[2] * 0.0722f }
        if (densities.size < 128) return null
        // Each RGB endpoint comes from the same group of pixels, preserving channel relations.
        val tail = max(8, (densities.size * 0.025f).toInt())
        val low = FloatArray(3) { c -> densities.take(tail).map { it[c] }.average().toFloat() }
        val high = FloatArray(3) { c -> densities.takeLast(tail).map { it[c] }.average().toFloat() }
        if ((0..2).any { high[it] - low[it] < 0.12f }) return null
        return FilmNegativeDensityWindow(low[0], low[1], low[2], high[0], high[1], high[2])
    }
}
