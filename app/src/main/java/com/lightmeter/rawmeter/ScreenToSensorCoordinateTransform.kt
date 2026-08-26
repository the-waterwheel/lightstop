package com.lightmeter.rawmeter

/**
 * Maps a normalized point as shown in the preview into normalized sensor coordinates.
 *
 * [mirrored] represents a user-facing front-camera preview. It is intentionally carried with
 * the rotation rather than inferred at each call site, so touch metering and saved RAW grids use
 * the exact same coordinate contract.
 */
internal data class ScreenToSensorCoordinateTransform(
    val rotationDegrees: Int,
    val mirrored: Boolean,
) {
    fun map(screenX: Float, screenY: Float): Pair<Float, Float> {
        val x = (if (mirrored) 1f - screenX else screenX).coerceIn(0f, 1f)
        val y = screenY.coerceIn(0f, 1f)
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
    }
}
