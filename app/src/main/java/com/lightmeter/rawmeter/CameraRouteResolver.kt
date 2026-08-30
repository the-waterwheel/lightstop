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
    ): List<CameraRouteCandidate> {
        val logical = CameraRouteCandidate(
            cameraIdToOpen = descriptor.logicalCameraId,
            physicalCameraId = null,
            kind = if (descriptor.physicalCameraId == null) {
                CameraRouteKind.LOGICAL_AUTO
            } else {
                CameraRouteKind.LOGICAL_FALLBACK
            },
        )
        val requestedPhysical = descriptor.physicalCameraId ?: return listOf(logical)
        val direct = descriptor.directCameraId?.let { directCameraId ->
            CameraRouteCandidate(
                cameraIdToOpen = directCameraId,
                physicalCameraId = null,
                kind = CameraRouteKind.PUBLIC_DIRECT,
            )
        }
        val physical = CameraRouteCandidate(
            cameraIdToOpen = descriptor.logicalCameraId,
            physicalCameraId = requestedPhysical,
            kind = CameraRouteKind.FIXED_PHYSICAL,
        )
        return buildList {
            direct?.let(::add)
            add(physical)
            add(logical)
        }
    }
}

/** A route candidate is intentionally independent from stream profile and output-size probing. */
internal data class CameraRouteCandidate(
    /** Camera id passed to CameraManager.openCamera. */
    val cameraIdToOpen: String,
    val physicalCameraId: String?,
    val kind: CameraRouteKind,
) {
    val isLogicalFallback: Boolean get() = kind == CameraRouteKind.LOGICAL_FALLBACK
}

internal enum class CameraRouteKind {
    LOGICAL_AUTO,
    PUBLIC_DIRECT,
    FIXED_PHYSICAL,
    LOGICAL_FALLBACK,
}
