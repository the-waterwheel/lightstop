package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

internal data class FlashConfiguration(
    /** Guide-number value at [guideNumberReferenceIso] and full power. */
    val guideNumber: Double = 100.0,
    /** ISO at which the entered guide number is specified (for example GN100 or GN200). */
    val guideNumberReferenceIso: Int = 100,
    /** ISO used by the flash calculation; independent from the Normal-mode ISO dial after entry. */
    val iso: Int = 100,
    val powerDenominator: Int = 1,
    val lossStops: Double = 0.0,
    /** Null selects Camera2 autofocus distance. */
    val distanceMeters: Double? = null,
) {
    val isAutoDistance: Boolean get() = distanceMeters == null

    fun normalized(): FlashConfiguration = copy(
        guideNumber = guideNumber.takeIf { it.isFinite() }?.coerceIn(1.0, 1000.0) ?: 100.0,
        guideNumberReferenceIso = guideNumberReferenceIso.coerceIn(1, 102400),
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

/** One-third-exposure-stop distance detents from 0.2 m to 100 m, plus automatic distance. */
internal object FlashDistanceScale {
    const val minimumMeters = 0.20
    const val maximumMeters = 100.0
    private const val STEPS_PER_DISTANCE_DOUBLING = 6

    val meters: List<Double?> = listOf<Double?>(null) + buildList {
        var step = 0
        while (true) {
            val value = minimumMeters * 2.0.pow(step.toDouble() / STEPS_PER_DISTANCE_DOUBLING)
            if (value >= maximumMeters) break
            add(value)
            step += 1
        }
        add(maximumMeters)
    }

    fun nearestIndex(value: Double?): Int {
        if (value == null) return 0
        val safe = value.coerceIn(minimumMeters, maximumMeters)
        return meters.indices.drop(1).minByOrNull { index ->
            abs(log2(meters[index]!! / safe))
        } ?: 1
    }

    fun isMajorIndex(index: Int): Boolean =
        index == 0 || index == meters.lastIndex ||
            index > 0 && (index - 1) % STEPS_PER_DISTANCE_DOUBLING == 0

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
    val effectiveDistanceMeters: Double? = null,
    val isEstimatedDistance: Boolean = false,
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
            sqrt(
                meteringIso.coerceAtLeast(1).toDouble() /
                    configuration.guideNumberReferenceIso.coerceAtLeast(1).toDouble(),
            ) /
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
        distanceMeasurementState: DistanceMeasurementState? = null,
    ): FlashAdjustment {
        val ambient = ambientEv100?.takeIf(Double::isFinite)
            ?: return invalid(FlashAdjustmentStatus.INVALID)
        if (!exposureCompensationEv.isFinite()) return invalid(FlashAdjustmentStatus.INVALID)
        // Exposure compensation changes the target dose for flash and ambient together.
        val effectiveGuide = effectiveGuideNumber(configuration, meteringIso) *
            2.0.pow(-exposureCompensationEv / 2.0)
        val automaticEstimate = if (configuration.isAutoDistance && distanceMeasurementState != null) {
            distanceMeasurementState.estimateForFlash { first, second ->
                distanceExposureDifference(
                    first, second, effectiveGuide, meteringIso, ambient,
                    lockMode, lockedApertureStop, lockedShutterLogSeconds,
                )
            }
        } else null
        val distance = configuration.distanceMeters ?: if (distanceMeasurementState != null)
            automaticEstimate?.meters else autofocusDistanceMeters
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
                        effectiveDistanceMeters = distance,
                        isEstimatedDistance = automaticEstimate?.isApproximate == true,
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
            effectiveDistanceMeters = distance,
            isEstimatedDistance = automaticEstimate?.isApproximate == true,
        )
    }

    /** Compare uncapped exposure predictions, including the locked-aperture singularity.
     * Flash-only 2*log2 distance ratio remains the fallback when no exposure context exists.
     */
    internal fun distanceExposureDifference(
        first: Double, second: Double, effectiveGuide: Double, meteringIso: Int, ambientEv100: Double,
        lockMode: ExposureLockMode, apertureStop: Double, shutterLogSeconds: Double,
    ): Double {
        if (!first.isFinite() || !second.isFinite() || first <= 0 || second <= 0 ||
            !effectiveGuide.isFinite() || effectiveGuide <= 0 || !ambientEv100.isFinite()) return Double.POSITIVE_INFINITY
        val firstFlash = (effectiveGuide / first).pow(2)
        val secondFlash = (effectiveGuide / second).pow(2)
        return when (lockMode) {
            ExposureLockMode.SHUTTER -> {
                val ambient = 2.0.pow(shutterLogSeconds + ambientEv100) * meteringIso.coerceAtLeast(1) / 100.0
                if (!ambient.isFinite() || ambient <= 0) Double.POSITIVE_INFINITY
                else abs(log2((ambient + firstFlash) / (ambient + secondFlash)))
            }
            ExposureLockMode.APERTURE -> {
                val apertureSquared = 2.0.pow(apertureStop)
                val firstRemaining = 1 - firstFlash / apertureSquared
                val secondRemaining = 1 - secondFlash / apertureSquared
                if (firstRemaining <= DOMINANCE_EPSILON || secondRemaining <= DOMINANCE_EPSILON) {
                    // Both dominant can still disagree substantially about the amount of flash.
                    if (firstRemaining <= DOMINANCE_EPSILON && secondRemaining <= DOMINANCE_EPSILON)
                        DistanceFusionEngine.flashDifferenceEv(first, second) else Double.POSITIVE_INFINITY
                } else abs(log2(firstRemaining / secondRemaining))
            }
        }.takeIf { it.isFinite() } ?: Double.POSITIVE_INFINITY
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
