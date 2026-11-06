package com.lightmeter.rawmeter

/** Exposure-matched quality evidence, never a scene measurement or calibration anchor. */
data class MeteringSignalEvidence(
    val cameraEv100: Double,
    val luma: Double,
    val clippedFraction: Double,
    val darkFraction: Double,
    val source: MeteringSource,
) {
    /** Mixed clipped/dark scenes have no unambiguous exposure recovery direction. */
    internal val recoveryStepEv: Double?
        get() = when {
            clippedFraction > .7 -> .8
            luma < .001 -> -.8
            clippedFraction > .05 && darkFraction <= .05 -> .8
            darkFraction > .05 && clippedFraction <= .05 -> -.8
            else -> null
        }

    internal companion object {
        fun from(stat: MeteringFrameStat, source: MeteringSource): MeteringSignalEvidence? {
            if (stat.evaluative?.baseReliable != false) return null
            val cameraEv = ExposurePreviewMath.cameraEv100(
                stat.aperture.toDouble(), stat.exposureTimeNs, stat.captureIso,
            ) ?: return null
            return MeteringSignalEvidence(cameraEv, stat.luma, stat.clipped, stat.darkFraction, source)
        }
    }
}
