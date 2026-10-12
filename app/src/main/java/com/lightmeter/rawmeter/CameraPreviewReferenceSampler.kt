package com.lightmeter.rawmeter

import android.os.Looper
import android.view.TextureView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns small, main-thread TextureView reference reads for RAW point registration.
 * A batch uses one bitmap so all patches describe one displayed frame; every bitmap is recycled
 * here, and only immutable reference samples leave this boundary. It never owns Camera2 Images.
 */
internal class CameraPreviewReferenceSampler(private val textureView: () -> TextureView?) {
    fun capture(
        target: ZoneMeteringTarget,
    ): PreviewLumaReference? {
        val texture = textureView() ?: return null
        if (Looper.myLooper() != Looper.getMainLooper() ||
            !texture.isAvailable || texture.width <= 1 || texture.height <= 1
        ) return null
        val scale = PREVIEW_REFERENCE_LONG_EDGE.toFloat() /
            max(texture.width, texture.height)
        val bitmapWidth = (texture.width * scale).roundToInt().coerceAtLeast(2)
        val bitmapHeight = (texture.height * scale).roundToInt().coerceAtLeast(2)
        val bitmap = try {
            texture.getBitmap(bitmapWidth, bitmapHeight)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return try {
            MeteringAnalysis.createPreviewReference(bitmap, target)
        } finally {
            bitmap.recycle()
        }
    }

    /** Reads TextureView only once so every batch feature patch describes the same preview frame. */
    fun captureBatch(
        requests: List<ZoneMeteringRequest>,
    ): Map<Int, PreviewLumaReference?> {
        val texture = textureView() ?: return emptyMap()
        if (Looper.myLooper() != Looper.getMainLooper() ||
            !texture.isAvailable || texture.width <= 1 || texture.height <= 1
        ) return emptyMap()
        val scale = PREVIEW_REFERENCE_LONG_EDGE.toFloat() /
            max(texture.width, texture.height)
        val bitmapWidth = (texture.width * scale).roundToInt().coerceAtLeast(2)
        val bitmapHeight = (texture.height * scale).roundToInt().coerceAtLeast(2)
        val bitmap = try {
            texture.getBitmap(bitmapWidth, bitmapHeight)
        } catch (_: RuntimeException) {
            null
        } ?: return emptyMap()
        return try {
            requests.associate { request ->
                request.markerId to MeteringAnalysis.createPreviewReference(
                    bitmap,
                    request.target,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object { const val PREVIEW_REFERENCE_LONG_EDGE = 384 }
}
