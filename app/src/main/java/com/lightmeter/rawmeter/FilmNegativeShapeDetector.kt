package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Image-space support, independent of frame format, orientation and a rectangular UI crop. */
internal class FilmNegativeMask(val width: Int, val height: Int, private val pixels: BooleanArray) {
    val area: Int = pixels.count { it }
    fun at(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && pixels[y * width + x]
    fun contains(u: Float, v: Float): Boolean = u >= 0f && u < 1f && v >= 0f && v < 1f &&
        at((u * width).toInt(), (v * height).toInt())
    fun interior(u: Float, v: Float): Boolean {
        val x = (u * width).toInt(); val y = (v * height).toInt()
        return at(x, y) && at(x - 1, y) && at(x + 1, y) && at(x, y - 1) && at(x, y + 1)
    }
    fun overlap(other: FilmNegativeMask, offsetU: Float = 0f, offsetV: Float = 0f): Float {
        var intersection = 0; var union = 0
        for (y in 0 until height) for (x in 0 until width) {
            val a = at(x, y); val b = other.contains((x + .5f) / width + offsetU, (y + .5f) / height + offsetV)
            if (a || b) union++
            if (a && b) intersection++
        }
        return intersection.toFloat() / max(1, union)
    }
}

/**
 * Bounded, non-ML segmentation on a small original preview. Clear-film colour proposes a
 * density component; surrounding clear-film support validates it. No straight edges, fixed
 * aspect ratio, sprocket holes, perspective rectification or optical-flow dependency is needed.
 */
internal object FilmNegativeShapeDetector {
    internal data class Photograph(val region: FilmNegativeRegion, val mask: FilmNegativeMask,
        val base: FilmBaseCandidate, val baseSupport: FilmNegativeMask, val monochrome: Boolean)
    private data class Component(val left: Int, val top: Int, val right: Int, val bottom: Int, val pixels: IntArray)
    private val density = FloatArray(256) { -log10(FilmNegativeMath.linearize(it / 255f).coerceAtLeast(1e-6)).toFloat() }
    private fun channel(pixel: Int, c: Int) = (pixel ushr (16 - c * 8)) and 255
    // These helpers run millions of times for a noisy strip. Avoid generic range iterators
    // in the pixel loops: ART does not eliminate their allocation as reliably as desktop JVMs.
    private fun valid(pixel: Int) = channel(pixel, 0) in 4..246 &&
        channel(pixel, 1) in 4..246 && channel(pixel, 2) in 4..246
    private fun similar(pixel: Int, base: FloatArray) =
        abs(channel(pixel, 0) - base[0]) <= max(5f, base[0] * .045f) &&
        abs(channel(pixel, 1) - base[1]) <= max(5f, base[1] * .045f) &&
        abs(channel(pixel, 2) - base[2]) <= max(5f, base[2] * .045f)
    private fun possibleBase(pixel: Int, monochrome: Boolean?): Boolean {
        if (!valid(pixel) || density[channel(pixel, 0)] > 1.6f ||
            density[channel(pixel, 1)] > 1.6f || density[channel(pixel, 2)] > 1.6f) return false
        val r = channel(pixel, 0); val g = channel(pixel, 1); val b = channel(pixel, 2)
        val gray = max(r, max(g, b)) - min(r, min(g, b)) <= 20
        val orange = r >= g * 1.04f && g >= b * 1.02f
        return when (monochrome) { true -> gray; false -> orange; null -> gray || orange }
    }

    fun find(frame: FilmNegativeFrozenFrame, monochrome: Boolean? = null): List<Photograph> {
        if (frame.width <= 0 || frame.height <= 0 || frame.width.toLong() * frame.height != frame.pixels.size.toLong()) return emptyList()
        val scale = min(1f, FilmNegativeSelectionAnalysis.ANALYSIS_EDGE.toFloat() / max(frame.width, frame.height))
        val w = (frame.width * scale).toInt(); val h = (frame.height * scale).toInt()
        if (min(w, h) < 18) return emptyList()
        val pixels = IntArray(w * h) { i -> frame.pixels[
            min(frame.height - 1, ((i / w + .5f) * frame.height / h).toInt()) * frame.width +
                min(frame.width - 1, ((i % w + .5f) * frame.width / w).toInt())] }
        // Quantized histogram bounds seed generation even on noisy, highly textured pictures.
        // A pair of similar neighbours also retains one-pixel diagonal or curved clear rails.
        val counts = IntArray(32768); val sums = Array(3) { LongArray(counts.size) }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x; val p = pixels[i]
            if (!possibleBase(p, monochrome)) continue
            var neighbours = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val q = pixels[i + dy * w + dx]
                if (abs(channel(p, 0) - channel(q, 0)) <= 5 &&
                    abs(channel(p, 1) - channel(q, 1)) <= 5 &&
                    abs(channel(p, 2) - channel(q, 2)) <= 5) neighbours++
            }
            if (neighbours < 2) continue
            val key = (channel(p, 0) / 8 shl 10) or (channel(p, 1) / 8 shl 5) or (channel(p, 2) / 8)
            counts[key]++
            for (c in 0..2) sums[c][key] += channel(p, c).toLong()
        }
        val seeds = ArrayList<FloatArray>()
        fun orange(base: FloatArray) = base[0] >= base[1] * 1.04f && base[1] >= base[2] * 1.02f
        val bins = counts.indices.filter { counts[it] >= 12 }.sortedByDescending { key ->
            (0..2).sumOf { sums[it][key].toDouble() / counts[key] }
        }
        for (key in bins) {
            val base = FloatArray(3) { sums[it][key].toFloat() / counts[key] }
            // Unclipped white backlight must not consume all slots before orange film base.
            if (seeds.count { orange(it) == orange(base) } >= 12) continue
            if (seeds.none { other -> (0..2).all { abs(base[it] - other[it]) <= max(5f, other[it] * .045f) } }) seeds.add(base)
            if (seeds.size == (if (monochrome == null) 24 else 12)) break
        }
        val proposals = ArrayList<Photograph>()
        val queue = IntArray(pixels.size)
        for (base in seeds) {
            val clear = BooleanArray(pixels.size) { valid(pixels[it]) && similar(pixels[it], base) }
            val dense = BooleanArray(pixels.size) { i ->
                val p = pixels[i]
                if (!valid(p) || clear[i]) false else {
                    val dr = density[channel(p, 0)] - density[base[0].toInt()]
                    val dg = density[channel(p, 1)] - density[base[1].toInt()]
                    val db = density[channel(p, 2)] - density[base[2].toInt()]
                    min(dr, min(dg, db)) > -.025f && dr * .2126f + dg * .7152f + db * .0722f > .07f
                }
            }
            val components = ArrayList<Component>()
            for (seed in dense.indices) {
                if (!dense[seed]) continue
                var head = 0; var tail = 1; queue[0] = seed; dense[seed] = false
                var left = w; var right = 0; var top = h; var bottom = 0
                while (head < tail) {
                    val i = queue[head++]; val x = i % w; val y = i / w
                    left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
                    for (dy in -1..1) for (dx in -1..1) {
                        if (x + dx !in 0 until w || y + dy !in 0 until h) continue
                        val next = i + dy * w + dx
                        if (dense[next]) { dense[next] = false; queue[tail++] = next }
                    }
                }
                if (tail < 128 || right - left < 12 || bottom - top < 12 || tail > pixels.size * .94f) continue
                // Bound expensive contour validation on textured/noisy scenes. Keep the eight
                // largest candidates, independent of scan order, before allocating any masks.
                if (components.size == 8) {
                    val smallest = components.minBy { it.pixels.size }
                    if (tail <= smallest.pixels.size) continue
                    components.remove(smallest)
                }
                components.add(Component(left, top, right, bottom, queue.copyOf(tail)))
            }
            for (component in components) {
                val (left, top, right, bottom, members) = component
                val mask = BooleanArray(pixels.size)
                for (i in members) mask[i] = true
                // Fill enclosed image detail without joining adjacent photographs across their
                // clear separator. The outer contour remains free to bend or be trapezoidal.
                fillHoles(mask, w, h, queue)
                val support = BooleanArray(pixels.size)
                val sectors = IntArray(8); val supportedSectors = IntArray(8)
                var boundary = 0; var supported = 0; var borderTouches = 0
                val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
                for (y in top..bottom) for (x in left..right) {
                    val i = y * w + x
                    if (!mask[i]) continue
                    if (x == 0 || x == w - 1 || y == 0 || y == h - 1) { borderTouches++; continue }
                    if (mask[i - 1] && mask[i + 1] && mask[i - w] && mask[i + w]) continue
                    boundary++
                    val sector = (((atan2((y - cy).toDouble(), (x - cx).toDouble()) + Math.PI) * 4 / Math.PI).toInt()).coerceIn(0, 7)
                    sectors[sector]++
                    var found = false
                    for (dy in -2..2) for (dx in -2..2) {
                        if (x + dx !in 0 until w || y + dy !in 0 until h) continue
                        val n = i + dy * w + dx
                        if (!mask[n] && clear[n]) { found = true; support[n] = true }
                    }
                    if (found) { supported++; supportedSectors[sector]++ }
                }
                // Surrounding evidence must extend around the component; a single orange
                // gradient band inside a photograph is never sufficient to identify film base.
                if (boundary < 30 || borderTouches > boundary * .1f || supported < boundary * .65f ||
                    (0..7).count { sectors[it] >= 2 && supportedSectors[it] >= sectors[it] * .45f } < 6) continue
                val indices = support.indices.filter { support[it] }
                if (indices.size < 24) continue
                val reference = FloatArray(3) { c ->
                    val values = indices.map { channel(pixels[it], c) }.sorted()
                    if (values[(values.size * .9f).toInt()] - values[(values.size * .1f).toInt()] > max(6f, base[c] * .055f)) return@FloatArray Float.NaN
                    values[values.size / 2] / 255f
                }
                if (reference.any { !it.isFinite() }) continue
                val grayFilm = monochrome ?: !orange(reference)
                if (grayFilm) {
                    // A neutral light table around orange film is not a B&W film border.
                    // Require largely neutral *relative density* inside the proposed image.
                    var measured = 0; var neutral = 0
                    val baseDensity = FloatArray(3) { density[(reference[it] * 255 + .5f).toInt().coerceIn(0, 255)] }
                    for (i in members) {
                        if (!valid(pixels[i])) continue
                        val dr = density[channel(pixels[i], 0)] - baseDensity[0]
                        val dg = density[channel(pixels[i], 1)] - baseDensity[1]
                        val db = density[channel(pixels[i], 2)] - baseDensity[2]
                        measured++
                        if (max(dr, max(dg, db)) - min(dr, min(dg, db)) < .12f) neutral++
                    }
                    if (measured < 128 || neutral < measured * .85f) continue
                }
                val region = FilmNegativeRegion(left.toFloat() / w, top.toFloat() / h, (right + 1f) / w, (bottom + 1f) / h)
                val shape = FilmNegativeMask(w, h, mask)
                if (proposals.any { it.mask.overlap(shape) > .8f }) continue
                // A stable physical tile is for display only. Temporal validation uses the
                // complete supporting border, so one sensor pixel moving cannot reset it.
                val anchor = indices.minBy { i -> abs(i % w - cx) + abs(i / w - top) }
                val candidate = FilmBaseCandidate(FilmNegativeRegion((anchor % w).toFloat() / w,
                    (anchor / w).toFloat() / h, (anchor % w + 1f) / w, (anchor / w + 1f) / h),
                    reference, supported.toFloat() / boundary)
                proposals.add(Photograph(region, shape, candidate, FilmNegativeMask(w, h, support), grayFilm))
            }
        }
        return proposals
    }

    private fun fillHoles(mask: BooleanArray, w: Int, h: Int, queue: IntArray) {
        val outside = BooleanArray(mask.size)
        var head = 0; var tail = 0
        fun add(i: Int) {
            if (!mask[i] && !outside[i]) { outside[i] = true; queue[tail++] = i }
        }
        for (x in 0 until w) { add(x); add((h - 1) * w + x) }
        for (y in 0 until h) { add(y * w); add(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]; val x = i % w; val y = i / w
            if (x > 0) add(i - 1)
            if (x + 1 < w) add(i + 1)
            if (y > 0) add(i - w)
            if (y + 1 < h) add(i + w)
        }
        for (i in mask.indices) if (!outside[i]) mask[i] = true
    }
}
