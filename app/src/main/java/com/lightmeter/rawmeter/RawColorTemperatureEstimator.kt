package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log

internal interface RawColorTemperatureEstimatorListener {
    fun onColorTemperatureCaptureResult(result: CaptureResult)
    fun onColorTemperatureFinished(
        callback: (Result<ColorTemperatureReading>) -> Unit,
        result: Result<ColorTemperatureReading>,
    )
}

/** Owns one RAW gray-card capture, including buffer/metadata pairing and timeout handling. */
internal class RawColorTemperatureEstimator(
    private val localized: (zh: String, en: String) -> String,
    private val listener: RawColorTemperatureEstimatorListener,
) {
    private data class ActiveCapture(
        val id: Int,
        val context: RawMeteringContext,
        val frameAspect: Float,
        val zoom: Float,
        val callback: (Result<ColorTemperatureReading>) -> Unit,
        val framePairer: TimestampedResultPairer<Image, CaptureResult> =
            TimestampedResultPairer(
                releaseImage = Image::close,
            ),
    )

    @Volatile
    var isEstimating = false
        private set

    private var nextId = 0
    private var active: ActiveCapture? = null
    private var timeout: Runnable? = null

    fun start(
        context: RawMeteringContext,
        request: CaptureRequest,
        frameAspect: Float,
        zoom: Float,
        completion: (Result<ColorTemperatureReading>) -> Unit,
    ): Boolean {
        if (isEstimating) return false
        val capture = ActiveCapture(++nextId, context, frameAspect, zoom, completion)
        active = capture
        isEstimating = true
        return try {
            scheduleTimeout(capture)
            context.session.capture(request, captureCallback, context.handler)
            true
        } catch (error: Exception) {
            finish(Result.failure(error))
            true
        }
    }

    fun onImageAvailable(reader: ImageReader): Boolean {
        val capture = active ?: return false
        val image = try {
            reader.acquireNextImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return true
        capture.framePairer.offerImage(image.timestamp, image)?.let { pair ->
            processPair(capture, pair)
        }
        return true
    }

    /** Cancels without touching a camera session; the controller owns preview/session teardown. */
    fun cancel(handler: Handler?): ((Result<ColorTemperatureReading>) -> Unit)? {
        val capture = active ?: return null
        nextId += 1
        active = null
        isEstimating = false
        cancelTimeout(handler)
        capture.framePairer.clear()
        return capture.callback
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val capture = active ?: return
            val effective = effectiveCaptureResult(result, capture.context.selectedPhysicalCameraId)
            listener.onColorTemperatureCaptureResult(effective)
            val timestamp = effective.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: result.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: return
            capture.framePairer.offerResult(timestamp, effective)?.let { pair ->
                processPair(capture, pair)
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            finish(
                Result.failure(
                    IllegalStateException(
                        localized("RAW 色温拍摄失败，请重试", "RAW color-temperature capture failed"),
                    ),
                ),
            )
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            finish(
                Result.failure(
                    IllegalStateException(
                        localized("RAW 色温拍摄被中止，请重试", "RAW color-temperature capture was aborted"),
                    ),
                ),
            )
        }
    }

    private fun processPair(
        capture: ActiveCapture,
        pair: TimestampedResultPair<Image, CaptureResult>,
    ) {
        try {
            val reading = RawColorTemperatureAnalysis.analyze(
                image = pair.image,
                result = pair.result,
                characteristics = capture.context.characteristics,
                cameraInfo = capture.context.cameraInfo,
                frameAspect = capture.frameAspect,
                zoom = capture.zoom,
            )
            if (reading == null) {
                finish(
                    Result.failure(
                        IllegalStateException(
                            localized(
                                "无法从中央区域读取灰卡，请避免过暗、过曝并让灰卡填满引导框",
                                "Unable to read the gray card. Avoid under/overexposure and fill the guide",
                            ),
                        ),
                    ),
                )
            } else {
                Log.i(
                    TAG,
                    "Color temperature estimated: ${reading.kelvin}K " +
                        "confidence=${reading.confidence} fit=${reading.fitError} " +
                        "level=${reading.sampleLevel} clipped=${reading.clippedFraction}",
                )
                finish(Result.success(reading))
            }
        } finally {
            pair.image.close()
        }
    }

    private fun scheduleTimeout(capture: ActiveCapture) {
        cancelTimeout(capture.context.handler)
        lateinit var pending: Runnable
        pending = Runnable {
            if (timeout !== pending || active?.id != capture.id) return@Runnable
            timeout = null
            finish(
                Result.failure(
                    IllegalStateException(
                        localized("RAW 色温拍摄超时，请重试", "RAW color-temperature capture timed out"),
                    ),
                ),
            )
        }
        timeout = pending
        capture.context.handler.postDelayed(pending, TIMEOUT_MS)
    }

    private fun cancelTimeout(handler: Handler?) {
        timeout?.let { handler?.removeCallbacks(it) }
        timeout = null
    }

    private fun finish(result: Result<ColorTemperatureReading>) {
        val capture = active ?: return
        active = null
        isEstimating = false
        cancelTimeout(capture.context.handler)
        capture.framePairer.clear()
        listener.onColorTemperatureFinished(capture.callback, result)
    }

    @Suppress("DEPRECATION")
    private fun effectiveCaptureResult(
        result: TotalCaptureResult,
        selectedPhysicalCameraId: String?,
    ): CaptureResult = selectedPhysicalCameraId?.let { result.physicalCameraResults[it] } ?: result

    companion object {
        private const val TAG = "lightstop"
        private const val TIMEOUT_MS = 8_000L
    }
}
