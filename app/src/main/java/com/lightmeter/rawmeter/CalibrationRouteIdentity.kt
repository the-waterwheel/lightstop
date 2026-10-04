package com.lightmeter.rawmeter

/** Stable user-selected route identity; unlike active physical metadata it survives session opens. */
internal object CalibrationRouteIdentity {
    fun resolve(selectedCameraId: String, requestedCameraId: String?): String =
        selectedCameraId.ifBlank { requestedCameraId.orEmpty() }.ifBlank { "0" }
}

/** Route identity plus optional concrete physical lens reported by a logical-camera HAL. */
internal data class CalibrationCaptureIdentity(
    val routeId: String,
    val activePhysicalCameraId: String?,
) {
    fun conflictsWith(other: CalibrationCaptureIdentity): Boolean =
        routeId != other.routeId ||
            activePhysicalCameraId != null && other.activePhysicalCameraId != null &&
            activePhysicalCameraId != other.activePhysicalCameraId

    fun withReportedPhysicalFrom(other: CalibrationCaptureIdentity): CalibrationCaptureIdentity =
        if (activePhysicalCameraId == null && routeId == other.routeId) {
            copy(activePhysicalCameraId = other.activePhysicalCameraId)
        } else {
            this
        }
}
