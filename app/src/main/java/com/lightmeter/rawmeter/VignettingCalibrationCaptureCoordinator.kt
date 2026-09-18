package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log

internal interface VignettingCalibrationCaptureListener {
    fun onVignettingCaptureStarted()
    fun onVignettingCaptureResult(result: TotalCaptureResult, effectiveResult: CaptureResult)
    fun onVignettingCaptureFinished(info: VignettingCalibrationInfo)
    fun onVignettingCaptureError(message: String)
}

/** Owns one RAW vignetting calibration capture, including pairing, timeout and persistence. */
internal class VignettingCalibrationCaptureCoordinator(
    private val store: VignettingCalibrationStore,
    private val localized: (zh: String, en: String) -> String,
    private val cameraCharacteristics: () -> CameraCharacteristics?,
    private val cameraInfo: () -> CameraUiInfo,
    private val listener: VignettingCalibrationCaptureListener,
) {
    private data class ActiveCapture(
        val id: Int,
        val cameraId: String,
        val context: RawMeteringContext,
        val framePairer: TimestampedResultPairer<Image, CaptureResult> =
            TimestampedResultPairer(releaseImage = Image::close),
    )

    @Volatile
    private var active: ActiveCapture? = null
    private var nextId = 0
    private var timeout: Runnable? = null

    val isActive: Boolean
        get() = active != null

    fun start(
        context: RawMeteringContext,
        cameraId: String,
        request: () -> CaptureRequest,
        beforeCapture: () -> Unit,
    ): Boolean {
        if (active != null) return false
        val capture = ActiveCapture(++nextId, cameraId, context)
        active = capture
        listener.onVignettingCaptureStarted()
        return try {
            val captureRequest = request()
            scheduleTimeout(capture)
            beforeCapture()
            context.session.capture(captureRequest, captureCallback, context.handler)
            true
        } catch (_: Exception) {
            finishWithError(
                localized(
                    "无法读取校准画面，请重试",
                    "Unable to read the calibration image. Please try again",
                ),
            )
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

    /** Cancels state only; interruption messaging and session teardown remain controller-owned. */
    fun cancel(handler: Handler?): Boolean {
        val capture = active ?: return false
        nextId += 1
        active = null
        cancelTimeout(handler)
        capture.framePairer.clear()
        return true
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val capture = active ?: return
            val effective = effectiveCaptureResult(result, capture.context.selectedPhysicalCameraId)
            listener.onVignettingCaptureResult(result, effective)
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
            finishWithError(
                localized(
                    "相机未能完成暗角校准，请重试",
                    "The camera could not complete vignetting calibration. Please try again",
                ),
            )
        }
    }

    private fun processPair(
        capture: ActiveCapture,
        pair: TimestampedResultPair<Image, CaptureResult>,
    ) {
        val characteristics = cameraCharacteristics()
        try {
            if (characteristics == null) {
                finishWithError(
                    localized(
                        "暗角校准数据无效，请重试",
                        "Vignetting calibration data was invalid. Please try again",
                    ),
                )
                return
            }
            val calibration = MeteringAnalysis.createVignettingCalibrationMap(
                image = pair.image,
                result = pair.result,
                characteristics = characteristics,
                activeArray = cameraInfo().activeArray,
            )
            if (calibration == null) {
                finishWithError(
                    localized(
                        "画面过暗或过亮，请调整均匀画面后重试",
                        "The frame is too dark, too bright, or invalid. Adjust the uniform scene and try again",
                    ),
                )
                return
            }
            store.save(capture.cameraId, calibration)
            val info = store.info(capture.cameraId)
            if (info == null) {
                finishWithError(
                    localized(
                        "暗角校准数据无效，请重试",
                        "Vignetting calibration data was invalid. Please try again",
                    ),
                )
                return
            }
            active = null
            cancelTimeout(capture.context.handler)
            capture.framePairer.clear()
            Log.i(
                TAG,
                "Vignetting calibration saved camera=${capture.cameraId} " +
                    "grid=${info.gridWidth}x${info.gridHeight} " +
                    "gain=${info.minimumGain}..${info.maximumGain}",
            )
            listener.onVignettingCaptureFinished(info)
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
            finishWithError(
                localized(
                    "暗角校准超时，请重试",
                    "Vignetting calibration timed out. Please try again",
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

    private fun finishWithError(message: String) {
        val capture = active ?: return
        active = null
        cancelTimeout(capture.context.handler)
        capture.framePairer.clear()
        Log.e(TAG, "Vignetting calibration failed: $message")
        listener.onVignettingCaptureError(message)
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
