package com.lightmeter.rawmeter

import kotlin.math.max

/** Inverse center crop, from GL output UV to the already oriented input texture UV. */
internal object FilmNegativeDisplayGeometry {
    fun matrix(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int,
        displayDegrees: Int, mirrored: Boolean, zoom: Float = 1f): FloatArray {
        require(sourceWidth > 0 && sourceHeight > 0 && outputWidth > 0 && outputHeight > 0)
        val degrees = ((displayDegrees % 360) + 360) % 360
        val quarter = degrees == 90 || degrees == 270
        val scale = max(outputWidth.toFloat() / if (quarter) sourceHeight else sourceWidth,
            outputHeight.toFloat() / if (quarter) sourceWidth else sourceHeight) *
            (zoom.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f)
        val cos = when (degrees) { 0 -> 1f; 180 -> -1f; else -> 0f }
        val sin = when (degrees) { 90 -> 1f; 270 -> -1f; else -> 0f }
        val mirror = if (mirrored) -1f else 1f
        val xx = cos * outputWidth * mirror / (sourceWidth * scale)
        val xy = -sin * outputHeight / (sourceWidth * scale)
        val yx = sin * outputWidth * mirror / (sourceHeight * scale)
        val yy = cos * outputHeight / (sourceHeight * scale)
        // GLSL column-major mat3; the center always maps to the sample center.
        return floatArrayOf(xx, yx, 0f, xy, yy, 0f,
            0.5f - (xx + xy) * 0.5f, 0.5f - (yx + yy) * 0.5f, 1f)
    }
}
