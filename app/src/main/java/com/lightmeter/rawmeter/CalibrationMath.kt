package com.lightmeter.rawmeter

import kotlin.math.PI
import kotlin.math.ln

enum class CalibrationReferenceMode {
    EV100,
    CAMERA_EXPOSURE,
    LUX_GRAY_CARD,
}

object CalibrationMath {
    const val GRAY_CARD_REFLECTANCE = 0.18
    const val REFLECTED_METER_CONSTANT_K = 12.5
    const val MAX_ABS_USER_CORRECTION_EV = 8.0

    fun ev100FromCameraExposure(
        aperture: Double,
        exposureSeconds: Double,
        iso: Double,
    ): Double {
        require(aperture > 0.0 && aperture.isFinite()) { "光圈必须大于 0" }
        require(exposureSeconds > 0.0 && exposureSeconds.isFinite()) {
            "快门时间必须大于 0"
        }
        require(iso > 0.0 && iso.isFinite()) { "ISO 必须大于 0" }
        return log2(100.0 * aperture * aperture / (exposureSeconds * iso))
    }

    fun ev100FromLuxOnGrayCard(lux: Double): Double {
        require(lux > 0.0 && lux.isFinite()) { "Lux 必须大于 0" }
        val grayCardLuminance = GRAY_CARD_REFLECTANCE * lux / PI
        return log2(100.0 * grayCardLuminance / REFLECTED_METER_CONSTANT_K)
    }

    fun updatedUserCorrection(
        currentCorrectionEv: Double,
        referenceEv100: Double,
        measuredEv100: Double,
    ): Double {
        require(referenceEv100.isFinite() && measuredEv100.isFinite()) {
            "校准 EV 必须是有限数值"
        }
        return (currentCorrectionEv + referenceEv100 - measuredEv100)
            .coerceIn(-MAX_ABS_USER_CORRECTION_EV, MAX_ABS_USER_CORRECTION_EV)
    }

    fun parseShutterSeconds(input: String): Double? {
        val normalized = input.trim()
            .lowercase()
            .replace("seconds", "")
            .replace("second", "")
            .replace("secs", "")
            .replace("sec", "")
            .replace("s", "")
            .replace("″", "")
            .replace("\"", "")
            .replace("÷", "/")
            .trim()
        if (normalized.isEmpty()) return null
        val slash = normalized.indexOf('/')
        return if (slash >= 0) {
            val numerator = normalized.substring(0, slash).trim().toDoubleOrNull() ?: return null
            val denominator = normalized.substring(slash + 1).trim().toDoubleOrNull() ?: return null
            if (denominator <= 0.0) null else numerator / denominator
        } else {
            normalized.toDoubleOrNull()
        }?.takeIf { it > 0.0 && it.isFinite() }
    }

    private fun log2(value: Double): Double = ln(value) / ln(2.0)
}
