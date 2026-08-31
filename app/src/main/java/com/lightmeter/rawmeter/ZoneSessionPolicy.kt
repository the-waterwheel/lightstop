package com.lightmeter.rawmeter

/** Pure stream-selection rules for Zone mode; CameraController owns the actual session switch. */
internal object ZoneSessionPolicy {
    fun residentProfile(
        normalProfile: CameraSessionProfile,
        zoneActive: Boolean,
        manualSafePreview: Boolean,
        trackingSupported: Boolean,
        zoneYuvUnavailable: Boolean,
    ): CameraSessionProfile = when {
        manualSafePreview -> CameraSessionProfile.PREVIEW_ONLY
        zoneActive && trackingSupported && !zoneYuvUnavailable ->
            CameraSessionProfile.COMPATIBLE
        zoneActive -> CameraSessionProfile.PREVIEW_ONLY
        else -> normalProfile
    }

    fun shouldUseTransientRaw(
        zoneActive: Boolean,
        requestedSource: MeteringSource?,
        pipelineMode: MeteringPipelineMode,
        rawSupported: Boolean,
        manualSafePreview: Boolean,
    ): Boolean = zoneActive &&
        requestedSource == null &&
        pipelineMode == MeteringPipelineMode.AUTO &&
        rawSupported &&
        !manualSafePreview

    /** Missing vendor metadata is unknown, not proof of a switch. */
    fun physicalCameraChanged(expectedId: String?, observedId: String?): Boolean =
        expectedId != null && observedId != null && expectedId != observedId
}
