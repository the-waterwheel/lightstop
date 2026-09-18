package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import java.io.File
import java.io.FileOutputStream

internal interface RawRecordCaptureListener {
    fun onRawRecordCaptureResult(result: CaptureResult)
    fun onRawRecordCaptureFinished(
        callback: (Result<RawRecordArtifact>) -> Unit,
        result: Result<RawRecordArtifact>,
    )
}

/**
 * Owns the complete one-shot RAW history capture lifecycle.
 *
 * CameraController remains responsible for choosing the session and pausing/resuming preview;
 * this coordinator owns Camera2 result/image pairing, timeout cleanup, DNG persistence and the
 * compact sampling grid.
 */
internal class RawRecordCaptureCoordinator(
    private val localized: (zh: String, en: String) -> String,
    private val cameraCharacteristics: () -> CameraCharacteristics?,
    private val cameraInfo: () -> CameraUiInfo,
    private val displayRotation: () -> Int,
    private val listener: RawRecordCaptureListener,
) {
    private data class ActiveCapture(
        val id: Int,
        val context: RawMeteringContext,
        val outputFile: File,
        val screenAspect: Float,
        val zoom: Float,
        val callback: (Result<RawRecordArtifact>) -> Unit,
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
        outputFile: File,
        screenAspect: Float,
        zoom: Float,
        request: () -> CaptureRequest,
        beforeCapture: () -> Unit,
        completion: (Result<RawRecordArtifact>) -> Unit,
    ): Boolean {
        if (active != null) return false
        val capture = ActiveCapture(
            id = ++nextId,
            context = context,
            outputFile = outputFile,
            screenAspect = screenAspect,
            zoom = zoom,
            callback = completion,
        )
        active = capture
        return try {
            outputFile.parentFile?.mkdirs()
            beforeCapture()
            scheduleTimeout(capture)
            context.session.capture(request(), captureCallback, context.handler)
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

    /** Cancels without resuming preview because the controller is about to close the session. */
    fun cancel(handler: Handler?): ((Result<RawRecordArtifact>) -> Unit)? {
        val capture = active ?: return null
        nextId += 1
        active = null
        cancelTimeout(handler)
        capture.framePairer.clear()
        capture.outputFile.delete()
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
            listener.onRawRecordCaptureResult(effective)
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
                    IllegalStateException(localized("RAW 记录失败", "RAW recording failed")),
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
                finish(
                    Result.failure(
                        IllegalStateException(localized("RAW 元数据无效", "RAW metadata is invalid")),
                    ),
                )
                return
            }
            val info = cameraInfo()
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: info.sensorOrientationDegrees
            val currentDisplayRotation = displayRotation()
            val screenToSensorTransform = ScreenToSensorCoordinateTransform(
                rotationDegrees = CameraPreviewTransform.relativeRotationDegrees(
                    sensorOrientation,
                    currentDisplayRotation,
                    info.lensFacing,
                ),
                mirrored = CameraPreviewTransform.shouldMirrorPreview(info.lensFacing),
                sensorViewport = info.previewSensorViewport,
            )
            val sensorAspect = CameraPreviewTransform.screenAspectInSensorCoordinates(
                capture.screenAspect,
                sensorOrientation,
                currentDisplayRotation,
                info.lensFacing,
            )
            val grid = RecordedRawGridSampler.sample(
                pair.image,
                pair.result,
                characteristics,
                sensorAspect,
                capture.zoom,
                screenToSensorTransform,
            ) ?: throw IllegalStateException(localized("RAW 数据无效", "RAW data is invalid"))
            FileOutputStream(capture.outputFile).use { stream ->
                DngCreator(characteristics, pair.result).use { creator ->
                    creator.writeImage(stream, pair.image)
                }
            }
            finish(Result.success(RawRecordArtifact(capture.outputFile.absolutePath, grid)))
        } catch (error: Exception) {
            finish(Result.failure(error))
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
                    IllegalStateException(localized("RAW 记录超时", "RAW recording timed out")),
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

    private fun finish(result: Result<RawRecordArtifact>) {
        val capture = active ?: return
        active = null
        cancelTimeout(capture.context.handler)
        capture.framePairer.clear()
        if (result.isFailure) capture.outputFile.delete()
        listener.onRawRecordCaptureFinished(capture.callback, result)
    }

    @Suppress("DEPRECATION")
    private fun effectiveCaptureResult(
        result: TotalCaptureResult,
        selectedPhysicalCameraId: String?,
    ): CaptureResult = selectedPhysicalCameraId?.let { result.physicalCameraResults[it] } ?: result

    companion object {
        private const val TIMEOUT_MS = 8_000L
    }
}
