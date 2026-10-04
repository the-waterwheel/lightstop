package com.lightmeter.rawmeter

/** Sensor-clock samples only; no interpolation across gaps and no endpoint extrapolation. */
internal class DistanceImuHistory {
    private val history = ArrayDeque<DistanceImuSample>()

    @Synchronized fun clear() = history.clear()

    @Synchronized fun add(sample: DistanceImuSample) {
        if (!sample.acceleration.finite() || !sample.angularVelocity.finite() ||
            history.lastOrNull()?.let { sample.timestampNs <= it.timestampNs } == true) return
        history.addLast(sample)
        while (history.size > 400 || history.first().timestampNs < sample.timestampNs - 3_000_000_000L)
            history.removeFirst()
    }

    @Synchronized fun between(startNs: Long, endNs: Long): List<DistanceImuSample> {
        if (endNs <= startNs) return emptyList()
        val all = history.toList()
        val first = interpolate(all, startNs) ?: return emptyList()
        val last = interpolate(all, endNs) ?: return emptyList()
        return listOf(first) + all.filter { it.timestampNs > startNs && it.timestampNs < endNs } + last
    }

    @Synchronized fun quietAt(timestampNs: Long): Boolean {
        val recent = history.filter { it.timestampNs in (timestampNs - 180_000_000L)..timestampNs }
        return recent.size >= 8 && timestampNs - recent.last().timestampNs <= 25_000_000L &&
            recent.zipWithNext().all { (a, b) -> b.timestampNs - a.timestampNs <= 40_000_000L } &&
            recent.all { it.acceleration.norm() < 0.15 && it.angularVelocity.norm() < 0.015 }
    }

    @Synchronized fun focusReliability(timestampNs: Long): Double {
        val sample = history.lastOrNull { it.timestampNs <= timestampNs } ?: return 1.0
        if (timestampNs - sample.timestampNs > 40_000_000L) return 1.0
        val rotation = sample.angularVelocity.norm() / 0.15
        val acceleration = sample.acceleration.norm() / 1.2
        return (1.0 / (1.0 + rotation * rotation + acceleration * acceleration)).coerceIn(0.1, 1.0)
    }

    private fun interpolate(all: List<DistanceImuSample>, timestampNs: Long): DistanceImuSample? {
        val before = all.lastOrNull { it.timestampNs <= timestampNs } ?: return null
        val after = all.firstOrNull { it.timestampNs >= timestampNs } ?: return null
        if (before.timestampNs == after.timestampNs) return before
        if (after.timestampNs - before.timestampNs > 40_000_000L) return null
        val f = (timestampNs - before.timestampNs).toDouble() / (after.timestampNs - before.timestampNs)
        return DistanceImuSample(timestampNs,
            before.acceleration * (1-f) + after.acceleration * f,
            before.angularVelocity * (1-f) + after.angularVelocity * f)
    }
}
