package com.lightmeter.rawmeter

/**
 * One source's capture context at the instant it was measured.
 *
 * It records the EV before the user's own metering correction/response was applied, plus the
 * correction that was actually applied, so a later save never has to re-read whichever session
 * happens to be active. The temporary session generation is diagnostic only and is never persisted
 * into the calibration's applicability.
 */
data class CalibrationMeasurementSample(
    val source: MeteringSource,
    val cameraId: String,
    val signature: CalibrationSignature?,
    /** Scene EV100 with physical conversion and baseline, but without the user correction/response. */
    val ev100BeforeUserCalibration: Double,
    /** The user offset or interpolated response actually applied to this frame. */
    val appliedUserCorrectionEv: Double,
    /** Processed-stream input luminance for a response anchor; null for RAW. */
    val inputLuma: Double? = null,
    val sessionGeneration: Long = 0L,
) {
    /** A sample is usable for saving only with finite EV values and a matching signature. */
    fun isUsableForSave(): Boolean =
        ev100BeforeUserCalibration.isFinite() &&
            appliedUserCorrectionEv.isFinite() &&
            signature != null &&
            cameraId.isNotBlank()

    fun withCaptureContext(cameraId: String, signature: CalibrationSignature?, generation: Long) =
        copy(cameraId = cameraId, signature = signature, sessionGeneration = generation)
}
