package com.lightmeter.rawmeter

/**
 * Determines whether a processed camera frame carries enough exposure metadata for absolute EV.
 *
 * READ_SENSOR_SETTINGS and MANUAL_SENSOR guarantee reporting of the applied sensor settings.
 * Some otherwise-limited camera providers expose the individual result keys without either
 * capability, so those keys remain a valid, narrower compatibility path.
 */
internal object ExposureMetadataPolicy {
    fun supportsAbsoluteMetering(
        readSensorSettingsAvailable: Boolean,
        manualSensorAvailable: Boolean,
        exposureTimeResultAvailable: Boolean,
        sensitivityResultAvailable: Boolean,
        apertureResultAvailable: Boolean,
        staticApertureAvailable: Boolean,
    ): Boolean {
        val sensorSettingsAvailable = readSensorSettingsAvailable ||
            manualSensorAvailable ||
            (exposureTimeResultAvailable && sensitivityResultAvailable)
        val apertureAvailable = apertureResultAvailable || staticApertureAvailable
        return sensorSettingsAvailable && apertureAvailable
    }

    /** A missing value invalidates only this frame; later results may still become usable. */
    fun hasUsableFrameMetadata(
        exposureTimeNs: Long?,
        sensitivity: Int?,
        resultAperture: Float?,
        staticApertures: FloatArray?,
    ): Boolean {
        val aperture = resultAperture ?: staticApertures?.firstOrNull()
        return exposureTimeNs != null && exposureTimeNs > 0L &&
            sensitivity != null && sensitivity > 0 &&
            aperture != null && aperture.isFinite() && aperture > 0f
    }
}
