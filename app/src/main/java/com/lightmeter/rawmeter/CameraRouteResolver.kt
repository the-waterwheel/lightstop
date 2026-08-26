package com.lightmeter.rawmeter

/**
 * Chooses the hardware route for a catalog entry without treating multi-camera sensor
 * synchronization as a capability gate. Synchronization matters only when two physical sensors
 * are used together; a request for one physical output is valid on both CALIBRATED and
 * APPROXIMATE devices.
 */
internal object CameraRouteResolver {
    fun candidates(
        descriptor: CameraDescriptor,
        forceLogicalFallback: Boolean,
    ): List<CameraRouteCandidate> {
        val logical = CameraRouteCandidate(
            logicalCameraId = descriptor.logicalCameraId,
            physicalCameraId = null,
            isLogicalFallback = descriptor.physicalCameraId != null,
        )
        val requestedPhysical = descriptor.physicalCameraId ?: return listOf(logical.copy(
            isLogicalFallback = false,
        ))
        val physical = CameraRouteCandidate(
            logicalCameraId = descriptor.logicalCameraId,
            physicalCameraId = requestedPhysical,
            isLogicalFallback = false,
        )
        return if (forceLogicalFallback) listOf(logical) else listOf(physical, logical)
    }
}

/** A route candidate is intentionally independent from stream profile and output-size probing. */
internal data class CameraRouteCandidate(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val isLogicalFallback: Boolean,
)
