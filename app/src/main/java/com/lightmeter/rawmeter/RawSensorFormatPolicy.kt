package com.lightmeter.rawmeter

/**
 * The native RAW sampler accepts exactly one 16-bit sample per pixel in one of the four Bayer
 * arrangements. CFA_RGB instead stores three samples per pixel and must never be interpreted as
 * Bayer; doing so corrupts stride math and produces arbitrary exposure statistics.
 */
internal object RawSensorFormatPolicy {
    fun isBayerCfa(colorFilterArrangement: Int?): Boolean = colorFilterArrangement in BAYER_CFAS

    // Vendors can omit the RAW capability flag or report Legacy while exposing usable RAW.
    // Trust the actual output declaration; CFA is needed to interpret the bytes correctly.
    fun supportsBayerMetering(
        hasRawSensorOutput: Boolean,
        colorFilterArrangement: Int?,
    ): Boolean = hasRawSensorOutput && isBayerCfa(colorFilterArrangement)

    // CameraMetadata's arrangement values are stable back to API 21. Numeric values avoid
    // accidentally referencing a newer inline constant on an API-28 device build.
    private val BAYER_CFAS = setOf(0, 1, 2, 3) // RGGB, GRBG, GBRG, BGGR
}
