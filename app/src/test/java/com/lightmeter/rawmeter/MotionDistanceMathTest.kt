package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class MotionDistanceMathTest {
    private val context = DistanceContext(1, "0", true)
    private fun trajectory(rotation: Double = 0.0, drift: Double = 0.0): List<DistanceImuSample> =
        (0..120).map { i ->
            val acceleration = when { i < 50 -> 0.8; i < 100 -> -0.8; else -> 0.0 }
            DistanceImuSample(1_000_000_000L+i*10_000_000L,
                DistanceVector(acceleration+drift, 0.0, 0.0), DistanceVector(0.0, rotation, 0.0))
        }

    @Test fun boundedStationaryToStationaryAccelerationProvidesMetreScale() {
        val result = MotionDistanceMath.baseline(trajectory(), DistanceRotation.IDENTITY, true)!!
        assertEquals(0.2, result.translation.x, 0.01)
        assertTrue(result.errorMeters > 0.03)
        assertNull(MotionDistanceMath.baseline(trajectory(), DistanceRotation.IDENTITY, false))
    }

    @Test fun gyroRotationWithoutTranslationCannotProduceDistance() {
        val samples = trajectory(0.02).map { it.copy(acceleration=DistanceVector.ZERO) }
        assertNull(MotionDistanceMath.baseline(samples, DistanceRotation.IDENTITY, true))
    }

    @Test fun fastRotationSensorGapsAndBiasDriftAreRejected() {
        assertNull(MotionDistanceMath.baseline(trajectory(0.1), DistanceRotation.IDENTITY, true))
        assertNull(MotionDistanceMath.baseline(trajectory(drift=0.3), DistanceRotation.IDENTITY, true))
        assertNull(MotionDistanceMath.baseline(trajectory().filterIndexed { i, _ -> i !in 50..60 },
            DistanceRotation.IDENTITY, true))
        assertNull(MotionDistanceMath.baseline(trajectory().map { it.copy(timestampNs=it.timestampNs*3) },
            DistanceRotation.IDENTITY, true))
    }

    @Test fun triangulationRecoversCurrentRayDistanceAndCompensatesRotation() {
        val rotation = DistanceRotation.increment(DistanceVector(0.0, 0.01, 0.0), 1.0)
        val translation = DistanceVector(0.2, 0.0, 0.0)
        val point = DistanceVector(0.0, 0.0, 2.0)
        val current = rotation.inverse().rotate(point-translation)
        val baseline = MetricMotionBaseline(translation, rotation, 0.01, 0.01)
        val depth = MotionDistanceMath.triangulate(MotionDistanceMath.RayMatch(point, current), baseline, 0.001)!!
        assertEquals(current.norm(), depth.meters, 1e-9)
    }

    @Test fun insufficientParallaxWrongMotionAndBehindCameraAreRejected() {
        val baseline = MetricMotionBaseline(DistanceVector(0.2, 0.0, 0.0), DistanceRotation.IDENTITY, 0.01, 0.0)
        val forward = DistanceVector(0.0, 0.0, 1.0)
        assertNull(MotionDistanceMath.triangulate(MotionDistanceMath.RayMatch(forward, forward), baseline, 0.001))
        assertNull(MotionDistanceMath.triangulate(MotionDistanceMath.RayMatch(forward, DistanceVector(0.1, 0.0, 1.0)), baseline, 0.001))
        assertNull(MotionDistanceMath.triangulate(MotionDistanceMath.RayMatch(forward, DistanceVector(-0.1, 0.1, 1.0)), baseline, 0.001))
    }

    @Test fun strongerParallaxIncreasesConfidenceAndMixedForegroundBackgroundIsRejected() {
        fun depths(angle: Double) = List(12) { MotionDistanceMath.Depth(2.0+it*0.001, angle, 0.12) }
        val strong = MotionDistanceMath.estimate(depths(0.04), context, 100, 100, 0.95)!!
        val weak = MotionDistanceMath.estimate(depths(0.01), context, 100, 100, 0.95)!!
        assertTrue(strong.confidence > weak.confidence)
        assertEquals(DistanceSource.MOTION_PARALLAX, strong.source)
        assertNull(MotionDistanceMath.estimate(depths(0.04).take(4), context, 100, 100, 0.95))
        val mixed = depths(0.04).take(6) + List(6) { MotionDistanceMath.Depth(4.0, 0.04, 0.12) }
        assertNull(MotionDistanceMath.estimate(mixed, context, 100, 100, 0.95))
    }

    @Test fun coordinateRotationPreservesMetricBaseline() {
        val rotation = DistanceRotation.increment(DistanceVector(0.0, 0.0, 1.0), Math.PI/2)
        val baseline = MotionDistanceMath.baseline(trajectory(), rotation, true)!!
        assertEquals(0.0, baseline.translation.x, 1e-9)
        assertEquals(0.2, baseline.translation.y, 0.01)
    }
}
