package com.lightmeter.rawmeter

/** A correction and its source measurement for one camera output domain. */
data class StreamCalibration(
    val correctionEv: Double?,
    val measuredEv100: Double?,
)

/**
 * Immutable snapshot of one camera calibration revision.
 *
 * Processed YUV and displayed ISP preview are deliberately separate domains.  The legacy value
 * is retained only for pre-three-source installations and is never presented as a fresh result.
 */
data class CameraCalibrationRecord(
    val raw: StreamCalibration,
    val yuv: StreamCalibration,
    val ispPreview: StreamCalibration,
    val legacyCompatibleCorrectionEv: Double?,
    val legacyCompatibleMeasuredEv100: Double?,
    val referenceEv100: Double?,
    val updatedAtEpochMs: Long,
    val calibrationCount: Int,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    val rawCorrectionEv: Double? get() = raw.correctionEv
    val rawMeasuredEv100: Double? get() = raw.measuredEv100
    val yuvCorrectionEv: Double? get() = yuv.correctionEv
    val yuvMeasuredEv100: Double? get() = yuv.measuredEv100
    val ispPreviewCorrectionEv: Double? get() = ispPreview.correctionEv
    val ispPreviewMeasuredEv100: Double? get() = ispPreview.measuredEv100

    /** Compatibility aliases for callers that have not yet been upgraded to a source-specific UI. */
    val compatibleCorrectionEv: Double?
        get() = yuvCorrectionEv ?: ispPreviewCorrectionEv ?: legacyCompatibleCorrectionEv
    val compatibleMeasuredEv100: Double?
        get() = yuvMeasuredEv100 ?: ispPreviewMeasuredEv100 ?: legacyCompatibleMeasuredEv100
    val correctionEv: Double get() = rawCorrectionEv ?: compatibleCorrectionEv ?: 0.0
    val measuredEv100: Double? get() = rawMeasuredEv100 ?: compatibleMeasuredEv100

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}
