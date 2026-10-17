package com.lightmeter.rawmeter

/** Per-frame EV statistics, before any photographic exposure preference. */
internal data class EvaluativeFrameStats(
    val baseEv100: Double,
    val highlightEv100: Double,
    val baseReliable: Boolean,
    val highlightReliable: Boolean,
    val highlightClipped: Boolean = false,
)

/** Automatic adjustment stays separate from the measured scene and user compensation. */
data class EvaluativeMeteringResult(
    val baseEv100: Double,
    val highlightEv100: Double?,
    val exposureReductionEv: Double,
    val highlightResidualEv: Double,
) {
    val recommendedEv100: Double get() = baseEv100 + exposureReductionEv
    val highlightsConfirmed: Boolean get() = highlightEv100 != null
}

/** Fixed, data-free exposure policy. Inputs are area-weighted local EVs, not encoded pixels. */
internal object EvaluativeMeteringAlgorithm {
    const val BASE_LOW = 0.05
    const val BASE_HIGH = 0.95
    const val HIGHLIGHT_LOW = 0.98
    const val HIGHLIGHT_HIGH = 0.995
    const val HIGHLIGHT_HEADROOM_EV = 2.5
    const val MAX_REDUCTION_EV = 0.5
    const val SIGNAL_FLOOR = 0.000001

    fun summarize(
        samples: DoubleArray,
        clippedFraction: Double,
        darkFraction: Double,
    ): EvaluativeFrameStats? {
        if (samples.isEmpty() || samples.any { !it.isFinite() } ||
            clippedFraction !in 0.0..1.0 || darkFraction !in 0.0..1.0
        ) return null
        samples.sort()
        return EvaluativeFrameStats(
            baseEv100 = intervalMean(samples, BASE_LOW, BASE_HIGH),
            highlightEv100 = intervalMean(samples, HIGHLIGHT_LOW, HIGHLIGHT_HIGH),
            // Keep censored area in the distribution; never renormalize it out of the frame.
            baseReliable = clippedFraction <= 1.0 - BASE_HIGH + EPSILON &&
                darkFraction <= BASE_LOW + EPSILON,
            highlightReliable = clippedFraction <= 1.0 - HIGHLIGHT_HIGH + EPSILON &&
                darkFraction <= HIGHLIGHT_LOW + EPSILON,
            highlightClipped = clippedFraction > 1.0 - HIGHLIGHT_HIGH + EPSILON,
        )
    }

    /** Exact fractional integration over sorted, equally represented areas. */
    fun intervalMean(sorted: DoubleArray, low: Double, high: Double): Double {
        require(sorted.isNotEmpty() && low >= 0.0 && high <= 1.0 && low < high)
        val begin = low * sorted.size
        val end = high * sorted.size
        var sum = 0.0
        for (index in begin.toInt() until kotlin.math.ceil(end).toInt()) {
            val area = (minOf(end, index + 1.0) - maxOf(begin, index.toDouble())).coerceAtLeast(0.0)
            sum += sorted[index.coerceAtMost(sorted.lastIndex)] * area
        }
        return sum / (end - begin)
    }

    fun decide(baseEv100: Double, highlightEv100: Double?): EvaluativeMeteringResult {
        require(baseEv100.isFinite() && (highlightEv100 == null || highlightEv100.isFinite()))
        val reduction = highlightEv100?.let {
            (it - baseEv100 - HIGHLIGHT_HEADROOM_EV).coerceIn(0.0, MAX_REDUCTION_EV)
        } ?: 0.0
        return EvaluativeMeteringResult(
            baseEv100, highlightEv100, reduction,
            highlightEv100?.let { (it - baseEv100 - reduction - HIGHLIGHT_HEADROOM_EV).coerceAtLeast(0.0) }
                ?: 0.0,
        )
    }

    private const val EPSILON = 1e-9
}

internal object EvaluativeMeteringFusion {
    /** A shorter RAW exposure can recover highlights without replacing a good original base. */
    fun recoverBase(current: MeteringFrameStat, reference: MeteringFrameStat?, preferReference: Boolean = false): MeteringFrameStat {
        val currentStats = current.evaluative ?: return current
        val previous = reference?.evaluative ?: return current
        if ((currentStats.baseReliable && !preferReference) || !previous.baseReliable) return current
        return reference.copy(
            clipped = current.clipped,
            sampleCount = current.sampleCount,
            evaluative = currentStats.copy(baseEv100 = previous.baseEv100, baseReliable = true),
        )
    }

    fun fuse(stats: List<MeteringFrameStat>, source: MeteringSource): MeterReading? {
        if (stats.isEmpty() || stats.any { it.evaluative == null }) return null
        val bases = stats.filter { it.evaluative!!.baseReliable }
        if (bases.isEmpty()) return null
        val base = median(bases.map { it.evaluative!!.baseEv100 })
        val highlights = stats.mapNotNull { it.evaluative!!.takeIf { e -> e.highlightReliable }?.highlightEv100 }
        val decision = EvaluativeMeteringAlgorithm.decide(base, highlights.takeIf { it.isNotEmpty() }?.let(::median))
        val representative = bases[bases.size / 2]
        return MeterReading(
            sceneEv100 = base,
            rawLuma = median(bases.map { it.luma }),
            clippedFraction = stats.map { it.clipped }.average(),
            frameCount = stats.size,
            captureIso = representative.captureIso,
            exposureTimeNs = representative.exposureTimeNs,
            aperture = representative.aperture,
            source = source,
            // This photographic recommendation must never become a source-calibration anchor.
            calibrationSample = null,
            evaluative = decision,
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
}
