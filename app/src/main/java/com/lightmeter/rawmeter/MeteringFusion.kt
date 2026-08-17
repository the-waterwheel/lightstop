package com.lightmeter.rawmeter

/**
 * Robustly fuses per-frame measurements into the value shown to the user.
 *
 * Keeping fusion independent of Camera2 makes the adaptive RAW-frame policy easy to test without a
 * device. Median EV/luma reject a transient frame while clipped fraction remains an average.
 */
internal object MeteringFusion {
    fun fuse(stats: List<MeteringFrameStat>, source: MeteringSource): MeterReading? {
        if (stats.isEmpty()) return null
        val representative = stats[stats.size / 2]
        return MeterReading(
            sceneEv100 = median(stats.map(MeteringFrameStat::ev100).sorted()),
            rawLuma = median(stats.map(MeteringFrameStat::luma).sorted()),
            clippedFraction = stats.map(MeteringFrameStat::clipped).average(),
            frameCount = stats.size,
            captureIso = representative.captureIso,
            exposureTimeNs = representative.exposureTimeNs,
            aperture = representative.aperture,
            source = source,
        )
    }

    private fun median(sorted: List<Double>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) * 0.5
        } else {
            sorted[middle]
        }
    }
}
