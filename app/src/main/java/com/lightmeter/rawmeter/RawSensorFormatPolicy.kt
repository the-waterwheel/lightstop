package com.lightmeter.rawmeter

/**
 * The native RAW sampler accepts exactly one 16-bit sample per pixel in one of the four Bayer
 * arrangements. CFA_RGB instead stores three samples per pixel and must never be interpreted as
 * Bayer; doing so corrupts stride math and produces arbitrary exposure statistics.
 */
internal object RawSensorFormatPolicy {
    fun isBayerCfa(colorFilterArrangement: Int?): Boolean = colorFilterArrangement in BAYER_CFAS

    fun supportsBayerMetering(
        rawCapabilityAdvertised: Boolean,
        hasRawSensorOutput: Boolean,
        isLegacyHardware: Boolean,
        colorFilterArrangement: Int?,
    ): Boolean = rawCapabilityAdvertised && hasRawSensorOutput && !isLegacyHardware &&
        isBayerCfa(colorFilterArrangement)

    // CameraMetadata's arrangement values are stable back to API 21. Numeric values avoid
    // accidentally referencing a newer inline constant on an API-28 device build.
    private val BAYER_CFAS = setOf(0, 1, 2, 3) // RGGB, GRBG, GBRG, BGGR
}
