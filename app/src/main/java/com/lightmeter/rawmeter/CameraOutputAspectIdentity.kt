package com.lightmeter.rawmeter

/** Resolves the preference key without mixing stale runtime lens metadata into a new selection. */
internal object CameraOutputAspectIdentity {
    fun resolve(
        cameraId: String,
        selectedCameraId: String,
        lensRole: CameraLensRole?,
        runtimeCameraId: String,
        activePhysicalCameraId: String?,
        runtimeCalibrationCameraId: String,
    ): String = if (
        cameraId == selectedCameraId &&
        runtimeCameraId == cameraId &&
        lensRole == CameraLensRole.AUTOMATIC &&
        activePhysicalCameraId != null
    ) {
        runtimeCalibrationCameraId
    } else {
        cameraId
    }
}
