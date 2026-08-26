package com.lightmeter.rawmeter

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView

/** Main-thread sampler that keeps camera preview-health state out of [CameraController]. */
internal class PreviewHealthSampler(
    private val onFailure: (PreviewHealthReason) -> Unit,
) {
    private val monitor = PreviewHealthMonitor()
    private var sampledFrames = 0
    private var lastDecision: PreviewHealthDecision? = null

    fun onTextureUpdated(
        texture: TextureView?,
        surface: SurfaceTexture,
        cameraStarted: Boolean,
    ) {
        sampledFrames += 1
        if (sampledFrames % SAMPLE_INTERVAL != 0 || !cameraStarted) return
        val view = texture ?: return
        if (!view.isAvailable || view.width <= 1 || view.height <= 1) return
        val bitmap = try {
            view.getBitmap(SAMPLE_EDGE, SAMPLE_EDGE)
        } catch (_: RuntimeException) {
            null
        } ?: return
        val pixels = IntArray(SAMPLE_EDGE * SAMPLE_EDGE)
        try {
            bitmap.getPixels(pixels, 0, SAMPLE_EDGE, 0, 0, SAMPLE_EDGE, SAMPLE_EDGE)
        } finally {
            bitmap.recycle()
        }
        val metrics = PreviewHealthAnalyzer.analyzeArgb(SAMPLE_EDGE, SAMPLE_EDGE, pixels) ?: return
        val decision = monitor.observe(metrics, surface.timestamp)
        if (decision != lastDecision) {
            lastDecision = decision
            Log.i(
                TAG,
                "Preview health state=${decision.state} reason=${decision.reason} " +
                    "stripeH=${metrics.horizontalStripeScore} stripeV=${metrics.verticalStripeScore} " +
                    "green=${metrics.greenDominance} dark=${metrics.darkFraction}",
            )
        }
        if (decision.state == PreviewHealthState.FAILED) {
            onFailure(decision.reason ?: return)
        }
    }

    fun reset() {
        monitor.reset()
        sampledFrames = 0
        lastDecision = null
    }

    private companion object {
        private const val TAG = "PreviewHealth"
        private const val SAMPLE_EDGE = 64
        private const val SAMPLE_INTERVAL = 4
    }
}
