package com.lightmeter.rawmeter

import android.graphics.SurfaceTexture
import android.graphics.Bitmap

/** GL worker boundary. The coordinator owns its lifetime; the worker owns GL and inputTexture. */
internal interface CameraFilmPreviewRenderer {
    val outputTexture: SurfaceTexture
    val inputTexture: SurfaceTexture?
    val orientationToken: Long
    fun start()
    fun resize(width: Int, height: Int)
    fun orient(displayDegrees: Int, mirrored: Boolean, zoom: Float = 1f)
    fun update(settings: FilmNegativeSettings)
    fun observe(evidence: FilmNegativeFrameEvidence)
    fun captureFrame(maxEdge: Int = 1280, completion: (FilmNegativeFrozenFrame?) -> Unit)
    /** Unzoomed original pixels and their sensor timestamp; caller pairs exposure metadata. */
    fun captureMeteringFrame(maxEdge: Int = 320, completion: (Bitmap?, Long) -> Unit) { completion(null, 0L) }
    fun sampleBase(automatic: Boolean = false, completion: (FilmNegativeBaseSample?) -> Unit)
    /** Call only after Camera2 producers close. Never release the TextureView's outputTexture. */
    fun release(completion: () -> Unit = {})
}

internal fun interface CameraFilmPreviewRendererFactory {
    fun create(
        outputTexture: SurfaceTexture,
        initialSettings: FilmNegativeSettings,
        onReady: (SurfaceTexture) -> Unit,
        onFirstFrame: () -> Unit,
        onFailure: (Exception) -> Unit,
    ): CameraFilmPreviewRenderer
}
