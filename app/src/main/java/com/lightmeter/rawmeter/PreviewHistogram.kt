package com.lightmeter.rawmeter

import android.graphics.Bitmap

internal object PreviewHistogram {
    const val BIN_COUNT = 64

    fun fromBitmap(bitmap: Bitmap): FloatArray {
        if (bitmap.width <= 0 || bitmap.height <= 0) return FloatArray(BIN_COUNT)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return fromPixels(pixels)
    }

    fun fromPixels(pixels: IntArray): FloatArray {
        val counts = IntArray(BIN_COUNT)
        pixels.forEach { pixel ->
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            val luma = (red * 54 + green * 183 + blue * 19) shr 8
            counts[(luma * BIN_COUNT / 256).coerceIn(0, BIN_COUNT - 1)] += 1
        }
        val peak = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        return FloatArray(BIN_COUNT) { index -> counts[index].toFloat() / peak }
    }
}
