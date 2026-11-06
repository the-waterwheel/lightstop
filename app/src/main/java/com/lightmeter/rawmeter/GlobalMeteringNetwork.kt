package com.lightmeter.rawmeter

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.tanh

internal data class GlobalMeteringPrediction(val compensationEv: Double, val errorScaleEv: Double)

/**
 * Deliberate over-exposure applied to the global model's final exposure, in EV.
 *
 * Negative film has strongly asymmetric latitude. Over-exposure keeps recording
 * separable highlight density well past the metered placement and is normally
 * recoverable in development or scanning; under-exposure starves the shadows of light
 * that no later stage can restore. Given that asymmetry, erring one stop bright costs a
 * negative far less than erring one stop dark, so the app raises the model's final
 * recommendation by this amount instead of reproducing the model's placement exactly.
 *
 * Scope is deliberately narrow. This touches only the exposure the global model
 * proposes. The scene EV reading, the statistical fallback policy, the RAW/YUV/ISP
 * metering calibration and the preview-execution calibration are separate domains and
 * stay untouched, so no calibration record can absorb or double-count this bias.
 *
 * Positive = brighter. Applied unconditionally, independent of the selected film stock.
 */
internal const val NEGATIVE_FILM_OVEREXPOSURE_EV = 1.0

/**
 * Target is tied to its own frame metadata, including when a shorter RAW is recaptured.
 *
 * [targetEv100] already carries [overexposureBiasEv]; [compensationEv] stays the raw
 * model output so it can still be compared against the trained network directly.
 */
internal data class GlobalMeteringExposure(
    val targetEv100: Double,
    val compensationEv: Double,
    val errorScaleEv: Double,
    val modelEpoch: Int = GlobalMeteringNetwork.EPOCH,
    /** Negative-film over-exposure folded into [targetEv100]; see [NEGATIVE_FILM_OVEREXPOSURE_EV]. */
    val overexposureBiasEv: Double = NEGATIVE_FILM_OVEREXPOSURE_EV,
) {
    companion object {
        fun from(cameraEv100: Double, prediction: GlobalMeteringPrediction) = GlobalMeteringExposure(
            targetEv100 = cameraEv100 - prediction.compensationEv - NEGATIVE_FILM_OVEREXPOSURE_EV,
            compensationEv = prediction.compensationEv,
            errorScaleEv = prediction.errorScaleEv,
        )
    }
}

/** Immutable weights; each call owns its ~4 KiB workspace, so camera sources can share the model. */
internal class GlobalMeteringNetwork private constructor(
    private val mean: FloatArray, private val std: FloatArray, private val uncertaintyFactor: Float,
    private val weights: List<FloatArray>, private val biases: List<FloatArray>,
) {
    fun predict(features: FloatArray): GlobalMeteringPrediction? {
        if (features.size != 776 || features.any { !it.isFinite() }) return null
        var values = FloatArray(776) { (features[it] - mean[it]) / std[it] }
        for (layer in 0..2) {
            val previous = values
            val width = previous.size
            values = FloatArray(biases[layer].size) { row ->
                var sum = biases[layer][row]
                val start = row * width
                for (column in 0 until width) sum += weights[layer][start + column] * previous[column]
                if (layer < 2) maxOf(0f, sum) else sum
            }
        }
        if (values.any { !it.isFinite() }) return null
        val compensation = 4.0 * tanh(values[0].toDouble() / 4.0)
        val scaleLogit = values[1].toDouble()
        val softplus = if (scaleLogit > 20) scaleLogit else ln1p(exp(scaleLogit))
        return GlobalMeteringPrediction(compensation, minOf(softplus + .05, 4.0) * uncertaintyFactor)
    }

    companion object {
        const val EPOCH = 219
        private const val BYTE_COUNT = 213740

        fun decode(bytes: ByteArray): GlobalMeteringNetwork {
            require(bytes.size == BYTE_COUNT) { "Incorrect model length" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8); buffer.get(magic)
            require(magic.contentEquals("LTMTRMLP".toByteArray(Charsets.US_ASCII)))
            for (expected in intArrayOf(1, EPOCH, 776, 64, 32, 2)) require(buffer.int == expected)
            fun floats(count: Int) = FloatArray(count) { buffer.float }.also { a -> require(a.all(Float::isFinite)) }
            val mean = floats(776); val std = floats(776); require(std.all { it > 0 })
            val factor = floats(1)[0]; require(factor > 0)
            val weights = mutableListOf<FloatArray>(); val biases = mutableListOf<FloatArray>()
            var previous = 776
            for (width in intArrayOf(64,32,2)) {
                weights += floats(width * previous); biases += floats(width); previous = width
            }
            require(!buffer.hasRemaining())
            return GlobalMeteringNetwork(mean, std, factor, weights, biases)
        }
    }
}
