package com.lightmeter.rawmeter

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicInteger

/** Read-only camera context and explicit session operations; feature state stays in its owner. */
internal interface CameraFilmPreviewHost {
    val cameraHandler: Handler?
    val cameraGeneration: Int
    val cameraInfo: CameraUiInfo
    val requestCharacteristics: CameraCharacteristics?
    val sessionAvailable: Boolean
    val textureView: TextureView?
    val started: Boolean
    val stopInProgress: Boolean
    val displayRotation: Int
    val displayZoom: Float
    fun closeCamera()
    fun resetRecoveryState()
    fun openCamera(output: SurfaceTexture?)
    fun updatePreviewRepeatingRequest()
    fun finishCameraFailure(message: String)
}

/**
 * Owns film-preview locking, frame/reference evidence, sampling and renderer lifetime.
 *
 * Camera2 and mutable capture evidence are confined to the host's camera handler; UI callbacks
 * use mainHandler, and GL operations remain in CameraFilmPreviewRenderer. Serial/generation checks
 * reject callbacks from an obsolete lock, sample, renderer or camera. Close the Camera2 producer
 * before releasing the renderer; a completed GL release resolves the live lifecycle/handler again.
 *
 * Maintenance boundary: add film-specific state and workflows here, not to CameraController.
 * This owner never selects a RAW/YUV workflow or changes metering/calibration policy.
 */
internal class CameraFilmPreviewCoordinator(
    private val mainHandler: Handler,
    private val host: CameraFilmPreviewHost,
    private val localized: (String, String) -> String,
    private val rendererFactory: CameraFilmPreviewRendererFactory =
        CameraFilmPreviewRendererFactory(::FilmNegativeRenderer),
) {
    @Volatile var isEnabled = false
        private set
    @Volatile private var currentSettings = FilmNegativeSettings()
    @Volatile var isLocked = false
        private set
    @Volatile private var previewRenderer: CameraFilmPreviewRenderer? = null
    private var rendererFailed = false
    private val rendererReleases = AtomicInteger(0)
    private var lockCompletion: ((Boolean) -> Unit)? = null
    private var lockSerial = 0L
    var isPreparingCapture = false
        private set
    private var convergedFrames = 0
    private var sampleGeneration = 0L
    private var currentEvidence: FilmNegativeFrameEvidence? = null
    private var sampleReference: FilmNegativeBaseSample? = null
    private var controlledCurve: Boolean? = null
    var onError: ((String) -> Unit)? = null
    var onReady: ((String) -> Unit)? = null
    var onBaseInvalidated: (() -> Unit)? = null
    var onProcessingChanged: ((Boolean) -> Unit)? = null

    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        isLocked = false
        val handler = host.cameraHandler ?: return
        handler.post {
            host.closeCamera()
            host.resetRecoveryState()
            rendererFailed = false
            releaseRenderer()
            if (host.started) host.openCamera(host.textureView?.surfaceTexture)
        }
    }

    fun updateSettings(settings: FilmNegativeSettings) {
        currentSettings = settings
        host.cameraHandler?.post {
            val latest = currentSettings
            // Manual colour/density edits still belong to the capture setup that supplied them.
            if (isEnabled) previewRenderer?.update(latest)
        }
    }

    fun setCameraLocked(locked: Boolean, completion: (Boolean) -> Unit) {
        val handler = host.cameraHandler
        if (handler == null) { mainHandler.post { completion(false) }; return }
        handler.post {
            lockCompletion?.let { old -> mainHandler.post { old(false) } }
            lockCompletion = null
            val serial = ++lockSerial
            val chars = host.requestCharacteristics
            val supported = chars?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
            val accepted = isEnabled && host.sessionAvailable && (!locked || supported)
            val waitForLock = locked && accepted
            isPreparingCapture = waitForLock && !isLocked
            convergedFrames = 0
            if (!waitForLock) { isLocked = false; invalidateBase() }
            if (waitForLock) lockCompletion = completion
            if (isEnabled) host.updatePreviewRepeatingRequest()
            if (!waitForLock) mainHandler.post { if (serial == lockSerial) completion(accepted) }
            else handler.postDelayed({
                if (serial == lockSerial && lockCompletion != null) {
                    val pending = lockCompletion
                    lockCompletion = null
                    isPreparingCapture = false
                    isLocked = false
                    invalidateBase()
                    host.updatePreviewRepeatingRequest()
                    mainHandler.post { if (serial == lockSerial) pending?.invoke(false) }
                }
            }, 2500L)
        }
    }

    fun sampleBase(automatic: Boolean = false, completion: (FloatArray?) -> Unit) {
        val handler = host.cameraHandler
        if (handler == null) { mainHandler.post { completion(null) }; return }
        handler.post {
            val sampleSerial = ++sampleGeneration
            val renderer = previewRenderer
            val generation = host.cameraGeneration
            if (!isEnabled || renderer == null || !isLocked || lockCompletion != null) {
                mainHandler.post { completion(null) }
            } else {
                renderer.sampleBase(automatic) { sample -> handler.post sampleResult@{
                    if (sampleSerial != sampleGeneration || generation != host.cameraGeneration || !isEnabled || previewRenderer !== renderer) return@sampleResult
                    val accepted = sample?.takeIf { isLocked && currentEvidence?.compatible(it.evidence) == true }
                    if (accepted != null) sampleReference = accepted
                    mainHandler.post {
                        if (sampleSerial == sampleGeneration && generation == host.cameraGeneration && isEnabled && previewRenderer === renderer) {
                            completion(accepted?.takeIf { sampleReference === it }?.rgb)
                        }
                    }
                } }
            }
        }
    }

    fun captureMeteringFrame(completion: (android.graphics.Bitmap?, Long) -> Unit) {
        captureOriginalFrame(320, completion)
    }

    fun captureRecordPreview(completion: (android.graphics.Bitmap?) -> Unit) {
        captureOriginalFrame(1600) { bitmap, _ -> completion(bitmap) }
    }

    private fun captureOriginalFrame(maxEdge: Int, completion: (android.graphics.Bitmap?, Long) -> Unit) {
        val renderer = previewRenderer
        val generation = host.cameraGeneration
        if (renderer == null || isEnabled) { completion(null, 0L); return }
        val orientation = renderer.orientationToken
        renderer.captureMeteringFrame(maxEdge) { bitmap, timestamp ->
            mainHandler.post {
                if (generation != host.cameraGeneration || renderer !== previewRenderer || renderer.orientationToken != orientation || isEnabled) {
                    bitmap?.recycle()
                    completion(null, 0L)
                } else completion(bitmap, timestamp)
            }
        }
    }

    fun captureFrame(maxEdge: Int = 1280, completion: (FilmNegativeFrozenFrame?) -> Unit) {
        val handler = host.cameraHandler
        if (handler == null) { mainHandler.post { completion(null) }; return }
        handler.post {
            val renderer = previewRenderer
            val generation = host.cameraGeneration
            if (!isEnabled || renderer == null || !isLocked || lockCompletion != null) {
                mainHandler.post { completion(null) }
            } else renderer.captureFrame(maxEdge) { frame -> handler.post {
                val accepted = frame?.takeIf { generation == host.cameraGeneration && isEnabled &&
                    previewRenderer === renderer && isLocked && currentEvidence?.compatible(it.evidence) == true }
                if (frame != null && accepted == null) Log.i("FilmFrameDiagnostic",
                    "Frozen frame conditions changed: processing=${frame.evidence.processingKey}->${currentEvidence?.processingKey} locked=$isLocked")
                mainHandler.post { completion(accepted?.takeIf { generation == host.cameraGeneration && isEnabled }) }
            } }
        }
    }

    /** Freeze one locked original, analyze it once, then reuse its display correction. */
    fun selectFrame(completion: (FilmNegativeSelection?) -> Unit) {
        val handler = host.cameraHandler
        if (handler == null) { mainHandler.post { completion(null) }; return }
        handler.post {
            val serial = ++sampleGeneration
            val generation = host.cameraGeneration
            val renderer = previewRenderer
            val snapshot = currentSettings
            if (renderer == null || !isEnabled || !isLocked || lockCompletion != null) {
                mainHandler.post { completion(null) }
                return@post
            }
            val orientation = renderer.orientationToken
            val selectionStartedAtMs = android.os.SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG) Log.i("FilmSelectionDiagnostic", "start serial=$serial mode=single-frame")
            var completed = false
            var timeout: Runnable? = null
            fun current() = serial == sampleGeneration && generation == host.cameraGeneration &&
                isEnabled && isLocked && previewRenderer === renderer
            fun finish(result: FilmNegativeSelection?) {
                if (completed) return
                completed = true
                timeout?.let { handler.removeCallbacks(it) }
                if (!current()) return
                val accepted = result?.takeIf { currentEvidence?.compatible(it.evidence) == true && renderer.orientationToken == it.orientationToken }
                if (BuildConfig.DEBUG) Log.i("FilmSelectionDiagnostic",
                    "finish serial=$serial accepted=${accepted != null} mode=single-frame elapsedMs=${android.os.SystemClock.elapsedRealtime() - selectionStartedAtMs}")
                if (accepted != null) {
                    sampleReference = FilmNegativeBaseSample(FloatArray(3) { accepted.settings.base(it) }, accepted.evidence)
                    currentSettings = accepted.settings
                    renderer.update(accepted.settings)
                }
                mainHandler.post { if (current()) completion(accepted?.takeIf { renderer.orientationToken == it.orientationToken }) }
            }
            timeout = Runnable { finish(null) }
            handler.postDelayed(checkNotNull(timeout), 6500L)
            renderer.captureFrame(FilmNegativeSelectionAnalysis.ANALYSIS_EDGE) { frame -> handler.post captureResult@{
                    if (!current() || completed) return@captureResult
                    if (frame == null || currentEvidence?.compatible(frame.evidence) != true ||
                        frame.orientationToken != orientation || renderer.orientationToken != orientation) { finish(null); return@captureResult }
                    Thread({
                        val analysisStarted = android.os.SystemClock.elapsedRealtime()
                        val result = runCatching { FilmNegativeSelectionAnalysis.automatic(frame, snapshot) }.getOrNull()
                        if (BuildConfig.DEBUG) Log.i("FilmSelectionDiagnostic", "analysis serial=$serial mode=single-frame " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - analysisStarted} region=${result?.region} " +
                            "monochrome=${result?.settings?.monochrome} base=${result?.settings?.let { "${it.baseRed},${it.baseGreen},${it.baseBlue}" }} " +
                            "tone=${result?.settings?.let { "${it.blackDensity},${it.whiteDensity}" }}")
                        handler.post analysisResult@{
                            if (!current() || completed) return@analysisResult
                            if (currentEvidence?.compatible(frame.evidence) != true ||
                                renderer.orientationToken != orientation) { finish(null); return@analysisResult }
                            finish(result)
                        }
                    }, "film-selection-analysis").start()
                } }
        }
    }

    fun acceptFrame(frame: FilmNegativeFrozenFrame, settings: FilmNegativeSettings,
        completion: (Boolean) -> Unit) {
        val handler = host.cameraHandler
        if (handler == null) { mainHandler.post { completion(false) }; return }
        handler.post {
            val generation = host.cameraGeneration
            val accepted = isEnabled && isLocked && currentEvidence?.compatible(frame.evidence) == true
                && previewRenderer?.orientationToken == frame.orientationToken
            val reference = if (accepted) FilmNegativeBaseSample(FloatArray(3) { settings.base(it) }, frame.evidence) else null
            if (accepted) {
                sampleReference = reference
                currentSettings = settings
                previewRenderer?.update(settings)
            }
            mainHandler.post { completion(accepted && generation == host.cameraGeneration && isEnabled &&
                isLocked && sampleReference === reference && currentSettings === settings) }
        }
    }

    private fun invalidateBase() {
        ++sampleGeneration
        if (sampleReference == null && !currentSettings.inverted &&
            currentSettings.referenceSource == FilmNegativeReferenceSource.DEFAULT) return
        sampleReference = null
        val resetSettings = currentSettings.withoutReference()
        currentSettings = resetSettings
        previewRenderer?.update(resetSettings)
        mainHandler.post { if (currentSettings === resetSettings) onBaseInvalidated?.invoke() }
    }

    fun observeResult(request: CaptureRequest, result: CaptureResult, timestamp: Long?) {
        if (!isEnabled || timestamp == null) return
        if (isPreparingCapture) {
            val converged = result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
                result.get(CaptureResult.CONTROL_AWB_STATE) == CaptureResult.CONTROL_AWB_STATE_CONVERGED
            convergedFrames = if (converged) convergedFrames + 1 else 0
            if (convergedFrames >= 2) {
                isPreparingCapture = false
                isLocked = true
                host.updatePreviewRepeatingRequest()
            }
        }
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val controlled = FilmNegativeCameraProfile.controlled(result)
        if (controlledCurve != controlled) {
            controlledCurve = controlled
            mainHandler.post { if (isEnabled) onProcessingChanged?.invoke(controlled) }
        }
        val evidence = FilmNegativeFrameEvidence(
            timestampNs = timestamp,
            cameraKey = "${host.cameraInfo.cameraId}:${host.cameraInfo.physicalCameraId}:${host.cameraGeneration}",
            locked = isLocked && request.get(CaptureRequest.CONTROL_AE_LOCK) == true &&
                request.get(CaptureRequest.CONTROL_AWB_LOCK) == true &&
                result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_LOCKED &&
                result.get(CaptureResult.CONTROL_AWB_STATE) == CaptureResult.CONTROL_AWB_STATE_LOCKED,
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            colorGains = gains?.let { listOf(it.red, it.greenEven, it.greenOdd, it.blue) } ?: emptyList(),
            processingKey = "${result.get(CaptureResult.TONEMAP_MODE)}:" +
                "${result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.hashCode()}:${result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)}",
            processingCurve = FilmNegativeCameraProfile.signature(result.get(CaptureResult.TONEMAP_CURVE)),
        )
        currentEvidence = evidence
        if (evidence.locked && lockCompletion != null) {
            val pending = lockCompletion
            val serial = lockSerial
            lockCompletion = null
            mainHandler.post { if (serial == lockSerial && isLocked) pending?.invoke(true) }
        }
        sampleReference?.let { if (!it.evidence.compatible(evidence)) {
            if (BuildConfig.DEBUG) Log.i("FilmSelectionDiagnostic", "reference-invalidated " +
                "locked=${evidence.locked} exposure=${it.evidence.exposureNs}->${evidence.exposureNs} " +
                "iso=${it.evidence.sensitivity}->${evidence.sensitivity} " +
                "gains=${it.evidence.colorGains}->${evidence.colorGains} " +
                "processing=${it.evidence.processingKey}->${evidence.processingKey} " +
                "curveChanged=${it.evidence.processingCurve != evidence.processingCurve}")
            isLocked = false
            invalidateBase()
            host.updatePreviewRepeatingRequest()
        } }
        previewRenderer?.observe(evidence)
    }

    fun retry() {
        host.cameraHandler?.post {
            if (!isEnabled) return@post
            host.closeCamera()
            host.resetRecoveryState()
            isLocked = false
            rendererFailed = false
            releaseRenderer()
            if (host.started) host.openCamera(host.textureView?.surfaceTexture)
        }
    }

    fun releaseRenderer() {
        val renderer = previewRenderer ?: return
        previewRenderer = null
        rendererReleases.incrementAndGet()
        renderer.release {
            rendererReleases.decrementAndGet()
            // GL cleanup may finish after stop() has retired the camera thread. Resolve
            // the live handler on the lifecycle thread so a resumed session can reopen.
            mainHandler.post {
                if (!host.started || host.stopInProgress) return@post
                val handler = host.cameraHandler ?: return@post
                handler.post {
                    if (host.started && host.cameraHandler === handler && rendererReleases.get() == 0) {
                        host.openCamera(host.textureView?.surfaceTexture)
                    }
                }
            }
        }
    }

    fun inputFor(output: SurfaceTexture): SurfaceTexture? {
        if (rendererFailed || rendererReleases.get() > 0) return null
        previewRenderer?.let { renderer ->
            if (renderer.outputTexture === output) return renderer.inputTexture
            releaseRenderer()
            return null
        }
        lateinit var renderer: CameraFilmPreviewRenderer
        renderer = rendererFactory.create(output, if (isEnabled) currentSettings
            else FilmNegativeSettings(inverted = false, showGuide = false),
            onReady = {
                host.cameraHandler?.post {
                    if (host.started && previewRenderer === renderer &&
                        host.textureView?.surfaceTexture === output) host.openCamera(output)
                }
            },
            onFirstFrame = {
                mainHandler.post {
                    if (isEnabled && previewRenderer === renderer) {
                        onReady?.invoke(host.cameraInfo.cameraId)
                    }
                }
            },
            onFailure = { error ->
                host.cameraHandler?.post {
                    if (previewRenderer !== renderer) return@post
                    rendererFailed = true
                    host.closeCamera()
                    releaseRenderer()
                    val message = localized("取景渲染失败，请重启预览或应用", "Preview rendering failed. Restart the preview or app")
                    Log.e(TAG, message, error)
                    mainHandler.post { if (isEnabled) onError?.invoke(message) }
                    if (!isEnabled) host.finishCameraFailure(message)
                }
            })
        previewRenderer = renderer
        orient()
        renderer.start()
        return null
    }

    fun orient() {
        val degrees = when (host.textureView?.display?.rotation ?: host.displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        previewRenderer?.orient(degrees, CameraPreviewTransform.shouldMirrorPreview(host.cameraInfo.lensFacing),
            if (isEnabled) 1f else host.displayZoom)
    }

    val hasRenderer: Boolean get() = previewRenderer != null
    val isReleasingRenderer: Boolean get() = rendererReleases.get() > 0

    /** A fresh foreground run retries the renderer, just as the camera retries its capabilities. */
    fun onCameraStart() { rendererFailed = false }

    /** Invalidate capture evidence before the host closes its Camera2 producers/readers. */
    fun onCameraClosed() {
        ++lockSerial
        ++sampleGeneration
        isPreparingCapture = false
        lockCompletion?.let { pending -> mainHandler.post { pending(false) } }
        lockCompletion = null
        isLocked = false
        currentEvidence = null
        controlledCurve = null
        invalidateBase()
    }

    fun onCameraFailure(message: String) {
        if (isEnabled) {
            isLocked = false
            rendererFailed = true
            releaseRenderer()
            val generation = host.cameraGeneration
            mainHandler.post {
                if (isEnabled && generation == host.cameraGeneration) {
                    onError?.invoke(message)
                }
            }
        }
    }

    fun resize(width: Int, height: Int) { previewRenderer?.resize(width, height) }

    private companion object { const val TAG = "lightstop" }
}
