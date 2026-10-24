package com.lightmeter.rawmeter

import kotlin.math.ln

/**
 * What the metering pipeline should do with the frames it already captured. The distinction
 * between random and systematic error matters: more frames reduce random noise but never a
 * systematic bias caused by saturated statistics.
 */
internal enum class RawMeteringQualityAction {
    ACCEPT,
    APPEND_SAME_EXPOSURE,
    CHANGE_EXPOSURE,
    REJECT,
}

internal data class RawQualityAssessment(
    val action: RawMeteringQualityAction,
    /** Spread of the fused luminance across frames, in EV; null when fewer than two frames. */
    val randomNoiseStops: Double?,
    /** True when the used channel statistics are pinned at the white level. */
    val systematicBias: Boolean,
)

/**
 * Pure RAW quality model. The base burst still follows [RawMeteringPolicy]; measured inter-frame
 * spread may request a bounded extra frame. This is not a single-frame early-accept model.
 *
 * Cost boundary: reuse the ROI statistics already produced by MeteringAnalysis/native sampling.
 * Do not add a full-frame scan, Bitmap, denoising or feature extraction to this decision.
 * A null inter-frame spread means insufficient evidence, not zero noise: ACCEPT for one valid
 * frame is NOT proof that its noise is low enough to replace the configured multi-frame burst.
 */
internal object RawMeteringQualityPolicy {
    /** Below this sample count the channel medians are not stable enough to trust. */
    const val MIN_SAMPLES = 16

    /** Inter-frame spread above this is treated as random noise that more frames can reduce. */
    const val RANDOM_NOISE_EV_THRESHOLD = 0.08

    fun assess(
        frameLumas: List<Double>,
        saturatedChannelCount: Int,
        sampleCount: Int,
        requiredSaturatedChannels: Int = RawExposureRetryPolicy.REQUIRED_SATURATED_CHANNELS,
    ): RawQualityAssessment {
        val systematicBias = saturatedChannelCount >= requiredSaturatedChannels
        val noise = frameNoiseStops(frameLumas)
        val action = when {
            sampleCount < MIN_SAMPLES -> RawMeteringQualityAction.REJECT
            systematicBias -> RawMeteringQualityAction.CHANGE_EXPOSURE
            noise != null && noise > RANDOM_NOISE_EV_THRESHOLD ->
                RawMeteringQualityAction.APPEND_SAME_EXPOSURE
            else -> RawMeteringQualityAction.ACCEPT
        }
        return RawQualityAssessment(
            action = action,
            randomNoiseStops = noise,
            systematicBias = systematicBias,
        )
    }

    /** Spread of the fused luminance across frames, in EV; null when fewer than two frames. */
    fun frameNoiseStops(frameLumas: List<Double>): Double? {
        val positiveFinite = frameLumas.filter { it.isFinite() && it > 0.0 }
        if (positiveFinite.size < 2) return null
        return ln(positiveFinite.max()) / ln(2.0) - ln(positiveFinite.min()) / ln(2.0)
    }

    /**
     * Whether one more same-exposure frame is worth capturing. Only random inter-frame noise can
     * be reduced by more frames; systematic bias needs a different exposure instead.
     */
    fun shouldAppendSameExposure(
        framesCaptured: Int,
        maxFrames: Int,
        noiseStops: Double?,
    ): Boolean = framesCaptured < maxFrames &&
        noiseStops != null &&
        noiseStops > RANDOM_NOISE_EV_THRESHOLD
}
