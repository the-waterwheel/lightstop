package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.max

/** CaptureResult evidence, kept separate from rendered frames until their timestamps match. */
internal data class FilmNegativeFrameEvidence(
    val timestampNs: Long,
    val cameraKey: String,
    val locked: Boolean,
    val exposureNs: Long,
    val sensitivity: Int,
    val colorGains: List<Float>,
    val processingKey: String,
    val processingCurve: List<Float> = emptyList(),
) {
    val usable: Boolean get() = timestampNs > 0 && locked && exposureNs > 0 && sensitivity > 0 &&
        colorGains.size == 4 && colorGains.all { it.isFinite() && it > 0f }

    fun compatible(other: FilmNegativeFrameEvidence): Boolean = usable && other.usable &&
        cameraKey == other.cameraKey && processingKey == other.processingKey &&
        processingCurve.size == other.processingCurve.size &&
        processingCurve.indices.all { processingCurve[it].isFinite() && other.processingCurve[it].isFinite() &&
            abs(processingCurve[it] - other.processingCurve[it]) <= 1f / 255f } &&
        abs(exposureNs.toDouble() / other.exposureNs - 1.0) < 0.02 &&
        abs(sensitivity.toDouble() / other.sensitivity - 1.0) < 0.02 &&
        colorGains.indices.all { abs(colorGains[it] / other.colorGains[it] - 1f) < 0.02f }
}

internal data class FilmNegativeBaseSample(val rgb: FloatArray, val evidence: FilmNegativeFrameEvidence)

/** Three distinct, spatially valid frames with consistent capture settings and film base. */
internal class FilmNegativeSampleWindow {
    private val samples = ArrayList<FilmNegativeBaseSample>()
    fun clear() = samples.clear()

    fun add(rgb: FloatArray?, evidence: FilmNegativeFrameEvidence): FilmNegativeBaseSample? {
        if (!evidence.usable || rgb == null || rgb.size != 3 || rgb.any { !it.isFinite() }) {
            clear()
            return null
        }
        val previous = samples.lastOrNull()
        if (previous != null) {
            if (evidence.timestampNs <= previous.evidence.timestampNs) return null
            if (!evidence.compatible(previous.evidence) ||
                evidence.timestampNs - previous.evidence.timestampNs > 250_000_000L ||
                rgb.indices.any { abs(rgb[it] - previous.rgb[it]) > max(2f / 255f, previous.rgb[it] * 0.015f) }) clear()
        }
        // Compare to the anchor too, so a slowly changing light cannot accumulate unnoticed.
        val anchor = samples.firstOrNull()
        if (anchor != null && (!evidence.compatible(anchor.evidence) ||
                rgb.indices.any { abs(rgb[it] - anchor.rgb[it]) > max(2f / 255f, anchor.rgb[it] * 0.015f) })) clear()
        samples.add(FilmNegativeBaseSample(rgb.copyOf(), evidence))
        if (samples.size < 3) return null
        val result = FloatArray(3) { c -> samples.map { it.rgb[c] }.sorted()[1] }
        clear()
        return FilmNegativeBaseSample(result, evidence)
    }
}

/** Bounded exact-timestamp join; callbacks and camera texture updates may arrive in either order. */
internal class FilmNegativeSampleMatcher {
    private val metadata = LinkedHashMap<Long, FilmNegativeFrameEvidence>()
    private val pixels = LinkedHashMap<Long, FloatArray?>()
    private val window = FilmNegativeSampleWindow()
    private var lastTimestamp = 0L
    fun clear() { metadata.clear(); pixels.clear(); window.clear(); lastTimestamp = 0 }
    fun frame(timestamp: Long, rgb: FloatArray?): FilmNegativeBaseSample? {
        pixels[timestamp] = rgb
        trim(pixels)
        return match(timestamp)
    }
    fun evidence(value: FilmNegativeFrameEvidence): FilmNegativeBaseSample? {
        metadata[value.timestampNs] = value
        trim(metadata)
        return match(value.timestampNs)
    }
    private fun match(timestamp: Long): FilmNegativeBaseSample? {
        val evidence = metadata[timestamp] ?: return null
        if (!pixels.containsKey(timestamp)) return null
        val rgb = pixels.remove(timestamp)
        metadata.remove(timestamp)
        if (timestamp <= lastTimestamp) return null
        lastTimestamp = timestamp
        return window.add(rgb, evidence)
    }
    private fun <T> trim(map: LinkedHashMap<Long, T>) {
        while (map.size > 12) map.remove(map.keys.first())
    }
}
