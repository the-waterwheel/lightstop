package com.lightmeter.rawmeter

import android.hardware.camera2.CaptureResult
import android.media.ImageReader
import android.os.Handler

/** Camera-thread loop. Samples existing streams without neutralizing AE or rebuilding a session. */
internal class ContinuousPreviewMeter(
    private val mainHandler: Handler,
    private val cameraHandler: () -> Handler?,
    private val context: () -> CompatibleMeteringContext,
    private val canSample: () -> Boolean,
    calibrationStore: CameraCalibrationStore,
    localized: (String, String) -> String,
    private val onReading: (MeterReading) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile var enabled = false
        private set
    private var scheduled: Runnable? = null
    var intervalMs=350L
        set(value) {field=value.coerceIn(100L,5000L)}
    private val meter = CompatibleLightMeter(mainHandler, calibrationStore, localized,
        object : CompatibleLightMeterListener {
            override fun onCompatibleMeteringStarted(source: MeteringSource, frameCount: Int) = Unit
            override fun onCompatibleMeteringReading(reading: MeterReading) {
                if (enabled) onReading(reading)
            }
            override fun onCompatibleMeteringError(message: String) {
                if (enabled) onError(message)
                schedule()
            }
            override fun onCompatibleMeteringCompleted(requiresPreviewOnlySession: Boolean) { schedule() }
        })

    fun setEnabled(value: Boolean) {
        enabled = value
        suspend()
        if (value) schedule(0)
    }

    fun suspend(resetSource: Boolean = false) {
        scheduled?.let { cameraHandler()?.removeCallbacks(it) }
        scheduled = null
        meter.cancel(cameraHandler(), resetYuvAvailability = resetSource)
    }

    fun resume() { if (enabled) schedule() }
    fun onImage(reader: ImageReader): Boolean = enabled && meter.onImageAvailable(reader)
    fun onResult(result: CaptureResult, fallbackTimestamp: Long?) {
        if (enabled) meter.onCaptureResult(result, fallbackTimestamp)
    }

    private fun schedule(delay: Long = intervalMs) {
        val handler = cameraHandler() ?: return
        handler.post {
            if (!enabled) return@post
            scheduled?.let(handler::removeCallbacks)
            val action = Runnable {
                scheduled = null
                if (!enabled) return@Runnable
                if (!canSample()) { schedule(); return@Runnable }
                if (!meter.measure(MeteringMode.AVERAGE, null, context())) schedule()
            }
            scheduled = action
            handler.postDelayed(action, delay)
        }
    }
}
