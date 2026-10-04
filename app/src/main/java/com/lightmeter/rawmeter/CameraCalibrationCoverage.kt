package com.lightmeter.rawmeter

internal data class CameraCalibrationCoverageEntry(
    val storageCameraId: String,
    val record: CameraCalibrationRecord,
    val signatureStates: Map<MeteringSource, CalibrationSignatureState> = emptyMap(),
) {
    /** True when a stored correction exists but does not apply to the current capture context. */
    val needsRevalidation: Boolean
        get() = listOf(
            MeteringSource.RAW to record.rawCorrectionEv,
            MeteringSource.YUV_PREVIEW to record.yuvCorrectionEv,
            MeteringSource.ISP_PREVIEW to record.ispPreviewCorrectionEv,
        ).any { (source, value) ->
            value != null && signatureStates[source]?.let {
                it != CalibrationSignatureState.VALID && it != CalibrationSignatureState.NONE
            } == true
        }
}

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
        signatureState: (String, MeteringSource) -> CalibrationSignatureState = { _, _ ->
            CalibrationSignatureState.VALID
        },
    ): CameraCalibrationCoverage {
        fun entryFor(storageId: String, value: CameraCalibrationRecord) =
            CameraCalibrationCoverageEntry(
                storageCameraId = storageId,
                record = value,
                signatureStates = MeteringSource.entries.associateWith { source ->
                    signatureState(storageId, source)
                },
            )
        val direct = record(camera.cameraId)?.let { entryFor(camera.cameraId, it) }
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
            record(storageId)?.let { entryFor(storageId, it) }
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
