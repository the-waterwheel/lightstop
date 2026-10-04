package com.lightmeter.rawmeter

/** Source and confidence of the last meter reading, for a persistent UI hint. */
data class ReadingQuality(
    val sourceLabel: String?,
    val lowConfidence: Boolean,
)

/**
 * Pure classification of a reading's source and trustworthiness. It is deliberately conservative:
 * a non-RAW source is always labelled, and clipping or a very weak signal is flagged so the user
 * can re-meter instead of trusting a distorted value.
 */
object ReadingQualityPolicy {
    /** ROI clipping above this fraction makes the fused luminance unreliable. */
    const val CLIPPED_FRACTION_LIMIT = 0.05

    /** Fused luma below this is treated as a weak, noisy signal. */
    const val WEAK_LUMA_LIMIT = 0.002

    fun evaluate(reading: MeterReading?): ReadingQuality {
        val value = reading ?: return ReadingQuality(sourceLabel = null, lowConfidence = false)
        val label = when (value.source) {
            MeteringSource.RAW -> "RAW"
            MeteringSource.YUV_PREVIEW -> "YUV"
            MeteringSource.ISP_PREVIEW -> "ISP"
        }
        val lowConfidence = value.source != MeteringSource.RAW ||
            value.clippedFraction > CLIPPED_FRACTION_LIMIT ||
            !value.rawLuma.isFinite() ||
            value.rawLuma < WEAK_LUMA_LIMIT
        return ReadingQuality(sourceLabel = label, lowConfidence = lowConfidence)
    }
}
