package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Conservative clear-rebate detector. Scores describe support, not calibrated probabilities. */
internal object FilmNegativeBaseDetector {
    const val ANALYSIS_EDGE = 192
    private const val TILE = 6
    private const val STRIDE = 3
    private data class Patch(val x: Int, val y: Int, val rgb: IntArray)
    private data class Supported(val candidate: FilmBaseCandidate, val transmission: Double)

    fun find(width: Int, height: Int, pixels: IntArray, monochrome: Boolean): FilmBaseCandidate? {
        if (width < TILE * 3 || height < TILE * 3 || width.toLong() * height != pixels.size.toLong()) return null
        val scale = min(1.0, ANALYSIS_EDGE.toDouble() / max(width, height))
        val w = (width * scale).toInt()
        val h = (height * scale).toInt()
        if (min(w, h) < TILE * 3) return null
        // Point sampling avoids treating averages of encoded RGB as linear-light averages.
        val image = IntArray(w * h) { i ->
            val x = ((i % w + 0.5) * width / w).toInt().coerceAtMost(width - 1)
            val y = ((i / w + 0.5) * height / h).toInt().coerceAtMost(height - 1)
            pixels[y * width + x]
        }
        val cols = (w - TILE) / STRIDE + 1
        val rows = (h - TILE) / STRIDE + 1
        val patches = arrayOfNulls<Patch>(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            uniformPatch(image, w, x * STRIDE, y * STRIDE, monochrome)?.let {
                patches[y * cols + x] = Patch(x * STRIDE, y * STRIDE, it)
            }
        }
        val seen = BooleanArray(patches.size)
        val queue = IntArray(patches.size)
        val supported = ArrayList<Supported>()
        for (seed in patches.indices) {
            if (seen[seed] || patches[seed] == null) continue
            var head = 0
            var tail = 1
            queue[0] = seed
            seen[seed] = true
            val group = ArrayList<Int>()
            while (head < tail) {
                val index = queue[head++]
                group.add(index)
                val patch = patches[index]!!
                val x = index % cols
                val y = index / cols
                val neighbors = intArrayOf(if (x > 0) index - 1 else -1,
                    if (x + 1 < cols) index + 1 else -1,
                    if (y > 0) index - cols else -1,
                    if (y + 1 < rows) index + cols else -1)
                for (next in neighbors) {
                    if (next < 0 || seen[next]) continue
                    val adjacent = patches[next] ?: continue
                    if (!sameColor(patch.rgb, adjacent.rgb)) continue
                    seen[next] = true
                    queue[tail++] = next
                }
            }
            assess(group, patches, cols, rows, image, w, h)?.let(supported::add)
        }
        val ranked = supported.sortedByDescending { it.transmission }
        val best = ranked.firstOrNull() ?: return null
        // Two similarly transmissive but differently colored borders are ambiguous.
        if (ranked.drop(1).any { other ->
            abs(other.transmission - best.transmission) < 0.08 &&
                (0..2).any { abs(other.candidate.rgb[it] - best.candidate.rgb[it]) > 0.04f }
        }) return null
        return best.candidate
    }

    private fun channel(pixel: Int, c: Int) = (pixel ushr (16 - 8 * c)) and 255
    private fun valid(pixel: Int) = (0..2).all { channel(pixel, it) in 4..246 }
    private fun sameColor(a: IntArray, b: IntArray) = (0..2).all {
        abs(a[it] - b[it]) <= max(4.0, min(a[it], b[it]) * 0.035)
    }

    private fun uniformPatch(image: IntArray, width: Int, x: Int, y: Int, monochrome: Boolean): IntArray? {
        val values = Array(3) { IntArray(TILE * TILE) }
        var count = 0
        for (dy in 0 until TILE) for (dx in 0 until TILE) {
            val pixel = image[(y + dy) * width + x + dx]
            if (!valid(pixel)) continue
            for (c in 0..2) values[c][count] = channel(pixel, c)
            count++
        }
        if (count < ceil(TILE * TILE * 0.9).toInt()) return null
        val rgb = IntArray(3) { c ->
            values[c].sort(0, count)
            val median = values[c][count / 2]
            if (values[c][(count * 0.9).toInt()] - values[c][(count * 0.1).toInt()] > max(4.0, median * 0.05)) return null
            median
        }
        if (monochrome) {
            if (rgb.maxOrNull()!! - rgb.minOrNull()!! > 20) return null
        } else if (rgb[0] < rgb[1] * 1.08 || rgb[1] < rgb[2] * 1.04) return null
        // Avoid noisy very dense patches even when their encoded color looks orange.
        if (rgb.any { -log10(FilmNegativeMath.linearize(it / 255f).coerceAtLeast(1e-6)) !in 0.03..1.6 }) return null
        return rgb
    }

    private fun assess(group: List<Int>, patches: Array<Patch?>, cols: Int, rows: Int,
        image: IntArray, w: Int, h: Int): Supported? {
        if (group.size < 8) return null
        val rowCounts = IntArray(rows)
        val colCounts = IntArray(cols)
        for (index in group) { rowCounts[index / cols]++; colCounts[index % cols]++ }
        var coverage = 0f
        for (y in 0 until rows) if (y < rows * 0.28 || y > rows * 0.72) {
            coverage = max(coverage, rowCounts[y].toFloat() / cols)
        }
        for (x in 0 until cols) if (x < cols * 0.28 || x > cols * 0.72) {
            coverage = max(coverage, colCounts[x].toFloat() / rows)
        }
        // Require an extended edge band, not an isolated orange object or a central patch.
        if (coverage < 0.55f) return null
        val rgb = IntArray(3) { c ->
            val values = group.map { patches[it]!!.rgb[c] }.sorted()
            val median = values[values.size / 2]
            // Connected neighbors must also agree globally; gradients cannot creep through.
            if (values[(values.size * 0.9).toInt()] - values[(values.size * 0.1).toInt()] > max(5.0, median * 0.05)) return null
            median
        }
        val reference = DoubleArray(3) { FilmNegativeMath.linearize(rgb[it] / 255f).coerceAtLeast(1e-6) }
        var validContent = 0
        var denserContent = 0
        for (y in h / 4 until h * 3 / 4 step STRIDE) for (x in w / 4 until w * 3 / 4 step STRIDE) {
            val pixel = image[y * w + x]
            if (!valid(pixel)) continue
            validContent++
            val densities = DoubleArray(3) { c ->
                log10(reference[c] / FilmNegativeMath.linearize(channel(pixel, c) / 255f).coerceAtLeast(1e-6))
            }
            if (densities.all { it > 0.025 } && densities.average() > 0.08) denserContent++
        }
        // A uniform field alone has no evidence separating clear film from colored light.
        // An opaque holder alone must not supply the missing photograph evidence either.
        if (validContent < 32 || denserContent < max(16.0, validContent * 0.18)) return null
        val chosen = group.map { patches[it]!! }.filter { patch ->
            patch.x < w * 0.28 || patch.x > w * 0.72 || patch.y < h * 0.28 || patch.y > h * 0.72
        }.minByOrNull { patch ->
            // Prefer the continuous inner rail to tiny clear gaps between sprocket holes.
            val bandSupport = max(rowCounts[patch.y / STRIDE].toDouble() / cols,
                colCounts[patch.x / STRIDE].toDouble() / rows)
            (0..2).sumOf { abs(patch.rgb[it] - rgb[it]) }.toDouble() + (1.0 - bandSupport) * 24.0
        } ?: return null
        val region = FilmNegativeRegion(chosen.x.toFloat() / w, chosen.y.toFloat() / h,
            (chosen.x + TILE).toFloat() / w, (chosen.y + TILE).toFloat() / h)
        return Supported(FilmBaseCandidate(region, FloatArray(3) { rgb[it] / 255f },
            0.65f + 0.25f * coverage), reference.map { log10(it) }.average())
    }
}
