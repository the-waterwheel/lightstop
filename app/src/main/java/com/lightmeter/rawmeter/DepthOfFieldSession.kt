package com.lightmeter.rawmeter

import kotlin.math.abs

/** Mutable calculator state kept outside the View so later tools can reuse the same UI shell. */
internal class DepthOfFieldSession {
    var selectedFormat: FrameFormat = FrameFormat.ALL.first()
        private set
    var circleOfConfusionMm: Double = 0.03
        private set
    var selectedAperture: Double = 5.6
        private set
    var focusIndex: Int = defaultFocusIndex()
        private set
    var fullFrameEquivalentMm: Double = 50.0
        private set
    var result: DepthOfFieldResult? = null
        private set
    var initialized: Boolean = false
        private set

    fun reset(
        frameFormat: FrameFormat,
        aperture: Double,
        fullFrameEquivalentMm: Double,
    ) {
        selectedFormat = frameFormat
        circleOfConfusionMm = DepthOfFieldMath.recommendedCircleOfConfusionMm(frameFormat)
        selectedAperture = aperture
        focusIndex = defaultFocusIndex()
        this.fullFrameEquivalentMm = fullFrameEquivalentMm.coerceAtLeast(1.0)
        initialized = true
        recalculate()
    }

    fun updateFullFrameEquivalent(value: Double) {
        fullFrameEquivalentMm = value.coerceAtLeast(1.0)
        recalculate()
    }

    fun selectFrame(format: FrameFormat) {
        selectedFormat = format
        recalculate()
    }

    fun selectCircleOfConfusion(value: Double) {
        circleOfConfusionMm = value.coerceIn(
            DepthOfFieldMath.MIN_COC_MM,
            DepthOfFieldMath.MAX_COC_MM,
        )
        recalculate()
    }

    fun selectApertureIndex(index: Int, values: DoubleArray): Boolean {
        if (values.isEmpty()) return false
        val nextValue = values[index.coerceIn(0, values.lastIndex)]
        if (abs(selectedAperture - nextValue) < 0.0001) return false
        selectedAperture = nextValue
        recalculate()
        return true
    }

    fun selectFocusIndex(index: Int): Boolean {
        val next = index.coerceIn(0, DepthOfFieldMath.focusDistancesM.lastIndex)
        if (focusIndex == next) return false
        focusIndex = next
        recalculate()
        return true
    }

    fun apertureIndex(values: DoubleArray): Int =
        values.indices.minByOrNull { abs(values[it] - selectedAperture) } ?: 0

    fun focusDistanceM(): Double = DepthOfFieldMath.focusDistancesM[focusIndex]

    fun focalLengthMm(): Double = DepthOfFieldMath.focalLengthForFormat(
        selectedFormat,
        fullFrameEquivalentMm,
    ).coerceAtLeast(1.0)

    private fun recalculate() {
        result = DepthOfFieldMath.calculate(
            focalLengthMm = focalLengthMm(),
            aperture = selectedAperture,
            circleOfConfusionMm = circleOfConfusionMm,
            focusDistanceM = focusDistanceM(),
        )
    }

    private companion object {
        fun defaultFocusIndex(): Int =
            DepthOfFieldMath.focusDistancesM.indexOfFirst { it == 2.0 }.coerceAtLeast(0)
    }
}
