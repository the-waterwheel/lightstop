package com.lightmeter.rawmeter

import android.os.Handler
import android.os.SystemClock

internal data class ForegroundPreviewGeometry(
    val width: Int,
    val height: Int,
    val displayRotation: Int,
    val ready: Boolean,
    val keepSampling: Boolean,
)

/** Waits for stable foreground window geometry before binding a fresh camera producer. */
internal class ForegroundCameraStartCoordinator(
    private val mainHandler: Handler,
    private val isEligible: () -> Boolean,
    private val sampleGeometry: () -> ForegroundPreviewGeometry,
    private val onReady: (ForegroundPreviewGeometry) -> Unit,
) {
    private val token = Any()
    private var revision = 0

    fun schedule() {
        val expectedRevision = ++revision
        mainHandler.removeCallbacksAndMessages(token)
        var previousWidth = -1
        var previousHeight = -1
        var previousRotation = -1
        var stableSamples = 0
        var attempts = 0
        lateinit var sample: Runnable
        sample = Runnable {
            if (expectedRevision != revision || !isEligible()) return@Runnable
            val geometry = sampleGeometry()
            if (geometry.ready &&
                geometry.width == previousWidth &&
                geometry.height == previousHeight &&
                geometry.displayRotation == previousRotation
            ) {
                stableSamples += 1
            } else {
                stableSamples = 0
            }
            previousWidth = geometry.width
            previousHeight = geometry.height
            previousRotation = geometry.displayRotation
            attempts += 1
            if (geometry.ready &&
                (stableSamples >= STABLE_SAMPLES || attempts >= MAX_SAMPLES)
            ) {
                onReady(geometry)
                return@Runnable
            }
            if (geometry.keepSampling && attempts < MAX_SAMPLES) {
                postSample(sample)
            }
        }
        postSample(sample)
    }

    fun cancel() {
        revision += 1
        mainHandler.removeCallbacksAndMessages(token)
    }

    private fun postSample(sample: Runnable) {
        mainHandler.postAtTime(
            sample,
            token,
            SystemClock.uptimeMillis() + SAMPLE_INTERVAL_MS,
        )
    }

    private companion object {
        private const val STABLE_SAMPLES = 2
        private const val MAX_SAMPLES = 16
        private const val SAMPLE_INTERVAL_MS = 32L
    }
}
