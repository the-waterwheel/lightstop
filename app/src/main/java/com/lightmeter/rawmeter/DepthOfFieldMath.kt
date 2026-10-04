package com.lightmeter.rawmeter

import kotlin.math.exp
import kotlin.math.ln

data class DepthOfFieldResult(
    val hyperfocalDistanceM: Double,
    val nearLimitM: Double,
    /** `null` represents infinity. */
    val farLimitM: Double?,
) {
    val totalDepthM: Double?
        get() = farLimitM?.minus(nearLimitM)
}

/** Thin-lens depth-of-field calculations. All public distance values are in metres. */
object DepthOfFieldMath {
    const val MIN_COC_MM = 0.001
    const val MAX_COC_MM = 1.0
    const val MIN_FRAME_EDGE_MM = 1.0
    const val MAX_FRAME_EDGE_MM = 300.0
    const val MIN_FOCUS_DISTANCE_M = 0.1
    const val MAX_FOCUS_DISTANCE_M = 100.0

    val commonCircleOfConfusionMm = doubleArrayOf(
        0.010,
        0.015,
        0.020,
        0.025,
        0.030,
        0.045,
        0.050,
        0.100,
    )

    val focusDistancesM = doubleArrayOf(
        0.10, 0.12, 0.15, 0.18, 0.20, 0.25, 0.30, 0.35, 0.40, 0.50,
        0.60, 0.70, 0.80, 1.0, 1.2, 1.5, 2.0, 2.5, 3.0, 4.0,
        5.0, 7.0, 10.0, 15.0, 20.0, 30.0, 50.0, 100.0,
        Double.POSITIVE_INFINITY,
    )

    /** Common marked focal lengths; the dial may still start on the camera's exact value. */
    val commonFocalLengthsMm = doubleArrayOf(
        8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 21.0, 24.0, 28.0,
        35.0, 40.0, 45.0, 50.0, 55.0, 65.0, 75.0, 85.0, 90.0, 100.0,
        105.0, 120.0, 135.0, 150.0, 180.0, 200.0, 250.0, 300.0, 400.0,
        500.0, 600.0, 800.0,
    )

    /** Traditional diagonal/1500 approximation, yielding about 0.029 mm for 135 film. */
    fun recommendedCircleOfConfusionMm(format: FrameFormat): Double =
        (format.diagonalMm / 1500.0).coerceIn(MIN_COC_MM, MAX_COC_MM)

    fun calculate(
        focalLengthMm: Double,
        aperture: Double,
        circleOfConfusionMm: Double,
        focusDistanceM: Double,
    ): DepthOfFieldResult? {
        if (!focalLengthMm.isFinite() || focalLengthMm <= 0.0 ||
            !aperture.isFinite() || aperture <= 0.0 ||
            !circleOfConfusionMm.isFinite() ||
            circleOfConfusionMm !in MIN_COC_MM..MAX_COC_MM ||
            focusDistanceM.isNaN() || focusDistanceM <= 0.0
        ) return null

        val hyperfocalMm = focalLengthMm * focalLengthMm /
            (aperture * circleOfConfusionMm) + focalLengthMm
        if (focusDistanceM == Double.POSITIVE_INFINITY) {
            return DepthOfFieldResult(
                hyperfocalDistanceM = hyperfocalMm / 1000.0,
                nearLimitM = hyperfocalMm / 1000.0,
                farLimitM = null,
            )
        }
        val focusDistanceMm = focusDistanceM * 1000.0
        if (focusDistanceMm <= focalLengthMm) return null
        val subjectOffsetMm = focusDistanceMm - focalLengthMm
        val nearDenominator = hyperfocalMm + subjectOffsetMm
        if (nearDenominator <= 0.0) return null
        val nearMm = hyperfocalMm * focusDistanceMm / nearDenominator
        val farDenominator = hyperfocalMm - subjectOffsetMm
        val farMm = if (farDenominator <= 0.0) {
            null
        } else {
            hyperfocalMm * focusDistanceMm / farDenominator
        }
        return DepthOfFieldResult(
            hyperfocalDistanceM = hyperfocalMm / 1000.0,
            nearLimitM = nearMm / 1000.0,
            farLimitM = farMm?.div(1000.0),
        )
    }

    /** Logarithmic mapping used by both the focus dial and the distance ruler. */
    fun distanceFraction(distanceM: Double?): Double {
        if (distanceM == null || distanceM >= MAX_FOCUS_DISTANCE_M) return 1.0
        val value = distanceM.coerceIn(MIN_FOCUS_DISTANCE_M, MAX_FOCUS_DISTANCE_M)
        return (ln(value / MIN_FOCUS_DISTANCE_M) /
            ln(MAX_FOCUS_DISTANCE_M / MIN_FOCUS_DISTANCE_M)).coerceIn(0.0, 1.0)
    }

    /** Inverse of [distanceFraction], used for direct manipulation of the ruler. */
    fun distanceForFraction(fraction: Double): Double {
        val value = fraction.coerceIn(0.0, 1.0)
        if (value >= 0.997) return Double.POSITIVE_INFINITY
        return MIN_FOCUS_DISTANCE_M * exp(
            value * ln(MAX_FOCUS_DISTANCE_M / MIN_FOCUS_DISTANCE_M),
        )
    }

    fun focalLengthForFormat(
        format: FrameFormat,
        fullFrameEquivalentMm: Double,
    ): Double = fullFrameEquivalentMm * format.diagonalMm / FULL_FRAME_DIAGONAL_MM

    fun customFormat(widthMm: Double, heightMm: Double): FrameFormat? {
        if (!widthMm.isFinite() || !heightMm.isFinite() ||
            widthMm !in MIN_FRAME_EDGE_MM..MAX_FRAME_EDGE_MM ||
            heightMm !in MIN_FRAME_EDGE_MM..MAX_FRAME_EDGE_MM
        ) return null
        val label = "${formatDimension(widthMm)}×${formatDimension(heightMm)} mm"
        return FrameFormat("custom", label, widthMm, heightMm, label)
    }

    private fun formatDimension(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

    private const val FULL_FRAME_DIAGONAL_MM = 43.266615
}
