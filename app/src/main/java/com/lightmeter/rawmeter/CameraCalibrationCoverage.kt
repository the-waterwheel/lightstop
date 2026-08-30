package com.lightmeter.rawmeter

internal data class CameraCalibrationCoverageEntry(
    val storageCameraId: String,
    val record: CameraCalibrationRecord,
)

internal data class CameraCalibrationCoverage(
    val direct: CameraCalibrationCoverageEntry?,
    val activePhysical: CameraCalibrationCoverageEntry?,
    val physical: List<CameraCalibrationCoverageEntry>,
    val physicalLensCount: Int,
)

/** Resolves calibration records hidden behind an automatic logical-camera picker entry. */
internal object CameraCalibrationCoverageResolver {
    fun resolve(
        camera: CameraDescriptor,
        cameras: List<CameraDescriptor>,
        selectedCameraId: String,
        cameraInfo: CameraUiInfo,
        record: (String) -> CameraCalibrationRecord?,
    ): CameraCalibrationCoverage {
        val direct = record(camera.cameraId)?.let {
            CameraCalibrationCoverageEntry(camera.cameraId, it)
        }
        if (camera.lensRole != CameraLensRole.AUTOMATIC || camera.physicalCameraId != null) {
            return CameraCalibrationCoverage(direct, null, emptyList(), 0)
        }

        val physicalIds = cameras.asSequence()
            .filter { it.logicalCameraId == camera.logicalCameraId && it.physicalCameraId != null }
            .map { it.cameraId }
            .distinct()
            .toMutableList()
        val activeId = cameraInfo.activePhysicalCameraId
            ?.takeIf {
                selectedCameraId == camera.cameraId &&
                    cameraInfo.logicalCameraId == camera.logicalCameraId
            }
            ?.let { "${camera.logicalCameraId}@$it" }
        if (activeId != null && activeId !in physicalIds) physicalIds.add(0, activeId)
        val physical = physicalIds.mapNotNull { storageId ->
            record(storageId)?.let { CameraCalibrationCoverageEntry(storageId, it) }
        }
        return CameraCalibrationCoverage(
            direct = direct,
            activePhysical = physical.firstOrNull { it.storageCameraId == activeId },
            physical = physical,
            physicalLensCount = physicalIds.size,
        )
    }
}

/** Keeps a physical calibration visible across transient logical-camera session reopens. */
internal object CalibrationDisplayIdentity {
    fun resolve(boundCameraId: String?, liveCameraId: String): String {
        val live = liveCameraId.ifBlank { "0" }
        return if (boundCameraId != null && '@' !in live &&
            boundCameraId.startsWith("$live@")
        ) {
            boundCameraId
        } else {
            live
        }
    }
}
