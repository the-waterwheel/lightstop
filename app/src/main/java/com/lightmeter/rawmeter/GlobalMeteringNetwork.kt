package com.lightmeter.rawmeter

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.tanh

internal data class GlobalMeteringPrediction(val compensationEv: Double, val errorScaleEv: Double)

/** Target is tied to its own frame metadata, including when a shorter RAW is recaptured. */
internal data class GlobalMeteringExposure(
    val targetEv100: Double,
    val compensationEv: Double,
    val errorScaleEv: Double,
    val modelEpoch: Int = GlobalMeteringNetwork.EPOCH,
) {
    companion object {
        fun from(cameraEv100: Double, prediction: GlobalMeteringPrediction) = GlobalMeteringExposure(
            cameraEv100 - prediction.compensationEv, prediction.compensationEv, prediction.errorScaleEv,
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
