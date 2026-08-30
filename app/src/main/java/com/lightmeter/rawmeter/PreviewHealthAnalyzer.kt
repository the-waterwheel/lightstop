package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal enum class PreviewStripeDirection { HORIZONTAL, VERTICAL }

internal enum class PreviewHealthState { WARMING_UP, HEALTHY, SUSPECT, FAILED }

internal enum class PreviewHealthReason {
    GREEN_DOMINANT,
    MOSTLY_BLACK,
    FROZEN_FRAME,
    HORIZONTAL_STRIPES,
    VERTICAL_STRIPES,
}

internal data class PreviewHealthMetrics(
    val meanLuma: Double,
    val lumaStandardDeviation: Double,
    val darkFraction: Double,
    val greenDominance: Double,
    val saturatedGreenFraction: Double,
    val horizontalStripeScore: Double,
    val verticalStripeScore: Double,
    val perceptualHash: Long,
)

internal data class PreviewHealthDecision(
    val state: PreviewHealthState,
    val reason: PreviewHealthReason? = null,
)

/**
 * Pure, bounded analysis for a downsampled ARGB preview frame. Green, black and frozen-looking
 * images begin as suspects because real scenes can have those properties. Only an almost uniform,
 * highly saturated green output or repeated high-contrast stripes can fail automatically.
 */
internal object PreviewHealthAnalyzer {
    fun analyzeArgb(width: Int, height: Int, pixels: IntArray): PreviewHealthMetrics? {
        if (width < HASH_EDGE || height < HASH_EDGE || pixels.size < width * height) return null
        val rowMeans = DoubleArray(height)
        val columnMeans = DoubleArray(width)
        var lumaSum = 0.0
        var squaredLumaSum = 0.0
        var redSum = 0.0
        var greenSum = 0.0
        var blueSum = 0.0
        var dark = 0
        var saturatedGreen = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val red = (color shr 16 and 0xff) / 255.0
                val green = (color shr 8 and 0xff) / 255.0
                val blue = (color and 0xff) / 255.0
                val luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue
                lumaSum += luma
                squaredLumaSum += luma * luma
                redSum += red
                greenSum += green
                blueSum += blue
                rowMeans[y] += luma
                columnMeans[x] += luma
                if (luma <= DARK_LUMA) dark += 1
                if (green >= SATURATED_GREEN_MINIMUM &&
                    green - max(red, blue) >= SATURATED_GREEN_SEPARATION
                ) {
                    saturatedGreen += 1
                }
            }
        }
        val count = (width * height).toDouble()
        rowMeans.indices.forEach { rowMeans[it] /= width }
        columnMeans.indices.forEach { columnMeans[it] /= height }
        val meanGreen = greenSum / count
        val meanLuma = lumaSum / count
        val lumaVariance = (squaredLumaSum / count - meanLuma * meanLuma).coerceAtLeast(0.0)
        return PreviewHealthMetrics(
            meanLuma = meanLuma,
            lumaStandardDeviation = sqrt(lumaVariance),
            darkFraction = dark / count,
            greenDominance = meanGreen - (redSum + blueSum) / (2.0 * count),
            saturatedGreenFraction = saturatedGreen / count,
            horizontalStripeScore = stripeScore(rowMeans),
            verticalStripeScore = stripeScore(columnMeans),
            perceptualHash = perceptualHash(width, height, pixels, meanLuma),
        )
    }

    private fun stripeScore(values: DoubleArray): Double {
        if (values.size < 8) return 0.0
        val mean = values.average()
        val neighborDelta = (1 until values.size)
            .map { index -> abs(values[index] - values[index - 1]) }
            .average()
        val periodicDelta = PERIODS.map { period ->
            values.indices.drop(period).map { index -> abs(values[index] - values[index - period]) }
                .average()
        }.minOrNull() ?: 0.0
        val oddEven = abs(
            values.filterIndexed { index, _ -> index % 2 == 0 }.average() -
                values.filterIndexed { index, _ -> index % 2 == 1 }.average(),
        )
        val normalization = max(mean, 0.05)
        val periodicity = neighborDelta / (periodicDelta + 0.01)
        return max(oddEven / normalization, periodicity * neighborDelta / normalization)
    }

    private fun perceptualHash(width: Int, height: Int, pixels: IntArray, meanLuma: Double): Long {
        var hash = 0L
        for (cellY in 0 until HASH_EDGE) {
            val y = (cellY * height / HASH_EDGE).coerceIn(0, height - 1)
            for (cellX in 0 until HASH_EDGE) {
                val x = (cellX * width / HASH_EDGE).coerceIn(0, width - 1)
                val color = pixels[y * width + x]
                val luma = 0.2126 * (color shr 16 and 0xff) / 255.0 +
                    0.7152 * (color shr 8 and 0xff) / 255.0 +
                    0.0722 * (color and 0xff) / 255.0
                if (luma >= meanLuma) hash = hash or (1L shl (cellY * HASH_EDGE + cellX))
            }
        }
        return hash
    }

    private const val DARK_LUMA = 0.025
    private const val SATURATED_GREEN_MINIMUM = 0.55
    private const val SATURATED_GREEN_SEPARATION = 0.45
    private const val HASH_EDGE = 8
    private val PERIODS = intArrayOf(2, 4, 8)
}

/** Camera-thread-neutral consecutive-frame policy for [PreviewHealthAnalyzer] metrics. */
internal class PreviewHealthMonitor {
    private var observedFrames = 0
    private var stripeDirection: PreviewStripeDirection? = null
    private var stripeStreak = 0
    private var previousHash: Long? = null
    private var frozenStreak = 0
    private var flatGreenStreak = 0

    fun observe(metrics: PreviewHealthMetrics, timestampNs: Long): PreviewHealthDecision {
        observedFrames += 1
        if (observedFrames <= WARMUP_FRAMES) return PreviewHealthDecision(PreviewHealthState.WARMING_UP)

        val direction = when {
            metrics.horizontalStripeScore >= STRIPE_SCORE &&
                metrics.horizontalStripeScore > metrics.verticalStripeScore * 1.15 ->
                PreviewStripeDirection.HORIZONTAL
            metrics.verticalStripeScore >= STRIPE_SCORE &&
                metrics.verticalStripeScore > metrics.horizontalStripeScore * 1.15 ->
                PreviewStripeDirection.VERTICAL
            else -> null
        }
        stripeStreak = if (direction != null && direction == stripeDirection) stripeStreak + 1 else 1
        stripeDirection = direction
        if (direction == null) stripeStreak = 0
        if (stripeStreak >= STRIPE_FAILURE_FRAMES) {
            return PreviewHealthDecision(
                PreviewHealthState.FAILED,
                if (direction == PreviewStripeDirection.HORIZONTAL) {
                    PreviewHealthReason.HORIZONTAL_STRIPES
                } else {
                    PreviewHealthReason.VERTICAL_STRIPES
                },
            )
        }

        val flatGreenOutput = metrics.greenDominance >= GREEN_DOMINANCE &&
            metrics.saturatedGreenFraction >= FLAT_GREEN_PIXEL_FRACTION &&
            metrics.lumaStandardDeviation <= FLAT_GREEN_LUMA_DEVIATION
        flatGreenStreak = if (flatGreenOutput) flatGreenStreak + 1 else 0
        if (flatGreenStreak >= FLAT_GREEN_FAILURE_FRAMES) {
            return PreviewHealthDecision(
                PreviewHealthState.FAILED,
                PreviewHealthReason.GREEN_DOMINANT,
            )
        }

        frozenStreak = if (timestampNs > 0L && previousHash == metrics.perceptualHash) {
            frozenStreak + 1
        } else {
            0
        }
        previousHash = metrics.perceptualHash
        val suspect = when {
            metrics.greenDominance >= GREEN_DOMINANCE -> PreviewHealthReason.GREEN_DOMINANT
            metrics.darkFraction >= DARK_FRACTION -> PreviewHealthReason.MOSTLY_BLACK
            frozenStreak >= FROZEN_SUSPECT_FRAMES -> PreviewHealthReason.FROZEN_FRAME
            else -> null
        }
        return if (suspect != null) {
            PreviewHealthDecision(PreviewHealthState.SUSPECT, suspect)
        } else {
            PreviewHealthDecision(PreviewHealthState.HEALTHY)
        }
    }

    fun reset() {
        observedFrames = 0
        stripeDirection = null
        stripeStreak = 0
        previousHash = null
        frozenStreak = 0
        flatGreenStreak = 0
    }

    private companion object {
        private const val WARMUP_FRAMES = 3
        private const val STRIPE_SCORE = 1.3
        private const val STRIPE_FAILURE_FRAMES = 6
        private const val GREEN_DOMINANCE = 0.45
        private const val FLAT_GREEN_PIXEL_FRACTION = 0.985
        private const val FLAT_GREEN_LUMA_DEVIATION = 0.025
        private const val FLAT_GREEN_FAILURE_FRAMES = 10
        private const val DARK_FRACTION = 0.985
        private const val FROZEN_SUSPECT_FRAMES = 6
    }
}
