package com.lightmeter.rawmeter

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.TextureView

/** Main-thread sampler that keeps camera preview-health state out of [CameraController]. */
internal class PreviewHealthSampler(
    private val onFailure: (PreviewHealthReason) -> Unit,
    private val onHealthyPreviewConfirmed: () -> Unit = {},
) {
    private val monitor = PreviewHealthMonitor()
    private val samplingWindow = PreviewHealthSamplingWindow(HEALTH_CHECK_WINDOW_NS)
    private var sampledFrames = 0
    private var lastDecision: PreviewHealthDecision? = null
    private var healthyStreak = 0
    private var healthyPreviewReported = false

    fun onTextureUpdated(
        texture: TextureView?,
        surface: SurfaceTexture,
        cameraStarted: Boolean,
    ) {
        if (!samplingWindow.isActive(SystemClock.elapsedRealtimeNanos())) return
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
        healthyStreak = if (decision.state == PreviewHealthState.HEALTHY) healthyStreak + 1 else 0
        if (decision != lastDecision) {
            lastDecision = decision
            Log.i(
                TAG,
                "Preview health state=${decision.state} reason=${decision.reason} " +
                    "stripeH=${metrics.horizontalStripeScore} stripeV=${metrics.verticalStripeScore} " +
                    "green=${metrics.greenDominance} greenPixels=${metrics.saturatedGreenFraction} " +
                    "lumaStd=${metrics.lumaStandardDeviation} dark=${metrics.darkFraction} " +
                    "bright=${metrics.brightFraction}",
            )
        }
        if (decision.state == PreviewHealthState.FAILED) {
            samplingWindow.stop()
            onFailure(decision.reason ?: return)
        } else if (!healthyPreviewReported && healthyStreak >= HEALTHY_CONFIRMATION_FRAMES) {
            healthyPreviewReported = true
            onHealthyPreviewConfirmed()
        }
    }

    fun restartMonitoringWindow() {
        monitor.reset()
        sampledFrames = 0
        lastDecision = null
        healthyStreak = 0
        healthyPreviewReported = false
        samplingWindow.restart(SystemClock.elapsedRealtimeNanos())
    }

    fun stopMonitoring() {
        samplingWindow.stop()
        monitor.reset()
        sampledFrames = 0
        lastDecision = null
        healthyStreak = 0
        healthyPreviewReported = false
    }

    private companion object {
        private const val TAG = "PreviewHealth"
        private const val SAMPLE_EDGE = 64
        private const val SAMPLE_INTERVAL = 4
        private const val HEALTHY_CONFIRMATION_FRAMES = 3
        private const val HEALTH_CHECK_WINDOW_NS = 6_000_000_000L
    }
}

/** Pure bounded window so health bitmap sampling cannot become a permanent background cost. */
internal class PreviewHealthSamplingWindow(
    private val durationNs: Long,
) {
    private var deadlineNs: Long? = null

    fun restart(nowNs: Long) {
        deadlineNs = nowNs + durationNs.coerceAtLeast(0L)
    }

    fun stop() {
        deadlineNs = null
    }

    fun isActive(nowNs: Long): Boolean {
        val deadline = deadlineNs ?: return false
        if (nowNs < deadline) return true
        deadlineNs = null
        return false
    }
}
