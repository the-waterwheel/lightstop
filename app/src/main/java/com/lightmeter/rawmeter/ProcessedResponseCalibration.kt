package com.lightmeter.rawmeter

/** One measured point of a processed stream's luminance-to-correction response. */
data class ProcessedResponseAnchor(
    val inputLuma: Double,
    val correctionEv: Double,
)

/**
 * Multi-point response calibration for a processed (YUV/ISP) stream.
 *
 * A single EV offset cannot describe a vendor tone curve or local HDR, so this stores several
 * (luminance, correction) anchors and interpolates between them. It never changes RAW metering.
 */
data class ProcessedResponseCalibration(
    val anchors: List<ProcessedResponseAnchor>,
    /** Median absolute residual of the anchors, in EV; a rough trust indicator, not a guarantee. */
    val residualEv: Double = 0.0,
) {
    fun correctionEv(inputLuma: Double, fallbackEv: Double): Double {
        if (anchors.isEmpty()) return fallbackEv
        val sorted = anchors.sortedBy { it.inputLuma }
        if (sorted.size == 1 || !inputLuma.isFinite()) return sorted.first().correctionEv
        if (inputLuma <= sorted.first().inputLuma) return sorted.first().correctionEv
        if (inputLuma >= sorted.last().inputLuma) return sorted.last().correctionEv
        for (index in 0 until sorted.size - 1) {
            val left = sorted[index]
            val right = sorted[index + 1]
            if (inputLuma > right.inputLuma) continue
            val width = right.inputLuma - left.inputLuma
            if (width <= 1e-9) return right.correctionEv
            val fraction = (inputLuma - left.inputLuma) / width
            return left.correctionEv * (1.0 - fraction) + right.correctionEv * fraction
        }
        return sorted.last().correctionEv
    }

    /**
     * Adds or replaces an anchor within [MERGE_TOLERANCE] of an existing luminance and keeps the
     * list bounded and sorted. Repeated calibration under different lighting builds a real curve.
     */
    fun withAnchor(anchor: ProcessedResponseAnchor): ProcessedResponseCalibration {
        if (!anchor.inputLuma.isFinite() || !anchor.correctionEv.isFinite()) return this
        val merged = anchors
            .filterNot { kotlin.math.abs(it.inputLuma - anchor.inputLuma) <= MERGE_TOLERANCE }
            .plus(anchor)
            .sortedBy { it.inputLuma }
            .let { if (it.size > MAX_ANCHORS) it.takeLast(MAX_ANCHORS) else it }
        return ProcessedResponseCalibration(merged, residualOf(merged))
    }

    fun serialize(): String = buildString {
        append("%.6f".format(java.util.Locale.US, residualEv))
        anchors.sortedBy { it.inputLuma }.forEach { anchor ->
            append(';')
            append("%.6f".format(java.util.Locale.US, anchor.inputLuma))
            append(':')
            append("%.6f".format(java.util.Locale.US, anchor.correctionEv))
        }
    }

    companion object {
        const val MERGE_TOLERANCE = 0.05
        const val MAX_ANCHORS = 5

        fun parse(value: String?): ProcessedResponseCalibration? {
            if (value.isNullOrBlank()) return null
            val parts = value.split(';')
            val residual = parts.firstOrNull()?.toDoubleOrNull() ?: 0.0
            val anchors = parts.drop(1).mapNotNull { part ->
                val pair = part.split(':')
                if (pair.size != 2) return@mapNotNull null
                val luma = pair[0].toDoubleOrNull() ?: return@mapNotNull null
                val correction = pair[1].toDoubleOrNull() ?: return@mapNotNull null
                if (!luma.isFinite() || !correction.isFinite()) return@mapNotNull null
                ProcessedResponseAnchor(luma, correction)
            }
            return ProcessedResponseCalibration(anchors, residual).takeIf { it.anchors.isNotEmpty() }
        }

        private fun residualOf(anchors: List<ProcessedResponseAnchor>): Double {
            if (anchors.size < 3) return 0.0
            val corrections = anchors.map { it.correctionEv }.sorted()
            val median = corrections[corrections.size / 2]
            val deviations = corrections.map { kotlin.math.abs(it - median) }.sorted()
            return deviations[deviations.size / 2]
        }
    }
}
