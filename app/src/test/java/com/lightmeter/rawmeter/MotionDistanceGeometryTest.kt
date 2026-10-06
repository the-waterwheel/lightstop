package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class MotionDistanceGeometryTest {
    private val geometry = MotionDistanceGeometry(400.0, 420.0, 256.0, 192.0, 2.0,
        DistanceRotation.IDENTITY, 0.5f)

    @Test fun calibratedIntrinsicsProduceCentreRayAndAccountForSkew() {
        assertEquals(DistanceVector(0.0, 0.0, 1.0), geometry.ray(256.0, 192.0))
        val ray = geometry.ray(296.2, 234.0)
        assertEquals(0.1, ray.x, 1e-9)
        assertEquals(0.1, ray.y, 1e-9)
    }

    @Test fun focusZoomPrincipalPointAndMountChangesBreakParallaxWindow() {
        assertTrue(geometry.compatible(geometry.copy()))
        assertFalse(geometry.compatible(geometry.copy(focusDiopters=0.6f)))
        assertFalse(geometry.compatible(geometry.copy(fx=405.0)))
        assertFalse(geometry.compatible(geometry.copy(cx=257.0)))
        assertFalse(geometry.compatible(geometry.copy(sensorToCamera=
            DistanceRotation.increment(DistanceVector(0.0, 1.0, 0.0), 0.01))))
    }
}
