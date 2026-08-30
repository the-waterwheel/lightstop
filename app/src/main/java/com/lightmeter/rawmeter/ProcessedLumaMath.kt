package com.lightmeter.rawmeter

import kotlin.math.max
import kotlin.math.pow

/** Code-value range carried by an 8-bit YUV stream. Camera2 defaults to full-range JFIF. */
internal enum class YuvCodeRange {
    FULL,
    LIMITED,
}

/** Luma/chroma matrix used to reconstruct nonlinear RGB from a YUV_420_888 pixel. */
internal data class YuvColorEncoding(
    val range: YuvCodeRange = YuvCodeRange.FULL,
    val redLumaCoefficient: Double = 0.299,
    val blueLumaCoefficient: Double = 0.114,
) {
    val greenLumaCoefficient: Double
        get() = 1.0 - redLumaCoefficient - blueLumaCoefficient
}

/** A normalized input/output curve reported by Camera2's tonemap result metadata. */
internal data class ProcessedToneCurve(val points: DoubleArray)

internal sealed interface ProcessedTransfer {
    data object LINEAR : ProcessedTransfer
    data object SRGB : ProcessedTransfer
    data object REC709 : ProcessedTransfer
    data class GAMMA(val value: Double) : ProcessedTransfer
    data class CURVES(
        val red: ProcessedToneCurve,
        val green: ProcessedToneCurve,
        val blue: ProcessedToneCurve,
    ) : ProcessedTransfer
}

internal data class EncodedRgb(
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    val clipped: Boolean
        get() = red >= CLIP_LEVEL || green >= CLIP_LEVEL || blue >= CLIP_LEVEL

    private companion object {
        private const val CLIP_LEVEL = 0.98
    }
}

/**
 * Shared output-to-linear conversion for both ISP bitmaps and YUV metering frames.
 *
 * RAW samples are already normalized in the sensor-linear domain. Processed camera outputs are
 * not: their RGB channels have passed through Camera2's tonemap and an output transfer function.
 * This decoder uses the reported per-channel curve when it is usable, then falls back to the
 * standard transfer requested/reported by Camera2. A lookup table keeps region sampling bounded.
 */
internal class ProcessedLumaDecoder(
    private val transfer: ProcessedTransfer = ProcessedTransfer.SRGB,
) {
    private val redLookup = buildLookup(channel = 0)
    private val greenLookup = buildLookup(channel = 1)
    private val blueLookup = buildLookup(channel = 2)

    fun linearLuma(red: Double, green: Double, blue: Double): Double =
        0.2126 * lookup(redLookup, red) +
            0.7152 * lookup(greenLookup, green) +
            0.0722 * lookup(blueLookup, blue)

    private fun buildLookup(channel: Int): DoubleArray {
        val validatedCurve = (transfer as? ProcessedTransfer.CURVES)
            ?.curveFor(channel)
            ?.points
            ?.takeIf(::isUsableCurve)
        return DoubleArray(LOOKUP_LAST_INDEX + 1) { index ->
            val encoded = index.toDouble() / LOOKUP_LAST_INDEX
            when {
                validatedCurve != null -> invertCurve(validatedCurve, encoded)
                transfer == ProcessedTransfer.LINEAR -> encoded
                transfer == ProcessedTransfer.REC709 -> rec709ToLinear(encoded)
                transfer is ProcessedTransfer.GAMMA &&
                    transfer.value.isFinite() && transfer.value > 0.0 ->
                    encoded.pow(transfer.value)
                else -> srgbToLinear(encoded)
            }.coerceIn(0.0, 1.0)
        }
    }

    private fun lookup(table: DoubleArray, encoded: Double): Double {
        val coordinate = encoded.coerceIn(0.0, 1.0) * LOOKUP_LAST_INDEX
        val lower = coordinate.toInt().coerceIn(0, LOOKUP_LAST_INDEX)
        val upper = (lower + 1).coerceAtMost(LOOKUP_LAST_INDEX)
        val fraction = coordinate - lower
        return table[lower] * (1.0 - fraction) + table[upper] * fraction
    }

    private companion object {
        private const val LOOKUP_LAST_INDEX = 1024
    }
}

/** Pure YUV/JFIF conversion and robust region helpers, independently testable without Camera2. */
internal object ProcessedLumaMath {
    fun yuvToEncodedRgb(
        yCode: Int,
        uCode: Int,
        vCode: Int,
        encoding: YuvColorEncoding,
    ): EncodedRgb {
        val y = when (encoding.range) {
            YuvCodeRange.FULL -> yCode.coerceIn(0, 255) / 255.0
            YuvCodeRange.LIMITED -> ((yCode.coerceIn(0, 255) - 16.0) / 219.0)
                .coerceIn(0.0, 1.0)
        }
        val chromaScale = if (encoding.range == YuvCodeRange.LIMITED) 224.0 else 255.0
        val cb = (uCode.coerceIn(0, 255) - 128.0) / chromaScale
        val cr = (vCode.coerceIn(0, 255) - 128.0) / chromaScale
        val kr = encoding.redLumaCoefficient
        val kb = encoding.blueLumaCoefficient
        val kg = encoding.greenLumaCoefficient
        if (kr <= 0.0 || kb <= 0.0 || kg <= 0.0) return EncodedRgb(y, y, y)
        val red = y + 2.0 * (1.0 - kr) * cr
        val blue = y + 2.0 * (1.0 - kb) * cb
        val green = (y - kr * red - kb * blue) / kg
        return EncodedRgb(
            red = red.coerceIn(0.0, 1.0),
            green = green.coerceIn(0.0, 1.0),
            blue = blue.coerceIn(0.0, 1.0),
        )
    }

    fun median(values: DoubleArray, size: Int = values.size): Double? {
        if (size <= 0 || size > values.size) return null
        values.sort(0, size)
        val middle = size / 2
        return if (size % 2 == 0) {
            (values[middle - 1] + values[middle]) * 0.5
        } else {
            values[middle]
        }
    }
}

private fun ProcessedTransfer.CURVES.curveFor(channel: Int): ProcessedToneCurve = when (channel) {
    0 -> red
    1 -> green
    else -> blue
}

private fun isUsableCurve(points: DoubleArray): Boolean {
    if (points.size < 4 || points.size % 2 != 0) return false
    var previousInput = Double.NEGATIVE_INFINITY
    var previousOutput = Double.NEGATIVE_INFINITY
    for (index in points.indices step 2) {
        val input = points[index]
        val output = points[index + 1]
        if (!input.isFinite() || !output.isFinite() ||
            input !in 0.0..1.0 || output !in 0.0..1.0 ||
            input <= previousInput || output < previousOutput
        ) {
            return false
        }
        previousInput = input
        previousOutput = output
    }
    return points[0] <= CURVE_ENDPOINT_EPSILON &&
        points[points.lastIndex - 1] >= 1.0 - CURVE_ENDPOINT_EPSILON
}

private fun invertCurve(points: DoubleArray, encoded: Double): Double {
    val target = encoded.coerceIn(0.0, 1.0)
    var low = 0.0
    var high = 1.0
    repeat(24) {
        val middle = (low + high) * 0.5
        if (evaluateCurve(points, middle) < target) low = middle else high = middle
    }
    return (low + high) * 0.5
}

private fun evaluateCurve(points: DoubleArray, input: Double): Double {
    val value = input.coerceIn(0.0, 1.0)
    for (index in 0 until points.size - 2 step 2) {
        val leftInput = points[index]
        val rightInput = points[index + 2]
        if (value > rightInput) continue
        val leftOutput = points[index + 1]
        val rightOutput = points[index + 3]
        val width = max(rightInput - leftInput, 1e-9)
        val fraction = ((value - leftInput) / width).coerceIn(0.0, 1.0)
        return leftOutput * (1.0 - fraction) + rightOutput * fraction
    }
    return points.last()
}

private fun srgbToLinear(value: Double): Double =
    if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

private fun rec709ToLinear(value: Double): Double =
    if (value < 0.081) value / 4.5 else ((value + 0.099) / 1.099).pow(1.0 / 0.45)

private const val CURVE_ENDPOINT_EPSILON = 1e-4
