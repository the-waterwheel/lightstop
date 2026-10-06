package com.lightmeter.rawmeter

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.ceil

/** Display-only settings. They never change metering calibration or camera preferences. */
data class FilmNegativeSettings(
    val baseRed: Float = 0.90f,
    val baseGreen: Float = 0.60f,
    val baseBlue: Float = 0.35f,
    val blackDensity: Float = 0f,
    val whiteDensity: Float = 1.5f,
    val gamma: Float = 1.4f,
    val exposureEv: Float = 0f,
    val saturation: Float = 1f,
    val monochrome: Boolean = false,
    val filmGamma: Float = 0.65f,
    val inverted: Boolean = false,
    val showGuide: Boolean = false,
    val densityWindow: FilmNegativeDensityWindow? = null,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val regionTone: Boolean = false,
    val colorCorrection: FilmNegativeColorCorrection? = null,
    val colorStrength: Float = 0.7f,
    val referenceSource: FilmNegativeReferenceSource = FilmNegativeReferenceSource.DEFAULT,
    val redAlignment: Float = 0f,
    val greenAlignment: Float = 0f,
    val blueAlignment: Float = 0f,
    val redCurve: FilmNegativeCurve = FilmNegativeCurve(),
    val greenCurve: FilmNegativeCurve = FilmNegativeCurve(),
    val blueCurve: FilmNegativeCurve = FilmNegativeCurve(),
    val contrast: Float = 1f,
) {
    fun alignment(channel: Int) = when (channel) { 0 -> redAlignment; 1 -> greenAlignment; else -> blueAlignment }
    fun curve(channel: Int) = when (channel) { 0 -> redCurve; 1 -> greenCurve; else -> blueCurve }
    fun base(channel: Int): Float = when (channel) {
        0 -> baseRed
        1 -> baseGreen
        else -> baseBlue
    }

    /** A reference belongs to one capture setup. Resume must start with original camera colours. */
    fun withoutReference() = FilmNegativeSettings(monochrome = monochrome,
        baseGreen = if (monochrome) 0.9f else 0.6f,
        baseBlue = if (monochrome) 0.9f else 0.35f)
}

enum class FilmNegativeReferenceSource { DEFAULT, FILM_BORDER, CONTENT_ESTIMATE }

/** A bounded, monotone correction in density space, separate from shared luminance endpoints. */
data class FilmNegativeColorCorrection(val redSlope: Float, val blueSlope: Float,
    val redOffset: Float, val blueOffset: Float) {
    fun apply(density: Double, channel: Int, strength: Float): Double {
        val amount = strength.coerceIn(0f, 1f)
        val slope = when (channel) { 0 -> redSlope; 2 -> blueSlope; else -> 1f }
        val offset = when (channel) { 0 -> redOffset; 2 -> blueOffset; else -> 0f }
        return density * (1 + (slope - 1) * amount) + offset * amount
    }
}

/**
 * Approximate conversion of ISP preview RGB, independently implemented from the density formula.
 * The one-dimensional RGB lookup bakes the non-linear work whenever settings change, leaving
 * texture lookups and saturation for the ES 2.0 shader. This is a viewing aid, not RAW development.
 */
internal object FilmNegativeMath {
    /** Relative scene-linear light; a generic straight-line film model, not a stock calibration. */
    fun channel(encoded: Float, base: Float, settings: FilmNegativeSettings, channel: Int = 0): Float {
        val transmission = linearize(encoded).coerceAtLeast(1e-6)
        val reference = linearize(base).coerceAtLeast(1e-6)
        val rawDensity = -log10(transmission / reference)
        val density = (settings.colorCorrection?.apply(rawDensity, channel, settings.colorStrength) ?: rawDensity) +
            settings.alignment(channel).takeIf { it.isFinite() }?.coerceIn(-.5f, .5f).let { it ?: 0f }
        fun curved(light: Double) = settings.curve(channel).evaluate(light.coerceAtLeast(0.0).pow(1.0 / 2.2))
            .coerceAtLeast(0.0).pow(2.2).coerceAtMost(16.0).toFloat()
        if (settings.regionTone) {
            val span = (settings.whiteDensity - settings.blackDensity).coerceAtLeast(0.05f)
            return curved(((density - settings.blackDensity) / span).coerceIn(0.0, 4.0).pow(2.2))
        }
        settings.densityWindow?.let { window ->
            val low = window.low(channel) + settings.blackDensity
            val span = ((window.high(channel) - window.low(channel)) * settings.whiteDensity / 1.5f).coerceAtLeast(0.05f)
            return curved(((density - low) / span).coerceIn(0.0, 4.0).pow(2.2))
        }
        val span = (settings.whiteDensity - settings.blackDensity).coerceAtLeast(0.05f)
        val slope = settings.filmGamma.coerceIn(0.3f, 1.5f)
        val black = 10.0.pow(-span / slope.toDouble())
        val light = 10.0.pow(((density - settings.blackDensity - span) / slope).coerceAtMost(2.0))
        return curved(((light - black) / (1.0 - black)).coerceIn(0.0, 16.0))
    }

    fun lookup(settings: FilmNegativeSettings): ByteArray = ByteArray(256 * 3) { index ->
        // Cube-root log packing retains shadows as well as values above display white.
        (packLight(channel((index / 3) / 255f, settings.base(index % 3), settings, index % 3)) * 255f)
            .roundToInt().coerceIn(0, 255).toByte()
    }

    fun packLight(light: Float): Float = (ln(1.0 + light.coerceIn(0f, 16f)) / ln(17.0)).pow(1.0 / 3.0).toFloat()
    fun unpackLight(value: Float): Float = (17.0.pow(value.toDouble() * value * value) - 1.0).toFloat()

    fun sampleBase(rgba: ByteArray): FloatArray? {
        val channels = Array(3) { ArrayList<Int>() }
        for (offset in 0 until rgba.size - 3 step 4) {
            val rgb = IntArray(3) { rgba[offset + it].toInt() and 255 }
            // Sampling a hole or an almost-black frame cannot establish a useful film reference.
            if (rgb.any { it <= 2 || it >= 250 }) continue
            for (channel in 0..2) channels[channel].add(rgb[channel])
        }
        if (channels[0].size < max(16, ceil(rgba.size / 4 * 0.8).toInt())) return null
        val result = FloatArray(3) { channel ->
            val values = channels[channel].sorted()
            if (values[(values.size * 0.9).toInt().coerceAtMost(values.lastIndex)] -
                values[(values.size * 0.1).toInt()] > max(6f, values[values.size / 2] * 0.08f)) return null
            val middle = values.size / 2
            val median = if (values.size % 2 == 0) {
                (values[middle - 1] + values[middle]) * 0.5f
            } else values[middle].toFloat()
            median / 255f
        }
        // Check spatial consistency as well as a global median on the 16 x 16 sampling tile.
        if (rgba.size == 16 * 16 * 4) {
            for (qy in 0..1) for (qx in 0..1) for (c in 0..2) {
                val values = ArrayList<Int>()
                for (y in qy * 8 until qy * 8 + 8) for (x in qx * 8 until qx * 8 + 8) {
                    val offset = (y * 16 + x) * 4
                    if ((0..2).all { (rgba[offset + it].toInt() and 255) in 3..249 }) {
                        values.add(rgba[offset + c].toInt() and 255)
                    }
                }
                if (values.size < 48) return null
                values.sort()
                if (abs(values[values.size / 2] - result[c] * 255f) > max(6f, result[c] * 255f * 0.05f)) return null
            }
        }
        return result
    }

    fun linearize(value: Float): Double {
        val encoded = value.coerceIn(0f, 1f).toDouble()
        return if (encoded <= 0.04045) encoded / 12.92
        else ((encoded + 0.055) / 1.055).pow(2.4)
    }

    fun encode(linear: Double): Float = (if (linear <= 0.0031308) linear * 12.92
        else 1.055 * linear.pow(1.0 / 2.4) - 0.055).toFloat()

    /** CPU reference for the shader, used to verify exposure, neutral tones and gamut mapping. */
    fun displayColor(scene: FloatArray, settings: FilmNegativeSettings): FloatArray {
        var color = DoubleArray(3) { scene[it].toDouble().coerceAtLeast(0.0)
            .pow(1.0 / settings.gamma.coerceIn(0.3f, 3f)).coerceAtMost(1024.0) *
            2.0.pow(settings.exposureEv.coerceIn(-3f, 3f).toDouble()) }
        val shifts = doubleArrayOf(settings.warmth * 0.5 + settings.tint * 0.25,
            -settings.tint * 0.5, -settings.warmth * 0.5 + settings.tint * 0.25)
        color = DoubleArray(3) { color[it] * 2.0.pow(shifts[it]) }
        var gray = color[0] * 0.2126 + color[1] * 0.7152 + color[2] * 0.0722
        val saturation = if (settings.monochrome) 0.0 else settings.saturation.coerceIn(0f, 2f).toDouble()
        color = DoubleArray(3) { gray + (color[it] - gray) * saturation }
        if (abs(settings.contrast - 1f) > .0001f) {
            val level = (.18 * (gray.coerceAtLeast(0.0) / .18).pow(settings.contrast.coerceIn(.5f, 2f).toDouble())).coerceAtMost(1024.0)
            color = DoubleArray(3) { color[it] * level / max(gray, .0001) }
            gray = level
        }
        val mapped = if (gray <= 0.75) gray else 0.75 + 0.25 * (1 - exp(-(gray - 0.75) / 0.25))
        val scale = mapped / max(gray, 0.0001)
        color = DoubleArray(3) { color[it] * scale }
        gray = mapped
        var chroma = 1.0
        for (v in color) {
            if (v > 1.0) chroma = minOf(chroma, (1.0 - gray) / max(v - gray, 0.0001))
            if (v < 0.0) chroma = minOf(chroma, gray / max(gray - v, 0.0001))
        }
        return FloatArray(3) { encode((gray + (color[it] - gray) * chroma).coerceIn(0.0, 1.0)) }
    }
}
