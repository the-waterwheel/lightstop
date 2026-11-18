package com.lightmeter.rawmeter

/** Chooses the smallest safe Camera2 session for one calibration source. */
internal object CalibrationSessionProfilePolicy {
    fun profileFor(source: MeteringSource): CameraSessionProfile = when (source) {
        MeteringSource.RAW -> CameraSessionProfile.RAW_ONLY
        MeteringSource.YUV_PREVIEW -> CameraSessionProfile.COMPATIBLE
        MeteringSource.ISP_PREVIEW -> CameraSessionProfile.PREVIEW_ONLY
    }

    fun requiresFreshSession(
        profile: CameraSessionProfile,
        activeProfile: CameraSessionProfile?,
        sessionReady: Boolean,
        yuvAvailable: Boolean,
    ): Boolean = !sessionReady || profile != activeProfile ||
        (profile == CameraSessionProfile.COMPATIBLE && !yuvAvailable)

    fun rawFallbackPreview(
        failedProfile: CameraSessionProfile?,
        previousPreview: CameraSessionProfile?,
    ): CameraSessionProfile? = previousPreview?.takeIf {
        failedProfile == CameraSessionProfile.RAW_ONLY && it.usesPreview && !it.usesRaw
    }
}
