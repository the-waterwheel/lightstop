package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

internal data class Matrix3(private val values: DoubleArray) {
    init {
        require(values.size == 9)
    }

    operator fun times(vector: DoubleArray): DoubleArray {
        require(vector.size == 3)
        return DoubleArray(3) { row ->
            values[row * 3] * vector[0] +
                values[row * 3 + 1] * vector[1] +
                values[row * 3 + 2] * vector[2]
        }
    }

    operator fun times(other: Matrix3): Matrix3 = Matrix3(
        DoubleArray(9) { index ->
            val row = index / 3
            val column = index % 3
            (0..2).sumOf { values[row * 3 + it] * other.values[it * 3 + column] }
        },
    )

    fun interpolate(other: Matrix3, fraction: Double): Matrix3 {
        val t = fraction.coerceIn(0.0, 1.0)
        return Matrix3(DoubleArray(9) { values[it] + (other.values[it] - values[it]) * t })
    }

}

internal data class SensorColorCalibration(
    val firstMatrix: Matrix3,
    val firstIlluminantKelvin: Int,
    val secondMatrix: Matrix3? = null,
    val secondIlluminantKelvin: Int? = null,
)

internal enum class ColorTemperatureConfidence { HIGH, MEDIUM, LOW }

internal data class ColorTemperatureReading(
    val kelvin: Int,
    val confidence: ColorTemperatureConfidence,
    val fitError: Double,
    val clippedFraction: Double,
    val sampleLevel: Double,
)

/** Pure color-temperature matching math, isolated from Camera2 and the UI for unit testing. */
internal object ColorTemperatureMath {
    const val MIN_KELVIN = 1_800
    const val MAX_KELVIN = 15_000

    fun estimate(
        sample: RawColorSample,
        calibration: SensorColorCalibration,
    ): ColorTemperatureReading? {
        val minimum = minOf(sample.red, sample.green, sample.blue)
        val maximum = maxOf(sample.red, sample.green, sample.blue)
        val level = (sample.red + sample.green + sample.blue) / 3.0
        if (minimum <= 0.002 || maximum >= 0.995 || level !in 0.012..0.97) return null

        val measured = normalizedLogChromaticity(
            doubleArrayOf(sample.red, sample.green, sample.blue),
        ) ?: return null
        var bestKelvin = MIN_KELVIN
        var bestError = Double.POSITIVE_INFINITY
        var kelvin = MIN_KELVIN
        while (kelvin <= MAX_KELVIN) {
            val predictedSensor = matrixFor(kelvin, calibration) * whitePointXyz(kelvin)
            val predicted = normalizedLogChromaticity(predictedSensor)
            if (predicted != null) {
                val error = sqrt(
                    (measured[0] - predicted[0]).pow(2) +
                        (measured[1] - predicted[1]).pow(2),
                )
                if (error < bestError) {
                    bestError = error
                    bestKelvin = kelvin
                }
            }
            kelvin += SEARCH_STEP_KELVIN
        }
        if (!bestError.isFinite()) return null
        val rounded = ((bestKelvin + 25) / 50) * 50
        val exposurePenalty = when {
            sample.clippedFraction > 0.01 || level !in 0.035..0.88 -> 1
            else -> 0
        }
        val baseConfidence = when {
            bestError <= 0.045 -> 2
            bestError <= 0.11 -> 1
            else -> 0
        }
        val confidence = when ((baseConfidence - exposurePenalty).coerceAtLeast(0)) {
            2 -> ColorTemperatureConfidence.HIGH
            1 -> ColorTemperatureConfidence.MEDIUM
            else -> ColorTemperatureConfidence.LOW
        }
        return ColorTemperatureReading(
            kelvin = rounded.coerceIn(MIN_KELVIN, MAX_KELVIN),
            confidence = confidence,
            fitError = bestError,
            clippedFraction = sample.clippedFraction,
            sampleLevel = level,
        )
    }

    fun whitePointXyz(kelvin: Int): DoubleArray {
        val t = kelvin.coerceIn(1_667, 25_000).toDouble()
        val x = if (t <= 4_000.0) {
            when {
                t <= 2_222.0 ->
                    -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) +
                        0.8776956e3 / t + 0.179910
                else ->
                    -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) +
                        0.2226347e3 / t + 0.240390
            }
        } else {
            val daylightX = if (t <= 7_000.0) {
                -4.6070e9 / t.pow(3) + 2.9678e6 / t.pow(2) + 0.09911e3 / t + 0.244063
            } else {
                -2.0064e9 / t.pow(3) + 1.9018e6 / t.pow(2) + 0.24748e3 / t + 0.237040
            }
            daylightX
        }
        val y = if (t <= 4_000.0) {
            when {
                t <= 2_222.0 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
                else -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
            }
        } else {
            -3.0 * x * x + 2.87 * x - 0.275
        }
        return doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)
    }

    private fun matrixFor(kelvin: Int, calibration: SensorColorCalibration): Matrix3 {
        val second = calibration.secondMatrix ?: return calibration.firstMatrix
        val secondKelvin = calibration.secondIlluminantKelvin ?: return calibration.firstMatrix
        val firstMired = 1_000_000.0 / calibration.firstIlluminantKelvin.coerceAtLeast(1)
        val secondMired = 1_000_000.0 / secondKelvin.coerceAtLeast(1)
        if (abs(firstMired - secondMired) < 1e-9) return calibration.firstMatrix
        val targetMired = 1_000_000.0 / kelvin.coerceAtLeast(1)
        val fraction = (targetMired - firstMired) / (secondMired - firstMired)
        return calibration.firstMatrix.interpolate(second, fraction)
    }

    private fun normalizedLogChromaticity(rgb: DoubleArray): DoubleArray? {
        if (rgb.size != 3 || rgb.any { !it.isFinite() || it <= 1e-8 }) return null
        return doubleArrayOf(ln(rgb[0] / rgb[1]), ln(rgb[2] / rgb[1]))
    }

    private const val SEARCH_STEP_KELVIN = 10
}
