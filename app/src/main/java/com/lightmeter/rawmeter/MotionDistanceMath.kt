package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal data class DistanceVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(v: DistanceVector) = DistanceVector(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: DistanceVector) = DistanceVector(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Double) = DistanceVector(x * s, y * s, z * s)
    fun dot(v: DistanceVector) = x * v.x + y * v.y + z * v.z
    fun norm() = sqrt(dot(this))
    fun unit() = this * (1.0 / norm())
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
    companion object { val ZERO = DistanceVector(0.0, 0.0, 0.0) }
}

internal data class DistanceRotation(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun times(q: DistanceRotation) = DistanceRotation(
        w*q.x + x*q.w + y*q.z - z*q.y,
        w*q.y - x*q.z + y*q.w + z*q.x,
        w*q.z + x*q.y - y*q.x + z*q.w,
        w*q.w - x*q.x - y*q.y - z*q.z,
    )
    fun inverse() = DistanceRotation(-x, -y, -z, w)
    fun rotate(v: DistanceVector): DistanceVector {
        val r = this * DistanceRotation(v.x, v.y, v.z, 0.0) * inverse()
        return DistanceVector(r.x, r.y, r.z)
    }
    companion object {
        val IDENTITY = DistanceRotation(0.0, 0.0, 0.0, 1.0)
        fun increment(omega: DistanceVector, seconds: Double): DistanceRotation {
            val angle = omega.norm() * seconds
            if (angle < 1e-12) return IDENTITY
            val v = omega.unit() * sin(angle / 2)
            return DistanceRotation(v.x, v.y, v.z, cos(angle / 2))
        }
    }
}

internal data class DistanceImuSample(
    val timestampNs: Long,
    val acceleration: DistanceVector,
    val angularVelocity: DistanceVector,
)

internal data class MetricMotionBaseline(
    val translation: DistanceVector,
    val currentToPrevious: DistanceRotation,
    val errorMeters: Double,
    val rotationRadians: Double,
)

/**
 * Short, visually stationary-to-stationary inertial baseline, not a general purpose VIO.
 * Gyro supplies orientation only. Metre scale comes from gravity-removed acceleration with
 * independently checked zero-velocity endpoints. Never bootstrap this scale from AF distance.
 */
internal object MotionDistanceMath {
    fun baseline(
        samples: List<DistanceImuSample>,
        sensorToCamera: DistanceRotation,
        stationaryEndpoints: Boolean,
    ): MetricMotionBaseline? {
        if (!stationaryEndpoints || samples.size < 20) return null
        val duration = (samples.last().timestampNs - samples.first().timestampNs) * 1e-9
        if (duration !in 0.25..1.5) return null
        var orientation = DistanceRotation.IDENTITY
        var velocity = DistanceVector.ZERO
        var displacement = DistanceVector.ZERO
        var rotation = 0.0
        var peakSpeed = 0.0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = (b.timestampNs - a.timestampNs) * 1e-9
            if (dt !in 0.001..0.04 || !a.acceleration.finite() || !b.acceleration.finite() ||
                !a.angularVelocity.finite() || !b.angularVelocity.finite()) return null
            val omega = (a.angularVelocity + b.angularVelocity) * 0.5
            val next = orientation * DistanceRotation.increment(omega, dt)
            val accel = (orientation.rotate(a.acceleration) + next.rotate(b.acceleration)) * 0.5
            if (accel.norm() > 8.0) return null
            displacement = displacement + velocity * dt + accel * (0.5 * dt * dt)
            velocity = velocity + accel * dt
            peakSpeed = max(peakSpeed, velocity.norm())
            rotation += omega.norm() * dt
            orientation = next
        }
        // Large rotation makes gravity removal, rolling shutter and lens/IMU lever arms unsafe.
        if (rotation > 0.035 || velocity.norm() > 0.10 || velocity.norm() / duration > 0.12 ||
            velocity.norm() > max(0.025, peakSpeed * 0.25)) return null
        // Constant residual acceleration bias is observable through the zero end velocity.
        displacement = displacement - velocity * (duration * 0.5)
        val length = displacement.norm()
        val error = 0.003 + 0.015 * duration * duration + 0.08 * length + 0.10 * rotation
        if (length !in 0.04..0.60 || error / length > 0.25) return null
        return MetricMotionBaseline(
            sensorToCamera.rotate(displacement),
            sensorToCamera * orientation * sensorToCamera.inverse(),
            error,
            rotation,
        )
    }

    data class RayMatch(val previous: DistanceVector, val current: DistanceVector)
    data class Depth(val meters: Double, val parallaxRadians: Double, val relativeError: Double)

    fun triangulate(match: RayMatch, baseline: MetricMotionBaseline, pixelAngularError: Double): Depth? {
        if (!pixelAngularError.isFinite() || pixelAngularError <= 0 || !baseline.translation.finite() ||
            !baseline.errorMeters.isFinite() || baseline.errorMeters <= 0) return null
        if (!match.previous.finite() || !match.current.finite() ||
            match.previous.norm() < 1e-9 || match.current.norm() < 1e-9) return null
        val first = match.previous.unit()
        val current = match.current.unit()
        val second = baseline.currentToPrevious.rotate(current)
        val cosine = first.dot(second).coerceIn(-1.0, 1.0)
        val angle = acos(cosine)
        if (angle !in 0.008..0.35 || angle < pixelAngularError * 5) return null
        val denominator = 1.0 - cosine * cosine
        val a = first.dot(baseline.translation)
        val b = second.dot(baseline.translation)
        val firstDistance = (a - cosine * b) / denominator
        val currentDistance = (cosine * a - b) / denominator
        if (firstDistance <= 0 || currentDistance !in 0.20..15.0) return null
        val separation = (first * firstDistance - baseline.translation - second * currentDistance).norm()
        if (separation > max(0.005, currentDistance * pixelAngularError * 2)) return null
        val transverse = (baseline.translation - second * second.dot(baseline.translation)).norm()
        if (transverse < 0.035) return null
        val relative = baseline.errorMeters / transverse + pixelAngularError / angle
        if (relative > 0.30) return null
        return Depth(currentDistance, angle, relative)
    }

    fun estimate(
        depths: List<Depth>, context: DistanceContext, timestampNs: Long, receivedAtNs: Long,
        trackFraction: Double,
    ): DistanceEstimate? {
        if (depths.size < 8 || !trackFraction.isFinite() || trackFraction !in 0.65..1.0 || depths.any {
                !it.meters.isFinite() || it.meters <= 0 || !it.parallaxRadians.isFinite() ||
                    it.parallaxRadians <= 0 || !it.relativeError.isFinite() || it.relativeError < 0
            }) return null
        val inverse = depths.map { 1.0 / it.meters }.sorted()
        val middle = median(inverse)
        val mad = median(inverse.map { abs(it - middle) }.sorted())
        if (mad / middle > 0.06) return null
        val inliers = depths.filter { abs(1.0 / it.meters - middle) <= max(0.03 * middle, 3 * mad) }
        if (inliers.size < 8 || inliers.size < depths.size * 0.8) return null
        val parallax = median(inliers.map { it.parallaxRadians }.sorted())
        val relative = max(0.08, median(inliers.map { it.relativeError }.sorted()) + 3 * mad / middle)
        if (relative > 0.30) return null
        val confidence = (trackFraction * (parallax / 0.025).coerceAtMost(1.0) *
            (1.0 - relative)).coerceIn(0.0, 0.85)
        val meters = 1.0 / middle
        return DistanceEstimate(
            meters, meters * (1 - relative), meters * (1 + relative), confidence,
            if (confidence >= 0.55) DistanceQuality.MEDIUM else DistanceQuality.LOW,
            DistanceSource.MOTION_PARALLAX, timestampNs, context.cameraIdentity, context.target,
            inliers.size, true, receivedAtNs = receivedAtNs,
        )
    }

    fun median(sorted: List<Double>): Double = if (sorted.size % 2 == 0)
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5 else sorted[sorted.size / 2]
}
