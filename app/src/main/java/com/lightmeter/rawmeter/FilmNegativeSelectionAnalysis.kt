package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

internal data class FilmNegativeSelection(val region: FilmNegativeRegion,
    val baseRegion: FilmNegativeRegion?, val settings: FilmNegativeSettings,
    val evidence: FilmNegativeFrameEvidence, val orientationToken: Long = 0,
    val mask: FilmNegativeMask? = null, val baseSupport: FilmNegativeMask? = null)

/** On-demand analysis of the original texture. All geometry is relative to the whole image. */
internal object FilmNegativeSelectionAnalysis {
    const val ANALYSIS_EDGE = 256
    private const val EDGE = ANALYSIS_EDGE
    private val opticalDensity = FloatArray(256) { -log10(FilmNegativeMath.linearize(it / 255f).coerceAtLeast(1e-6)).toFloat() }
    private data class Image(val w: Int, val h: Int, val pixels: IntArray)
    private data class Patch(val x: Int, val y: Int, val rgb: FloatArray)
    private data class Nearby(val candidate: FilmBaseCandidate, val gap: Float)
    private fun rgb(pixel: Int) = FloatArray(3) { ((pixel ushr (16 - it * 8)) and 255) / 255f }
    private fun valid(rgb: FloatArray) = rgb.all { it in 4f / 255..246f / 255 }
    private fun density(rgb: FloatArray, base: FloatArray) = FloatArray(3) {
        opticalDensity[(rgb[it] * 255 + .5f).toInt().coerceIn(0, 255)] -
            opticalDensity[(base[it] * 255 + .5f).toInt().coerceIn(0, 255)]
    }
    private fun luminance(d: FloatArray) = d[0] * .2126f + d[1] * .7152f + d[2] * .0722f
    private fun median(values: List<Float>) = values.sorted()[values.size / 2]
    private fun image(frame: FilmNegativeFrozenFrame): Image? {
        if (frame.width <= 0 || frame.height <= 0 || frame.width.toLong() * frame.height != frame.pixels.size.toLong()) return null
        val scale = min(1f, EDGE.toFloat() / max(frame.width, frame.height))
        val w = (frame.width * scale).toInt(); val h = (frame.height * scale).toInt()
        if (min(w, h) < 18) return null
        return Image(w, h, IntArray(w * h) { i ->
            frame.pixels[min(frame.height - 1, ((i / w + .5f) * frame.height / h).toInt()) * frame.width +
                min(frame.width - 1, ((i % w + .5f) * frame.width / w).toInt())]
        })
    }
    private fun patch(image: Image, x: Int, y: Int, monochrome: Boolean, size: Int = 3): FloatArray? {
        val values = ArrayList<FloatArray>()
        for (dy in 0 until size) for (dx in 0 until size) {
            val value = rgb(image.pixels[(y + dy) * image.w + x + dx])
            if (!valid(value)) return null
            values.add(value)
        }
        val result = FloatArray(3) { c ->
            val sorted = values.map { it[c] }.sorted()
            val middle = sorted[sorted.size / 2]
            if (sorted.last() - sorted.first() > max(5f / 255, middle * .055f)) return null
            middle
        }
        if (monochrome) {
            if (result.maxOrNull()!! - result.minOrNull()!! > 20f / 255) return null
        } else if (result[0] < result[1] * 1.04f || result[1] < result[2] * 1.02f) return null
        if (result.any { -log10(FilmNegativeMath.linearize(it).coerceAtLeast(1e-6)) > 1.6 }) return null
        return result
    }
    private fun similar(a: FloatArray, b: FloatArray) = (0..2).all { abs(a[it] - b[it]) < max(5f / 255, min(a[it], b[it]) * .045f) }
    /** Search only the surrounding rails of this photograph, including rails inside a contact sheet. */
    fun nearbyBase(frame: FilmNegativeFrozenFrame, region: FilmNegativeRegion, monochrome: Boolean): FilmBaseCandidate? {
        val image = image(frame) ?: return null
        return nearbyBase(image, region, monochrome)
    }
    private fun nearbyBase(image: Image, r: FilmNegativeRegion, monochrome: Boolean,
        requireMultipleSides: Boolean = true): FilmBaseCandidate? {
        if (r.corners != null) return rotatedBase(image, r, monochrome, requireMultipleSides)
        val x0 = r.left * image.w; val x1 = r.right * image.w
        val y0 = r.top * image.h; val y1 = r.bottom * image.h
        if (x1 - x0 < 12 || y1 - y0 < 12) return null
        val padX = max(7f, min(image.w * .12f, (x1 - x0) * .22f))
        val padY = max(7f, min(image.h * .12f, (y1 - y0) * .22f))
        val patches = ArrayList<Patch>()
        for (y in 0 until image.h) for (x in 0 until image.w) {
            val outside = x + 1 <= x0 || x >= x1 || y + 1 <= y0 || y >= y1
            val near = x >= x0 - padX && x + 1 <= x1 + padX && y >= y0 - padY && y + 1 <= y1 + padY
            // Pixel-wide rails survive downsampling; uniformity is checked over the whole rail.
            if (outside && near) patch(image, x, y, monochrome, 1)?.let { patches.add(Patch(x, y, it)) }
        }
        val groups = ArrayList<List<Patch>>()
        val available = patches.associateBy { it.y * image.w + it.x }.toMutableMap()
        while (available.isNotEmpty()) {
            val seed = available.values.first()
            val group = ArrayList<Patch>(); val queue = ArrayDeque<Patch>()
            available.remove(seed.y * image.w + seed.x); queue.add(seed)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst(); group.add(current)
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    if (current.x + dx !in 0 until image.w || current.y + dy !in 0 until image.h) continue
                    val key = (current.y + dy) * image.w + current.x + dx
                    val next = available[key] ?: continue
                    if (!similar(seed.rgb, next.rgb)) continue
                    available.remove(key); queue.add(next)
                }
            }
            if (group.size >= 16) groups.add(group)
        }
        val candidates = groups.mapNotNull { group ->
            // A long local rail supplies geometry. A small orange patch cannot supply it.
            val rowSupport = group.groupBy { it.y }.values.maxOf { row -> row.count { it.x >= x0 && it.x + 1 <= x1 }.toFloat() }
            val colSupport = group.groupBy { it.x }.values.maxOf { col -> col.count { it.y >= y0 && it.y + 1 <= y1 }.toFloat() }
            val support = max(rowSupport / (x1 - x0), colSupport / (y1 - y0))
            if (support < .42f) return@mapNotNull null
            if (requireMultipleSides) {
                // A smooth photograph gradient can supply one uniform rail. Automatic mode
                // requires corroborating rails beside at least two sides of the photograph.
                val sides = listOf(
                    group.filter { it.y + 1 <= y0 }.groupBy { it.y }.values.maxOfOrNull { row -> row.count { it.x >= x0 && it.x + 1 <= x1 }.toFloat() / (x1 - x0) } ?: 0f,
                    group.filter { it.y >= y1 }.groupBy { it.y }.values.maxOfOrNull { row -> row.count { it.x >= x0 && it.x + 1 <= x1 }.toFloat() / (x1 - x0) } ?: 0f,
                    group.filter { it.x + 1 <= x0 }.groupBy { it.x }.values.maxOfOrNull { col -> col.count { it.y >= y0 && it.y + 1 <= y1 }.toFloat() / (y1 - y0) } ?: 0f,
                    group.filter { it.x >= x1 }.groupBy { it.x }.values.maxOfOrNull { col -> col.count { it.y >= y0 && it.y + 1 <= y1 }.toFloat() / (y1 - y0) } ?: 0f)
                if (sides.count { it >= .42f } < 2) return@mapNotNull null
            }
            val base = FloatArray(3) { c -> median(group.map { it.rgb[c] }) }
            val uniform = group.filter { similar(base, it.rgb) }
            if (uniform.size < group.size * .8f) return@mapNotNull null
            if ((0..2).any { c ->
                val values = uniform.map { it.rgb[c] }.sorted()
                values[(values.size * .9f).toInt()] - values[(values.size * .1f).toInt()] > max(6f / 255, base[c] * .05f)
            }) return@mapNotNull null
            var content = 0; var denser = 0
            for (y in max(0, y0.toInt()) until min(image.h, y1.toInt()) step 2)
                for (x in max(0, x0.toInt()) until min(image.w, x1.toInt()) step 2) {
                    val value = rgb(image.pixels[y * image.w + x])
                    if (!valid(value)) continue
                    content++
                    val d = density(value, base)
                    if (d.all { it > .015f } && luminance(d) > .06f) denser++
                }
            if (content < 32 || denser < max(16f, content * .25f)) return@mapNotNull null
            // Keep the physical sampling tile stable when sensor noise changes RGB rankings.
            val chosen = uniform.minBy { p ->
                val px = p.x + .5f; val py = p.y + .5f
                minOf(abs(px - (x0 + x1) / 2) / (x1 - x0) + abs(py - y0) / (y1 - y0),
                    abs(px - (x0 + x1) / 2) / (x1 - x0) + abs(py - y1) / (y1 - y0),
                    abs(py - (y0 + y1) / 2) / (y1 - y0) + abs(px - x0) / (x1 - x0),
                    abs(py - (y0 + y1) / 2) / (y1 - y0) + abs(px - x1) / (x1 - x0))
            }
            val gap = minOf(abs(chosen.x + .5f - x0) / image.w, abs(chosen.x + .5f - x1) / image.w,
                abs(chosen.y + .5f - y0) / image.h, abs(chosen.y + .5f - y1) / image.h)
            Nearby(FilmBaseCandidate(FilmNegativeRegion(chosen.x.toFloat() / image.w, chosen.y.toFloat() / image.h,
                (chosen.x + 1f) / image.w, (chosen.y + 1f) / image.h), base, .65f + min(1f, support) * .25f), gap)
        }
        val closest = candidates.minOfOrNull { it.gap } ?: return null
        val nearby = candidates.filter { it.gap <= closest + .02f }.map { it.candidate }
            .sortedByDescending { it.rgb.sumOf { v -> log10(FilmNegativeMath.linearize(v).coerceAtLeast(1e-6)) } }
        val best = nearby.firstOrNull() ?: return null
        // Conflicting references beside the same image require a human selection.
        if (nearby.drop(1).any { other -> !similar(best.rgb, other.rgb) &&
            abs(luminance(density(other.rgb, best.rgb))) < .12f }) return null
        return best
    }

    /** Inspect rails parallel to the edited crop, rather than its much larger rotated bounds. */
    private fun rotatedBase(image: Image, region: FilmNegativeRegion, monochrome: Boolean,
        requireMultipleSides: Boolean): FilmBaseCandidate? {
        if (!region.validCorners()) return null
        val crop = FilmNegativeCrop.from(region, image.w, image.h)
        if (crop.width < 12 || crop.height < 12) return null
        val padX = max(7f, min(image.w * .12f, crop.width * .22f))
        val padY = max(7f, min(image.h * .12f, crop.height * .22f))
        val spanX = crop.width + 2 * padX; val spanY = crop.height + 2 * padY
        val scale = min(1f, EDGE / max(spanX, spanY))
        val w = ceil(spanX * scale).toInt(); val h = ceil(spanY * scale).toInt()
        fun original(x: Float, y: Float) = crop.point(x * spanX - spanX / 2, y * spanY - spanY / 2)
        // Nearest-neighbour readback preserves actual encoded RGB used for the film reference.
        val pixels = IntArray(w * h) { i ->
            val p = original((i % w + .5f) / w, (i / w + .5f) / h)
            if (p.x < 0 || p.y < 0 || p.x >= image.w || p.y >= image.h) -1
            else image.pixels[p.y.toInt() * image.w + p.x.toInt()]
        }
        val localRegion = FilmNegativeRegion(padX / spanX, padY / spanY,
            (padX + crop.width) / spanX, (padY + crop.height) / spanY)
        val candidate = nearbyBase(Image(w, h, pixels), localRegion, monochrome, requireMultipleSides) ?: return null
        val r = candidate.region
        val points = listOf(original(r.left, r.top), original(r.right, r.top),
            original(r.right, r.bottom), original(r.left, r.bottom))
        return candidate.copy(region = FilmNegativeRegion((points.minOf { it.x } / image.w).coerceIn(0f, 1f),
            (points.minOf { it.y } / image.h).coerceIn(0f, 1f), (points.maxOf { it.x } / image.w).coerceIn(0f, 1f),
            (points.maxOf { it.y } / image.h).coerceIn(0f, 1f)))
    }

    /** Validate actual curved/rotated support, then compute tone only inside that support. */
    fun automatic(frame: FilmNegativeFrozenFrame, settings: FilmNegativeSettings): FilmNegativeSelection? {
        if (!frame.evidence.usable) return null
        val sampled = image(frame) ?: return null
        val reduced = frame.copy(width = sampled.w, height = sampled.h, pixels = sampled.pixels)
        return FilmNegativeShapeDetector.find(reduced).sortedByDescending { photo ->
            val r = photo.region
            val distance = abs((r.left + r.right) / 2 - .5f) + abs((r.top + r.bottom) / 2 - .5f)
            (photo.mask.area.toFloat() / (photo.mask.width * photo.mask.height)) / (1f + distance)
        }.firstNotNullOfOrNull { photo ->
            // Avoid computing colour and percentiles for every other frame on a strip.
            // If the preferred photograph has insufficient tone, try the next candidate.
            analyze(reduced, photo.region, settings.copy(monochrome = photo.monochrome), photo.base, photo.mask, photo.baseSupport)
        }
    }

    fun manual(frame: FilmNegativeFrozenFrame, region: FilmNegativeRegion, settings: FilmNegativeSettings): FilmNegativeSelection? {
        if (listOf(region.left, region.top, region.right, region.bottom).any { !it.isFinite() }) return null
        if (!region.validCorners()) return null
        val points = region.corners
        val r = if (points == null) FilmNegativeRegion(region.left.coerceIn(0f, 1f), region.top.coerceIn(0f, 1f),
            region.right.coerceIn(0f, 1f), region.bottom.coerceIn(0f, 1f))
        else FilmNegativeRegion(points.minOf { it.x }, points.minOf { it.y },
            points.maxOf { it.x }, points.maxOf { it.y }, points.toList())
        if (r.right <= r.left || r.bottom <= r.top) return null
        val sampled = image(frame) ?: return null
        val reduced = frame.copy(width = sampled.w, height = sampled.h, pixels = sampled.pixels)
        val cropMask = if (points == null) null else FilmNegativeMask(sampled.w, sampled.h,
            BooleanArray(sampled.w * sampled.h) { i -> r.contains((i % sampled.w + .5f) / sampled.w,
                (i / sampled.w + .5f) / sampled.h) })
        fun similarity(photo: FilmNegativeShapeDetector.Photograph) = cropMask?.overlap(photo.mask) ?: overlap(photo.region, r)
        val photo = FilmNegativeShapeDetector.find(reduced, settings.monochrome)
            .filter { similarity(it) > .85f }.maxByOrNull(::similarity)
        return if (photo != null) analyze(reduced, r, settings, photo.base, photo.mask, photo.baseSupport)
        else analyze(frame, r, settings, nearbyBase(frame, r, settings.monochrome))
    }
    private fun analyze(frame: FilmNegativeFrozenFrame, r: FilmNegativeRegion,
        settings: FilmNegativeSettings, reference: FilmBaseCandidate?,
        mask: FilmNegativeMask? = null, baseSupport: FilmNegativeMask? = null): FilmNegativeSelection? {
        if (frame.width <= 0 || frame.height <= 0 || frame.width.toLong() * frame.height != frame.pixels.size.toLong()) return null
        val x0 = (r.left * frame.width).toInt(); val x1 = (r.right * frame.width).toInt()
        val y0 = (r.top * frame.height).toInt(); val y1 = (r.bottom * frame.height).toInt()
        if (x1 - x0 < 12 || y1 - y0 < 12) return null
        val values = ArrayList<FloatArray>(); val quadrants = ArrayList<Int>(); var total = 0
        val step = max(1, max(x1 - x0, y1 - y0) / 160)
        for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
            if (!r.contains((x + .5f) / frame.width, (y + .5f) / frame.height)) continue
            if (mask != null && !mask.interior((x + .5f) / frame.width, (y + .5f) / frame.height)) continue
            total++
            val value = rgb(frame.pixels[y * frame.width + x])
            if (!valid(value)) continue
            values.add(value); quadrants.add((if (x > (x0 + x1) / 2) 1 else 0) + (if (y > (y0 + y1) / 2) 2 else 0))
        }
        if (values.size < 128 || values.size < total * .6f) return null
        val base = reference?.rgb ?: FloatArray(3) { c ->
            val sorted = values.map { it[c] }.sorted(); sorted[(sorted.size * .985f).toInt().coerceAtMost(sorted.lastIndex)]
        }
        val densities = values.map { density(it, base) }
        val levels = densities.map(::luminance).sorted()
        val low = levels[(levels.size * .02f).toInt()]
        val high = levels[(levels.size * .98f).toInt().coerceAtMost(levels.lastIndex)]
        if (high - low < .12f) return null
        val correction = if (settings.monochrome) null else neutralCorrection(densities, quadrants, low, high)
        val corrected = densities.map { d -> luminance(FloatArray(3) { c ->
            correction?.apply(d[c].toDouble(), c, settings.colorStrength)?.toFloat() ?: d[c]
        }) }.sorted()
        val adjusted = settings.copy(baseRed = base[0], baseGreen = base[1], baseBlue = base[2],
            blackDensity = corrected[(corrected.size * .02f).toInt()],
            whiteDensity = corrected[(corrected.size * .98f).toInt().coerceAtMost(corrected.lastIndex)],
            regionTone = true, densityWindow = null, colorCorrection = correction,
            gamma = 1f, exposureEv = 0f, warmth = 0f, tint = 0f, saturation = 1f, inverted = true,
            redAlignment = 0f, greenAlignment = 0f, blueAlignment = 0f,
            redCurve = FilmNegativeCurve(), greenCurve = FilmNegativeCurve(), blueCurve = FilmNegativeCurve(), contrast = 1f,
            referenceSource = if (reference == null) FilmNegativeReferenceSource.CONTENT_ESTIMATE else FilmNegativeReferenceSource.FILM_BORDER)
        return FilmNegativeSelection(r, reference?.region, adjusted, frame.evidence, frame.orientationToken, mask, baseSupport)
    }

    private fun neutralCorrection(densities: List<FloatArray>, quadrants: List<Int>, low: Float, high: Float): FilmNegativeColorCorrection? {
        val groups = Array(3) { ArrayList<FloatArray>() }; val spatial = HashSet<Int>()
        for ((index, d) in densities.withIndex()) {
            val level = (luminance(d) - low) / (high - low)
            if (level !in .08f.. .92f || d.maxOrNull()!! - d.minOrNull()!! > max(.07f, (high - low) * .16f)) continue
            groups[min(2, (level * 3).toInt())].add(d); spatial.add(quadrants[index])
        }
        // No gray-world correction: a single hue or a single small object is insufficient.
        val axes = groups.filter { it.size >= max(12, densities.size / 100) }
            .map { group -> FloatArray(3) { c -> median(group.map { it[c] }) } }
        if (axes.size < 2 || spatial.size < 3) return null
        val first = axes.first(); val last = axes.last()
        fun fit(c: Int): Pair<Float, Float>? {
            if (last[c] - first[c] < .08f) return null
            val slope = ((last[1] - first[1]) / (last[c] - first[c])).coerceIn(.8f, 1.25f)
            val offset = median(axes.map { it[1] - it[c] * slope }).coerceIn(-.12f, .12f)
            if (axes.any { abs(it[c] * slope + offset - it[1]) > .055f }) return null
            return slope to offset
        }
        val red = fit(0) ?: return null; val blue = fit(2) ?: return null
        return FilmNegativeColorCorrection(red.first, blue.first, red.second, blue.second)
    }

    internal fun overlap(a: FilmNegativeRegion, b: FilmNegativeRegion): Float {
        val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - intersection
        return intersection / max(.00000001f, union)
    }
}

/** Anchor all three results, so a moving selection or changing light cannot slowly drift. */
internal class FilmNegativeSelectionWindow {
    private val selections = ArrayList<FilmNegativeSelection>()
    fun clear() = selections.clear()
    fun add(value: FilmNegativeSelection): FilmNegativeSelection? {
        val last = selections.lastOrNull()
        if (last != null && value.evidence.timestampNs <= last.evidence.timestampNs) return null
        val first = selections.firstOrNull()
        if (first != null && (!first.evidence.compatible(value.evidence) ||
            first.orientationToken != value.orientationToken ||
            first.settings.monochrome != value.settings.monochrome ||
            value.evidence.timestampNs - last!!.evidence.timestampNs > 1_500_000_000L ||
            FilmNegativeSelectionAnalysis.overlap(first.region, value.region) < .85f ||
            first.baseRegion == null || value.baseRegion == null ||
            !consistentSupport(first, value) ||
            (0..2).any { abs(first.settings.base(it) - value.settings.base(it)) > max(3f / 255, first.settings.base(it) * .02f) } ||
            abs(first.settings.blackDensity - value.settings.blackDensity) > .04f ||
            abs(first.settings.whiteDensity - value.settings.whiteDensity) > .06f ||
            !consistentCorrection(first.settings.colorCorrection, value.settings.colorCorrection))) selections.clear()
        if (!value.evidence.usable || value.baseRegion == null) { selections.clear(); return null }
        selections.add(value)
        if (selections.size < 3) return null
        val result = selections[1].copy(evidence = value.evidence)
        selections.clear()
        return result
    }
    private fun consistentSupport(a: FilmNegativeSelection, b: FilmNegativeSelection): Boolean {
        if (a.mask != null || b.mask != null) {
            if (a.mask == null || b.mask == null || a.mask.overlap(b.mask) < .8f) return false
        }
        if (a.baseSupport != null || b.baseSupport != null) {
            // Register the narrow border by the already-validated photograph translation.
            // Two pixels of hand movement can otherwise make identical one-pixel rails disjoint.
            val du = (b.region.left + b.region.right - a.region.left - a.region.right) / 2
            val dv = (b.region.top + b.region.bottom - a.region.top - a.region.bottom) / 2
            return a.baseSupport != null && b.baseSupport != null && a.baseSupport.overlap(b.baseSupport, du, dv) >= .6f
        }
        val ar = a.baseRegion ?: return false; val br = b.baseRegion ?: return false
        // Older/manual candidates may be a single analysis pixel. Compare location in image
        // coordinates, not IoU of tiny tiles, while still rejecting a different rail.
        return abs(ar.left + ar.right - br.left - br.right) < .04f &&
            abs(ar.top + ar.bottom - br.top - br.bottom) < .04f
    }
    private fun consistentCorrection(a: FilmNegativeColorCorrection?, b: FilmNegativeColorCorrection?): Boolean {
        if (a == null || b == null) return a == b
        return abs(a.redSlope - b.redSlope) < .08f && abs(a.blueSlope - b.blueSlope) < .08f &&
            abs(a.redOffset - b.redOffset) < .04f && abs(a.blueOffset - b.blueOffset) < .04f
    }
}
