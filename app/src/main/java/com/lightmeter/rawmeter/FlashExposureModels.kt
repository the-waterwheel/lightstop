package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

internal data class FlashConfiguration(
    /** Guide number in metres at ISO 100 and full power. */
    val guideNumber: Double = 100.0,
    /** Editing/default ISO. Applied metering follows the Normal-mode ISO dial. */
    val iso: Int = 100,
    val powerDenominator: Int = 1,
    val lossStops: Double = 0.0,
    /** Null selects Camera2 autofocus distance. */
    val distanceMeters: Double? = null,
) {
    val isAutoDistance: Boolean get() = distanceMeters == null

    fun normalized(): FlashConfiguration = copy(
        guideNumber = guideNumber.takeIf { it.isFinite() }?.coerceIn(1.0, 1000.0) ?: 100.0,
        iso = iso.coerceIn(1, 102400),
        powerDenominator = powerDenominator.takeIf { it in FlashPowerScale.denominators } ?: 1,
        lossStops = lossStops.takeIf { it.isFinite() }?.coerceIn(0.0, 20.0) ?: 0.0,
        distanceMeters = distanceMeters
            ?.takeIf { it.isFinite() }
            ?.coerceIn(FlashDistanceScale.minimumMeters, FlashDistanceScale.maximumMeters),
    )
}

internal object FlashPowerScale {
    val denominators = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256)

    fun label(denominator: Int): String = if (denominator <= 1) "1/1" else "1/$denominator"
}

/** Equal detents with deliberately non-linear values: fine close-up control, broad far range. */
internal object FlashDistanceScale {
    val meters: List<Double?> = listOf(
        null,
        0.20, 0.25, 0.30, 0.35, 0.40, 0.50, 0.60, 0.70, 0.80, 1.0,
        1.2, 1.5, 1.8, 2.0, 2.5, 3.0, 4.0, 5.0, 7.0, 10.0,
        15.0, 20.0, 30.0, 50.0, 75.0, 100.0,
    )
    const val minimumMeters = 0.20
    const val maximumMeters = 100.0

    fun nearestIndex(value: Double?): Int {
        if (value == null) return 0
        return meters.indices.drop(1).minByOrNull { index -> abs(meters[index]!! - value) } ?: 1
    }

    fun label(value: Double?): String = when {
        value == null -> "Auto"
        value < 1.0 -> "%.2fm".format(java.util.Locale.US, value)
        value < 10.0 -> "%.1fm".format(java.util.Locale.US, value)
        else -> "%.0fm".format(java.util.Locale.US, value)
    }
}

internal enum class FlashAdjustmentStatus {
    APPLIED,
    DISTANCE_UNAVAILABLE,
    FLASH_DOMINATES,
    INVALID,
}

internal data class FlashAdjustment(
    val compensationStops: Double,
    val effectiveGuideNumber: Double?,
    val flashOnlyAperture: Double?,
    val status: FlashAdjustmentStatus,
)

/**
 * Adds ambient and flash exposure in the linear domain.
 *
 * At a locked shutter, stopping down affects both sources, so N² = Nambient² + Nflash².
 * At a locked aperture, flash dose is fixed and the remaining ambient fraction is 1 - Fflash.
 */
internal object FlashExposureMath {
    fun effectiveGuideNumber(configuration: FlashConfiguration, meteringIso: Int): Double {
        val value = configuration.guideNumber *
            sqrt(meteringIso.coerceAtLeast(1) / 100.0) /
            sqrt(configuration.powerDenominator.coerceAtLeast(1).toDouble()) *
            2.0.pow(-configuration.lossStops / 2.0)
        return value.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    }

    fun adjustment(
        configuration: FlashConfiguration,
        autofocusDistanceMeters: Double?,
        meteringIso: Int,
        ambientEv100: Double?,
        exposureCompensationEv: Double = 0.0,
        lockMode: ExposureLockMode,
        lockedApertureStop: Double,
        lockedShutterLogSeconds: Double,
    ): FlashAdjustment {
        val ambient = ambientEv100?.takeIf(Double::isFinite)
            ?: return invalid(FlashAdjustmentStatus.INVALID)
        if (!exposureCompensationEv.isFinite()) return invalid(FlashAdjustmentStatus.INVALID)
        // Exposure compensation changes the target dose for flash and ambient together.
        val effectiveGuide = effectiveGuideNumber(configuration, meteringIso) *
            2.0.pow(-exposureCompensationEv / 2.0)
        val distance = configuration.distanceMeters ?: autofocusDistanceMeters
        if (distance == null || !distance.isFinite() || distance <= 0.0) {
            if (distance == Double.POSITIVE_INFINITY) {
                return FlashAdjustment(
                    compensationStops = 0.0,
                    effectiveGuideNumber = effectiveGuide,
                    flashOnlyAperture = 0.0,
                    status = FlashAdjustmentStatus.APPLIED,
                )
            }
            return invalid(FlashAdjustmentStatus.DISTANCE_UNAVAILABLE)
        }
        if (effectiveGuide <= 0.0) return invalid(FlashAdjustmentStatus.INVALID)
        val flashAperture = effectiveGuide / distance
        val ambientAtIso = ambient + log2(meteringIso.coerceAtLeast(1) / 100.0)
        val compensation = when (lockMode) {
            ExposureLockMode.SHUTTER -> {
                val shutter = 2.0.pow(lockedShutterLogSeconds)
                val ambientApertureSquared = shutter * 2.0.pow(ambientAtIso)
                if (!ambientApertureSquared.isFinite() || ambientApertureSquared <= 0.0) {
                    return invalid(FlashAdjustmentStatus.INVALID)
                }
                log2(1.0 + flashAperture * flashAperture / ambientApertureSquared)
            }
            ExposureLockMode.APERTURE -> {
                val aperture = 2.0.pow(lockedApertureStop / 2.0)
                val flashFraction = (flashAperture / aperture).pow(2.0)
                if (flashFraction >= 1.0 - DOMINANCE_EPSILON) {
                    return FlashAdjustment(
                        // No shutter value can subtract flash dose at a locked aperture.
                        // Keep the meter on its ambient value and surface an explicit warning.
                        compensationStops = 0.0,
                        effectiveGuideNumber = effectiveGuide,
                        flashOnlyAperture = flashAperture,
                        status = FlashAdjustmentStatus.FLASH_DOMINATES,
                    )
                }
                -log2(1.0 - flashFraction.coerceAtLeast(0.0))
            }
        }
        if (!compensation.isFinite() || compensation < 0.0) {
            return invalid(FlashAdjustmentStatus.INVALID)
        }
        return FlashAdjustment(
            compensationStops = compensation.coerceAtMost(MAX_DISPLAY_COMPENSATION_STOPS),
            effectiveGuideNumber = effectiveGuide,
            flashOnlyAperture = flashAperture,
            status = FlashAdjustmentStatus.APPLIED,
        )
    }

    private fun invalid(status: FlashAdjustmentStatus) = FlashAdjustment(
        compensationStops = 0.0,
        effectiveGuideNumber = null,
        flashOnlyAperture = null,
        status = status,
    )

    private const val DOMINANCE_EPSILON = 1e-6
    private const val MAX_DISPLAY_COMPENSATION_STOPS = 12.0
}
