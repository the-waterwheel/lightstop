package com.lightmeter.rawmeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Public camera facade used by MainActivity.
 *
 * This class selects capabilities and coordinates lifecycle, calibration and fallback. Camera2
 * resources, RAW metering, compatible metering, result pairing and recovery history are owned by
 * dedicated internal components below this facade.
 */
class CameraController(
    private val context: Context,
    private val callback: CameraControllerCallback,
) : TextureView.SurfaceTextureListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sessionCoordinator = CameraSessionCoordinator(
        cameraManager = cameraManager,
        onRawImageAvailable = ::onRawImageAvailable,
        onTrackingImageAvailable = ::onTrackingImageAvailable,
        sessionParametersProvider = ::createSessionParameters,
        listener = object : CameraSessionCoordinatorListener {
            override fun onCameraOpened(generation: Int) {
                if (!started || generation != cameraGeneration) return
                cameraFailureStage = CameraFailureStage.CONFIGURING
            }

            override fun onSessionConfigured(
                device: CameraDevice,
                session: CameraCaptureSession,
                previewSurface: Surface?,
                generation: Int,
            ) {
                if (!started || generation != cameraGeneration) return
                cameraFailureStage = CameraFailureStage.RUNNING
                if (combinationWorkflowProbe.onSessionConfigured(
                        device = device,
                        session = session,
                        previewSurface = previewSurface,
                        generation = generation,
                    )
                ) {
                    return
                }
                if (beginPendingSystemWorkflowProbe(
                        device = device,
                        session = session,
                        previewSurface = previewSurface,
                        generation = generation,
                    )
                ) {
                    return
                }
                val transaction = zoneRawTransaction
                if (transaction?.sessionState?.phase == ZoneRawSessionPhase.SWITCHING_TO_RAW &&
                    activeSessionProfile == CameraSessionProfile.RAW_ISOLATED
                ) {
                    if (!transaction.sessionState.markRawSessionConfigured()) return
                    transaction.rawSessionConfiguredAtNs = System.nanoTime()
                    postInfo(
                        cameraInfo.copy(
                            rawAvailable = true,
                            status = localized("正在读取 RAW", "Reading RAW"),
                        ),
                    )
                    startMeasurement(transaction.plan)
                    return
                }
                if (transaction == null && calibrationSessionProfile == null &&
                    pendingResidentSessionProfile == null
                ) {
                    val desiredProfile = desiredResidentSessionProfile()
                    if (desiredProfile != activeSessionProfile) {
                        switchResidentSession(desiredProfile)
                        return
                    }
                }
                val preview = previewSurface
                if (preview == null) {
                    onSessionConfigurationFailed(
                        generation,
                        IllegalStateException("Preview session configured without a preview Surface"),
                    )
                    return
                }
                pendingResidentSessionProfile = null
                if (startPreview(device, session, preview, generation) &&
                    transaction?.sessionState?.phase == ZoneRawSessionPhase.RESTORING
                ) {
                    completeZoneRawTransaction(transaction)
                }
            }

            override fun onSessionConfigurationFailed(generation: Int, error: Exception?) {
                if (generation != cameraGeneration) return
                if (error == null) {
                    Log.w(TAG, "Camera session configuration failed for profile=$sessionProfile")
                } else {
                    Log.e(TAG, "Unable to create camera session for profile=$sessionProfile", error)
                }
                if (combinationWorkflowProbe.cancel(
                        error ?: IllegalStateException("Camera stream workflow was rejected"),
                    )
                ) {
                    return
                }
                if (handleZoneRawSessionFailure(error) || handleResidentSessionFailure(error)) {
                    return
                }
                handleSessionFailure(generation)
            }

            override fun onCameraDisconnected(generation: Int) {
                if (generation != cameraGeneration) return
                handleCameraFailure(
                    CameraFailureKind.DISCONNECTED,
                    cameraFailureStage,
                    generation,
                )
            }

            override fun onCameraError(error: Int, generation: Int) {
                if (generation != cameraGeneration) return
                val failure = CameraFailureKind.fromDeviceError(error)
                Log.e(
                    TAG,
                    "Camera device callback error=$error kind=$failure " +
                        "stage=$cameraFailureStage profile=$sessionProfile generation=$generation",
                )
                handleCameraFailure(failure, cameraFailureStage, generation)
            }
        },
    )
    private val cameraCatalog = CameraCatalog(cameraManager)
    private val runtimeMetadata = CameraRuntimeMetadataCoordinator(cameraManager)
    private val openConfigurationResolver = CameraOpenConfigurationResolver(
        cameraManager = cameraManager,
        cameraCatalog = cameraCatalog,
        localized = ::localized,
    )
    private val distanceCapture: CameraDistanceCaptureCoordinator = CameraDistanceCaptureCoordinator(
        cameraHandler = { cameraHandler },
        cameraDevice = { cameraDevice },
        captureSession = { captureSession },
        previewSurface = { sessionCoordinator.previewSurface },
        characteristics = { characteristics },
        cameraInfo = { cameraInfo },
        cameraGeneration = { cameraGeneration },
        previewCaptureCallback = { previewCaptureCallback },
        onStateChanged = { state ->
            // Camera results arrive on the camera thread. Keep the legacy field diagnostic-only.
            cameraInfo = cameraInfo.copy(focusDistanceMeters = state.estimate?.meters?.toFloat())
            mainHandler.post { callback.onDistanceMeasurementState(state) }
        },
    )
    private val combinationSelectionStore = CameraCombinationSelectionStore(context)
    private val calibrationStore = CameraCalibrationStore(context)
    private val vignettingCalibrationStore = VignettingCalibrationStore(context)
    private val compatibleMeter = CompatibleLightMeter(
        mainHandler = mainHandler,
        calibrationStore = calibrationStore,
        localized = ::localized,
        listener = object : CompatibleLightMeterListener {
            override fun onCompatibleMeteringStarted(source: MeteringSource, frameCount: Int) {
                callback.onMeteringStarted(source, frameCount)
            }

            override fun onCompatibleMeteringReading(reading: MeterReading) {
                meteringOperationActive = false
                callback.onMeterReading(reading)
            }

            override fun onCompatibleMeteringError(message: String) {
                meteringOperationActive = false
                finishCompatibleYuvRequest()
                callback.onMeteringError(message)
            }

            override fun onCompatibleMeteringCompleted(requiresPreviewOnlySession: Boolean) {
                afterCompatibleMeasurement(
                    requiresPreviewOnlySession = requiresPreviewOnlySession,
                    calibrationRunActive = calibrationStorageCameraId != null,
                )
            }
        },
    )
    private val rawMeter = RawLightMeter(
        calibrationStore = calibrationStore,
        vignettingCalibrationStore = vignettingCalibrationStore,
        localized = ::localized,
        listener = object : RawLightMeterListener {
            override fun onRawCaptureResult(
                result: CaptureResult,
                totalResult: TotalCaptureResult,
            ) {
                val transaction = zoneRawTransaction
                updateActivePhysicalCamera(totalResult)
                val expectedPhysicalId = transaction?.expectedPhysicalCameraId
                val activePhysicalId = cameraInfo.activePhysicalCameraId
                if (ZoneSessionPolicy.physicalCameraChanged(expectedPhysicalId, activePhysicalId)) {
                    transaction?.physicalCameraChanged = true
                    Log.w(
                        TAG,
                        "Zone RAW changed physical camera expected=$expectedPhysicalId " +
                            "actual=$activePhysicalId; the RAW reading will be discarded",
                    )
                }
                latestResult = result
                updateDynamicLensInfo(result)
            }

            override fun onRawMeteringStarted(frameCount: Int) {
                mainHandler.post { callback.onMeteringStarted(MeteringSource.RAW, frameCount) }
            }

            override fun onRawMeteringReading(reading: MeterReading) {
                val transaction = zoneRawTransaction
                if (transaction?.sessionState?.phase == ZoneRawSessionPhase.METERING) {
                    if (transaction.physicalCameraChanged) {
                        transaction.failure = ZoneRawFailure(
                            message = localized(
                                "RAW 流切换了物理镜头，已改用当前预览流测光",
                                "RAW switched physical lenses; using the current preview stream",
                            ),
                            meteringMode = transaction.plan.meteringMode,
                            target = transaction.plan.target,
                            meteringRoiFraction = transaction.plan.meteringRoiFraction,
                        )
                    } else {
                        transaction.reading = reading
                        deliverZoneRawResult(transaction, reading)
                    }
                    restoreZoneResidentSession(transaction)
                    return
                }
                resumePreviewAfterRawCapture()
                meteringOperationActive = false
                recoveryState.recordRawMeasurementSucceeded()
                mainHandler.post { callback.onMeterReading(reading) }
            }

            override fun onRawZoneBatchReading(results: List<ZoneMeteringResult>) {
                val transaction = zoneRawTransaction
                if (transaction?.sessionState?.phase == ZoneRawSessionPhase.METERING) {
                    if (transaction.physicalCameraChanged) {
                        transaction.failure = ZoneRawFailure(
                            message = localized(
                                "RAW 流切换了物理镜头，本次批量测光未应用",
                                "RAW switched physical lenses; this batch was not applied",
                            ),
                            meteringMode = transaction.plan.meteringMode,
                            target = null,
                            meteringRoiFraction = transaction.plan.meteringRoiFraction,
                        )
                    } else {
                        transaction.batchResults = results
                        deliverZoneRawBatchResult(transaction, results)
                    }
                    restoreZoneResidentSession(transaction)
                    return
                }
                resumePreviewAfterRawCapture()
                meteringOperationActive = false
                recoveryState.recordRawMeasurementSucceeded()
                mainHandler.post { callback.onZoneMeteringBatchResult(results) }
            }

            override fun onRawMeteringError(
                message: String,
                meteringMode: MeteringMode,
                target: ZoneMeteringTarget?,
                meteringRoiFraction: Float?,
            ) {
                val transaction = zoneRawTransaction
                if (transaction?.sessionState?.phase == ZoneRawSessionPhase.METERING) {
                    transaction.failure = ZoneRawFailure(
                        message = message,
                        meteringMode = meteringMode,
                        target = target,
                        meteringRoiFraction = meteringRoiFraction,
                    )
                    restoreZoneResidentSession(transaction)
                    return
                }
                resumePreviewAfterRawCapture()
                val canUsePreview = textureView?.isAvailable == true &&
                    cameraDevice != null && captureSession != null
                if (canUsePreview) {
                    // A calibration-only RAW failure must not downgrade the user's normal
                    // session. The coordinator will record the failed source and continue with
                    // the dedicated YUV/ISP stages.
                    downgradeAfterCompatibleMeasurement = calibrationStorageCameraId == null &&
                        recoveryState.recordRawMeasurementFailed(meteringPipelineMode)
                    measureCompatiblePreview(meteringMode, target, meteringRoiFraction)
                } else {
                    meteringOperationActive = false
                    postMeterError(message)
                }
            }
        },
    )
    private val colorTemperatureEstimator = RawColorTemperatureEstimator(
        localized = ::localized,
        listener = object : RawColorTemperatureEstimatorListener {
            override fun onColorTemperatureCaptureResult(result: CaptureResult) {
                latestResult = result
                updateDynamicLensInfo(result)
            }

            override fun onColorTemperatureFinished(
                callback: (Result<ColorTemperatureReading>) -> Unit,
                result: Result<ColorTemperatureReading>,
            ) {
                resumePreviewAfterRawCapture()
                meteringOperationActive = false
                mainHandler.post { callback(result) }
            }
        },
    )
    private val rawRecordCapture = RawRecordCaptureCoordinator(
        localized = ::localized,
        cameraCharacteristics = { characteristics },
        cameraInfo = { cameraInfo },
        displayRotation = { lastDisplayRotation },
        listener = object : RawRecordCaptureListener {
            override fun onRawRecordCaptureResult(result: CaptureResult) {
                latestResult = result
            }

            override fun onRawRecordCaptureFinished(
                callback: (Result<RawRecordArtifact>) -> Unit,
                result: Result<RawRecordArtifact>,
            ) {
                meteringOperationActive = false
                resumePreviewAfterRawCapture()
                mainHandler.post { callback(result) }
            }
        },
    )
    private val vignettingCapture = VignettingCalibrationCaptureCoordinator(
        store = vignettingCalibrationStore,
        localized = ::localized,
        cameraCharacteristics = { characteristics },
        cameraInfo = { cameraInfo },
        listener = object : VignettingCalibrationCaptureListener {
            override fun onVignettingCaptureStarted() {
                mainHandler.post { callback.onVignettingCalibrationStarted() }
            }

            override fun onVignettingCaptureResult(
                result: TotalCaptureResult,
                effectiveResult: CaptureResult,
            ) {
                updateActivePhysicalCamera(result)
                latestResult = effectiveResult
            }

            override fun onVignettingCaptureFinished(info: VignettingCalibrationInfo) {
                resumePreviewAfterRawCapture()
                mainHandler.post { callback.onVignettingCalibrationCompleted(info) }
            }

            override fun onVignettingCaptureError(message: String) {
                resumePreviewAfterRawCapture()
                mainHandler.post { callback.onVignettingCalibrationError(message) }
            }
        },
    )

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var started = false
    private var stopInProgress = false
    private var restartAfterStop = false
    private val opening: Boolean
        get() = sessionCoordinator.isOpening
    @Volatile
    private var requestedCameraId: String? = null
    private var textureView: TextureView? = null
    private val cameraDevice: CameraDevice?
        get() = sessionCoordinator.device
    private val captureSession: CameraCaptureSession?
        get() = sessionCoordinator.session
    private val rawReader: ImageReader?
        get() = sessionCoordinator.rawReader
    private val trackingReader: ImageReader?
        get() = sessionCoordinator.trackingReader
    private var characteristics: CameraCharacteristics? = null
    private var combinationSelectionMode = MeteringCombinationSelectionMode.SYSTEM
    private var activeCombinationPlan: CameraCombinationPlan? = null
    private var manualProbePlanId: String? = null
    private val combinationWorkflowProbe = CameraCombinationWorkflowProbeRunner(
        mainHandler = mainHandler,
        cameraHandler = { cameraHandler },
        currentGeneration = { cameraGeneration },
        currentProfile = { activeSessionProfile },
        rawSurface = { rawReader?.surface },
        rawCharacteristics = { characteristics },
        expectedRawSize = { rawOutputSize },
        configureAutoFocus = distanceCapture::configureAutoFocus,
        startPreview = ::startPreview,
        reconfigure = ::reconfigureSession,
        onStageConfigured = { pendingResidentSessionProfile = null },
    )
    private var currentCombinationCandidates: List<CameraCombinationCandidate> = emptyList()
    private var systemCombinationCandidates: List<CameraCombinationCandidate> = emptyList()
    private val rejectedSystemCombinationIds = mutableSetOf<String>()
    private var pendingSystemWorkflowProbePlanId: String? = null
    private var logicalCharacteristics: CameraCharacteristics? = null
    private var selectedPhysicalCameraId: String? = null
    private var meteringPipelineMode = MeteringPipelineMode.AUTO
    @Volatile
    private var rawHardwareAvailable = false
    @Volatile
    private var trackingHardwareAvailable = false
    @Volatile
    private var absoluteExposureMetadataAvailable = false
    private val recoveryState = CameraRecoveryStateMachine(
        maxRecoveryAttempts = MAX_TOTAL_RECOVERY_ATTEMPTS,
        rawFailuresBeforeDowngrade = RAW_FAILURES_BEFORE_DOWNGRADE,
    )
    private val sessionProfile: CameraSessionProfile?
        get() = activeSessionProfile ?: calibrationSessionProfile ?: recoveryState.profile
    private var activeSessionProfile: CameraSessionProfile? = null
    private var cameraGeneration = 0
    private var calibrationSessionProfile: CameraSessionProfile? = null
    private var pendingCalibrationMeteringPlan: MeteringPlan? = null
    private val zoneRawTransactions = ZoneRawTransactionCoordinator()
    private val zoneRawTransaction: ZoneRawTransaction?
        get() = zoneRawTransactions.active
    private var pendingResidentSessionProfile: CameraSessionProfile? = null
    private var zoneYuvSessionUnavailable = false
    @Volatile
    private var calibrationStorageCameraId: String? = null
    private var cameraFailureStage = CameraFailureStage.OPENING
    @Volatile
    private var cameraInfo = CameraUiInfo()
    @Volatile
    private var previewSize: Size? = null
    private var previewStreamGeneration = 0L
    private var rawOutputSize: Size? = null
    private var trackingOutputSize: Size? = null
    private var previewFpsRange: Range<Int>? = null
    @Volatile
    private var previewOutputAspectOverride: Float? = null
    private val frameRateController = PreviewFrameRateController()
    @Volatile
    private var latestResult: CaptureResult? = null
    private val previewResultStore = TimestampedCaptureResultStore<CaptureResult>()
    private var trackingFramesEnabled = false
    private val exposurePreviewState = ExposurePreviewStateCoordinator(
        onRequestChanged = ::updatePreviewRepeatingRequest,
        onUnavailable = { mainHandler.post(callback::onExposurePreviewUnavailable) },
    )
    private val previewBaseline = MeteringPreviewBaselineCoordinator { cameraHandler }
    private val previewRequests: CameraPreviewRequestCoordinator = CameraPreviewRequestCoordinator(
        cameraHandler = { cameraHandler },
        cameraDevice = { cameraDevice },
        captureSession = { captureSession },
        previewSurface = { sessionCoordinator.previewSurface },
        trackingSurface = { trackingReader?.surface },
        trackingFramesEnabled = { trackingFramesEnabled },
        combinationProbeActive = { combinationWorkflowProbe.isActive },
        cameraGeneration = { cameraGeneration },
        neutralBaselineGeneration = { previewBaseline.activeGeneration },
        previewFpsRange = { previewFpsRange },
        characteristics = { characteristics },
        manualExposure = { exposurePreviewState.manualExposure },
        exposureCompensationSteps = { exposurePreviewState.compensationSteps },
        sessionProfile = { sessionProfile },
        configureAutoFocus = distanceCapture::configureAutoFocus,
        captureCallback = { previewCaptureCallback },
        onRequestFailure = ::handlePreviewRequestFailure,
    )
    private var downgradeAfterCompatibleMeasurement = false
    @Volatile
    private var meteringOperationActive = false
    private val zoneCameraFrames = ZoneCameraFramePipeline(
        reserveTracker = callback::tryReserveZoneTrackingFrame,
        cancelTrackerReservation = callback::cancelZoneTrackingFrameReservation,
        deliverToTracker = callback::onZoneTrackingFrame,
    )
    private val exposurePreviewCalibration = ExposurePreviewCalibrationCoordinator(
        mainHandler = mainHandler,
        cameraHandler = { cameraHandler },
        cameraGeneration = { cameraGeneration },
        previewRequestSequence = { previewRequests.requestSequence },
        latestResult = { latestResult },
        characteristics = { characteristics },
        cameraInfo = { cameraInfo },
        calibrationIdentity = { currentCalibrationIdentity() },
        calibrationCameraId = { calibrationCameraId() },
        applyPreview = exposurePreviewState::applyCalibrationComparison,
    )

    private val previewSurfaceCoordinator = PreviewSurfaceCoordinator(
        mainHandler = mainHandler,
        textureView = { textureView },
        previewSize = { previewSize },
        sensorOrientationDegrees = { cameraInfo.sensorOrientationDegrees },
        lensFacing = { cameraInfo.lensFacing },
    )
    private val lastViewWidth: Int get() = previewSurfaceCoordinator.viewWidth
    private val lastViewHeight: Int get() = previewSurfaceCoordinator.viewHeight
    private val lastDisplayRotation: Int get() = previewSurfaceCoordinator.displayRotation
    private val lastDisplayZoom: Float get() = previewSurfaceCoordinator.displayZoom
    private val previewHealth: CameraPreviewHealthCoordinator = CameraPreviewHealthCoordinator(
        mainHandler = mainHandler,
        cameraHandler = { cameraHandler },
        started = { started },
        cameraGeneration = { cameraGeneration },
        sessionTransitionActive = {
            zoneRawTransaction != null || pendingResidentSessionProfile != null
        },
        retryAtStandardFrameRate = {
            if ((previewFpsRange?.upper ?: 0) > PreviewFrameRateMode.LOW.requestedCeiling &&
                frameRateController.limitToStandardRate()
            ) {
                scheduleRecovery(
                    sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY,
                    localized(
                        "高帧率画面异常，正在保持当前组合并降低取景帧率",
                        "High-rate preview is invalid. Keeping this workflow and lowering FPS",
                    ),
                    delayMs = 0L,
                )
                true
            } else {
                false
            }
        },
        advanceSystemCombination = {
            advanceSystemCombination(
                localized(
                    "检测到当前组合画面异常，正在测试下一个组合",
                    "This combination produced an invalid preview. Testing the next one",
                ),
                delayMs = 0L,
            )
        },
        tryNextCameraRoute = ::tryNextCameraRoute,
        failSafePreview = {
            finishCameraFailure(
                localized(
                    "安全预览仍持续异常，请选择其他镜头或重启手机",
                    "Safe preview remains invalid. Choose another lens or restart the phone",
                ),
            )
        },
        failPreview = {
            finishCameraFailure(
                localized(
                    "相机预览持续输出异常，请选择其他镜头或重启手机",
                    "Camera preview output remains invalid. Choose another lens or restart the phone",
                ),
            )
        },
        scheduleSafePreviewRecovery = {
            scheduleRecovery(
                CameraSessionProfile.PREVIEW_ONLY,
                localized(
                    "检测到相机输出异常，正在切换到安全预览…",
                    "Camera output is invalid. Switching to a safe preview…",
                ),
                delayMs = 0L,
            )
        },
        markPreviewStable = recoveryState::markPreviewStable,
    )
    private var manualSafePreviewActive = false

    fun attach(texture: TextureView) {
        textureView = texture
        texture.surfaceTextureListener = this
    }

    fun setTrackingFramesEnabled(enabled: Boolean) {
        zoneCameraFrames.setEnabled(enabled, cameraHandler)
        val handler = cameraHandler
        if (handler == null) {
            trackingFramesEnabled = enabled
            return
        }
        handler.post {
            if (trackingFramesEnabled == enabled) return@post
            trackingFramesEnabled = enabled
            if (!started || calibrationSessionProfile != null ||
                zoneRawTransaction != null || meteringOperationActive
            ) {
                return@post
            }
            val desiredProfile = desiredResidentSessionProfile()
            if (cameraDevice != null && captureSession != null &&
                desiredProfile != activeSessionProfile
            ) {
                switchResidentSession(desiredProfile)
            } else {
                updatePreviewRepeatingRequest()
            }
        }
    }

    fun setMeteringPipelineMode(mode: MeteringPipelineMode) {
        val handler = cameraHandler
        if (handler == null) {
            if (meteringPipelineMode != mode) {
                meteringPipelineMode = mode
                resetRecoveryState()
            }
            return
        }
        handler.post {
            if (meteringPipelineMode == mode) return@post
            meteringPipelineMode = mode
            resetRecoveryState()
            if (!started) return@post
            closeCamera()
            postInfo(
                cameraInfo.copy(
                    rawAvailable = false,
                    status = localized("正在切换测光方式", "Switching metering mode"),
                ),
            )
            openCamera(textureView?.surfaceTexture)
        }
    }

    fun setCombinationSelectionMode(mode: MeteringCombinationSelectionMode) {
        combinationSelectionMode = mode
        if (mode == MeteringCombinationSelectionMode.SYSTEM) {
            manualProbePlanId = null
        }
    }

    internal fun manualCombinationCandidates(): List<CameraCombinationCandidate> =
        currentCombinationCandidates

    /** Replays every distinct session stage of the workflow before asking the user to judge it. */
    internal fun probeManualCombination(
        planId: String,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        val candidate = currentCombinationCandidates.firstOrNull { it.plan.id == planId }
            ?: return false
        val handler = cameraHandler ?: return false
        if (!started || meteringOperationActive || calibrationStorageCameraId != null ||
            vignettingCapture.isActive || combinationWorkflowProbe.isActive
        ) {
            return false
        }
        handler.post {
            if (!started || meteringOperationActive || calibrationStorageCameraId != null ||
                vignettingCapture.isActive || combinationWorkflowProbe.isActive
            ) {
                mainHandler.post {
                    completion(Result.failure(IllegalStateException("Camera is busy")))
                }
                return@post
            }
            val plan = candidate.plan
            manualProbePlanId = plan.id
            activeCombinationPlan = plan
            combinationWorkflowProbe.begin(plan, completion)
            manualSafePreviewActive = false
            closeCamera(preserveExposurePreview = true)
            val texture = textureView?.surfaceTexture
            if (texture == null) {
                combinationWorkflowProbe.cancel(
                    IllegalStateException("Preview Surface is unavailable"),
                )
            } else {
                openCamera(texture)
            }
        }
        return true
    }

    internal fun acceptManualCombination(planId: String): Boolean {
        val plan = currentCombinationCandidates.firstOrNull { it.plan.id == planId }?.plan
            ?: return false
        combinationSelectionMode = MeteringCombinationSelectionMode.MANUAL
        manualProbePlanId = null
        activeCombinationPlan = plan
        combinationSelectionStore.save(cameraInfo.cameraId, plan.id)
        cameraHandler?.post {
            if (started && !combinationWorkflowProbe.isActive && cameraDevice != null) {
                val desired = desiredResidentSessionProfile()
                if (desired != activeSessionProfile) switchResidentSession(desired)
            }
        }
        return true
    }

    internal fun cancelManualCombinationSelection() {
        val handler = cameraHandler ?: return
        handler.post {
            combinationWorkflowProbe.cancel(IllegalStateException("Cancelled"))
            manualProbePlanId = null
            resetRecoveryState()
            if (!started) return@post
            closeCamera(preserveExposurePreview = true)
            textureView?.surfaceTexture?.let(::openCamera)
        }
    }

    fun setPreviewHealthDetectionEnabled(enabled: Boolean) {
        previewHealth.setEnabled(enabled)
    }

    fun setPreviewFrameRateMode(mode: PreviewFrameRateMode) {
        val handler = cameraHandler
        if (handler == null) {
            frameRateController.setMode(mode)
            return
        }
        handler.post {
            if (!frameRateController.setMode(mode)) return@post
            if (!started) return@post
            val profile = activeSessionProfile ?: return@post
            if (!profile.usesPreview) return@post
            previewFpsRange = choosePreviewFpsRange(profile)
            val range = previewFpsRange
            val fps = range?.upper ?: cameraInfo.previewFps
            cameraInfo = cameraInfo.copy(
                previewFps = fps,
                previewFpsLower = range?.lower ?: fps,
                previewFpsUpper = fps,
            )
            Log.i(
                TAG,
                "Viewfinder frame-rate preference=$mode lowLight=${frameRateController.isLowLight} " +
                    "selected=$previewFpsRange " +
                    "profile=$profile",
            )
            updatePreviewRepeatingRequest()
            postInfo(cameraInfo.copy(status = readyCameraStatus()))
        }
    }

    fun setPreviewOutputAspectOverride(aspect: Float?) {
        val normalized = aspect
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let { max(it, 1f / it).coerceIn(1f, 4f) }
        val update: () -> Unit = {
            val current = previewOutputAspectOverride
            val changed = !(
                current == null && normalized == null ||
                    current != null && normalized != null && abs(current - normalized) < 0.0001f
                )
            if (changed) {
                previewOutputAspectOverride = normalized
                latestResult?.let { result ->
                    runtimeMetadata.observePreviewSensorViewport(
                        result = result,
                        currentInfo = cameraInfo,
                        previewSize = previewSize,
                        outputAspectOverride = previewOutputAspectOverride,
                    )?.let(::postRuntimeInfo)
                }
            }
            // Camera selection and session replacement may leave TextureView dimensions unchanged.
            // Reapply the matrix even for the same aspect so geometry cannot remain stale.
            updatePreviewTransform(
                lastViewWidth,
                lastViewHeight,
                lastDisplayRotation,
                lastDisplayZoom,
            )
        }
        cameraHandler?.post(update) ?: update()
    }

    /** Starts a fresh Camera2 AF sampling run for the flash Auto distance control. */
    fun requestAutomaticDistance() {
        cameraHandler?.post {
            if (!started) return@post
            distanceCapture.beginAutomaticSampling()
        }
    }

    fun stopAutomaticDistance() {
        cameraHandler?.post { distanceCapture.stop() }
    }

    /** Uses only the display SurfaceTexture for this controller run; camera selection resets it. */
    fun switchToSafePreview(): Boolean {
        val handler = cameraHandler ?: return false
        if (!started || meteringOperationActive || calibrationStorageCameraId != null ||
            vignettingCapture.isActive || rawRecordCapture.isActive
        ) {
            return false
        }
        handler.post {
            if (!started || meteringOperationActive || calibrationStorageCameraId != null ||
                vignettingCapture.isActive || rawRecordCapture.isActive
            ) {
                return@post
            }
            closeCamera()
            recoveryState.forceProfile(CameraSessionProfile.PREVIEW_ONLY)
            manualSafePreviewActive = true
            previewHealth.resetRecoveryState()
            postInfo(
                cameraInfo.copy(
                    rawAvailable = false,
                    status = localized(
                        "已手动切换到安全预览",
                        "Safe preview selected manually",
                    ),
                ),
            )
            openCamera(textureView?.surfaceTexture)
        }
        return true
    }

    /** Applies the calibrated exposure represented by the currently displayed parameter rows. */
    fun updateExposurePreview(selection: ExposurePreviewSelection?) {
        exposurePreviewState.updateSelection(selection)
        cameraHandler?.post(::applyRequestedExposurePreview)
    }

    /**
     * Starts an auxiliary comparison against a fresh Camera2 AE 0-EV reference. This flow does not
     * read or modify RAW/YUV/ISP metering calibration.
     */
    fun beginExposurePreviewCalibration(onReady: (Boolean) -> Unit): Boolean {
        if (meteringOperationActive || calibrationSessionProfile != null ||
            vignettingCapture.isActive || rawRecordCapture.isActive
        ) {
            return false
        }
        val handler = cameraHandler ?: return false
        handler.post {
            if (!started || captureSession == null || cameraDevice == null ||
                meteringOperationActive || calibrationSessionProfile != null
            ) {
                mainHandler.post { onReady(false) }
                return@post
            }
            exposurePreviewState.clearRequestedSelection()
            exposurePreviewCalibration.begin(onReady)
        }
        return true
    }

    /** Null displays the 0-EV AE reference; a value displays the manually corrected preview. */
    fun updateExposurePreviewCalibrationComparison(correctionEv: Double?) {
        cameraHandler?.post {
            exposurePreviewCalibration.updateComparison(correctionEv)
        }
    }

    fun finishExposurePreviewCalibration() {
        cameraHandler?.post(exposurePreviewCalibration::finish)
    }

    @Synchronized
    fun saveExposurePreviewCalibration(correctionEv: Double): Double {
        val cameraId = exposurePreviewCalibration.cameraId ?: calibrationCameraId()
        val saved = calibrationStore.saveExposurePreviewCorrection(cameraId, correctionEv)
        Log.i(TAG, "Exposure preview calibration saved: camera=$cameraId correction=$saved EV")
        return saved
    }

    @Synchronized
    fun resetExposurePreviewCalibration(cameraId: String = calibrationCameraId()) {
        calibrationStore.resetExposurePreviewCorrection(cameraId)
        Log.i(TAG, "Exposure preview calibration reset: camera=$cameraId")
    }

    fun availableCameras(): List<CameraDescriptor> = cameraCatalog.discover().also { cameras ->
        Log.i(
            TAG,
            "Camera catalog (${cameras.size}): " + cameras.joinToString { camera ->
                "${camera.cameraId}[logical=${camera.logicalCameraId}, " +
                    "physical=${camera.physicalCameraId}, role=${camera.lensRole}, " +
                    "focal=${camera.focalLengthMm}, raw=${camera.rawAvailable}]"
            },
        )
    }

    fun selectCamera(cameraId: String) {
        if (cameraId.isBlank()) return
        requestedCameraId = cameraId
        val handler = cameraHandler ?: return
        handler.post {
            if (!started || cameraInfo.cameraId == cameraId && cameraDevice != null) return@post
            resetRecoveryState()
            closeCamera()
            val pending = cameraCatalog.discover().firstOrNull { it.cameraId == cameraId }
            postInfo(
                CameraUiInfo(
                    cameraId = cameraId,
                    logicalCameraId = pending?.logicalCameraId ?: cameraId,
                    physicalCameraId = pending?.physicalCameraId,
                    status = localized("正在切换摄像头", "Switching camera"),
                ),
            )
            openCamera(textureView?.surfaceTexture)
        }
    }

    fun start() {
        if (started) return
        if (stopInProgress) {
            restartAfterStop = true
            return
        }
        // The foreground request may have arrived while the previous camera thread was still
        // closing. Arm this again on the actual fresh start so that race cannot consume the
        // SurfaceTexture recommit request.
        previewSurfaceCoordinator.armForFreshStart()
        started = true
        previewHealth.refreshMonitoring()
        // A new foreground lifecycle is a fresh capability probe. Session downgrades remain
        // sticky only for the current run, preventing a transient HAL failure from permanently
        // hiding RAW or YUV until the user manually changes a setting.
        resetRecoveryState()
        val thread = HandlerThread("raw-meter-camera").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
        val texture = textureView
        if (texture?.isAvailable == true) openCamera(texture.surfaceTexture)
    }

    fun stop() {
        restartAfterStop = false
        if (stopInProgress) return
        started = false
        val handler = cameraHandler
        val thread = cameraThread
        if (handler != null && thread != null) {
            stopInProgress = true
            handler.post {
                closeCamera()
                thread.quitSafely()
                mainHandler.post {
                    if (cameraHandler !== handler) return@post
                    cameraHandler = null
                    cameraThread = null
                    stopInProgress = false
                    if (restartAfterStop) {
                        restartAfterStop = false
                        start()
                    }
                }
            }
        } else {
            closeCamera()
            cameraThread = null
            cameraHandler = null
        }
    }

    fun updatePreviewTransform(
        viewWidth: Int,
        viewHeight: Int,
        displayRotation: Int,
        displayZoom: Float,
    ) {
        previewSurfaceCoordinator.update(viewWidth, viewHeight, displayRotation, displayZoom)
    }

    /**
     * Re-synchronizes the surviving TextureView after the Activity returns to the foreground.
     * SurfaceTexture buffer geometry is UI transport state, not a Camera2 session choice, so this
     * does not merge the isolated RAW/YUV paths or change any HAL fallback decision.
     */
    fun prepareForForegroundPreview(
        viewWidth: Int,
        viewHeight: Int,
        displayRotation: Int,
        displayZoom: Float,
    ) {
        previewSurfaceCoordinator.prepareForForeground(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            displayRotation = displayRotation,
            displayZoom = displayZoom,
            cameraHandler = cameraHandler,
        )
    }

    fun measure(
        frameFormat: FrameFormat,
        frameLandscape: Boolean,
        displayZoom: Float,
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget? = null,
        meteringAngleDegrees: Int = AngleMeteringMath.DEFAULT_DEGREES,
        requestedSource: MeteringSource? = null,
    ): Boolean {
        if (meteringOperationActive || combinationWorkflowProbe.isActive) return false
        meteringOperationActive = true
        val screenAspect = if (frameLandscape) {
            frameFormat.landscapeAspect
        } else {
            1f / frameFormat.landscapeAspect
        }
        val sensorOrientation =
            characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: cameraInfo.sensorOrientationDegrees
        val sensorFrameAspect = CameraPreviewTransform.screenAspectInSensorCoordinates(
            screenAspect = screenAspect,
            sensorOrientationDegrees = sensorOrientation,
            displayRotation = lastDisplayRotation,
            lensFacing = cameraInfo.lensFacing,
        )
        val meteringRoiFraction = if (meteringMode == MeteringMode.ANGLE) {
            AngleMeteringMath.physicalRoiFraction(
                angleDegrees = meteringAngleDegrees,
                focalLengthMm = cameraInfo.focalLengthMm.toDouble(),
                sensorWidthMm = cameraInfo.sensorWidthMm.toDouble(),
                sensorHeightMm = cameraInfo.sensorHeightMm.toDouble(),
                sensorFrameAspect = sensorFrameAspect.toDouble(),
            )
        } else {
            null
        }
        val displayedPreviewReference = target?.let(::captureDisplayedPreviewReference)
        val plan = MeteringPlan(
            frameFormat = frameFormat,
            displayZoom = displayZoom,
            meteringMode = meteringMode,
            target = target,
            meteringAngleDegrees = meteringAngleDegrees,
            requestedSource = requestedSource,
            screenAspect = screenAspect,
            sensorOrientation = sensorOrientation,
            sensorFrameAspect = sensorFrameAspect,
            meteringRoiFraction = meteringRoiFraction,
            displayedPreviewReference = displayedPreviewReference,
        )
        val handler = cameraHandler ?: run {
            meteringOperationActive = false
            callback.onMeteringError(localized("相机尚未就绪", "Camera is not ready"))
            return true
        }
        handler.post {
            // Each metering pass gets a new AF distance acquisition. It is intentionally
            // independent of flash Auto so future distance consumers share the same result.
            distanceCapture.beginAutomaticSampling()
            startMeteringPlan(plan)
        }
        return true
    }

    /** Freezes every visible Zone point and analyzes one shared RAW capture sequence. */
    fun measureZoneBatch(
        frameFormat: FrameFormat,
        frameLandscape: Boolean,
        displayZoom: Float,
        meteringMode: MeteringMode,
        requests: List<ZoneMeteringRequest>,
        meteringAngleDegrees: Int = AngleMeteringMath.DEFAULT_DEGREES,
    ): Boolean {
        if (requests.isEmpty() || meteringOperationActive || combinationWorkflowProbe.isActive) {
            return false
        }
        meteringOperationActive = true
        val screenAspect = if (frameLandscape) {
            frameFormat.landscapeAspect
        } else {
            1f / frameFormat.landscapeAspect
        }
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: cameraInfo.sensorOrientationDegrees
        val sensorFrameAspect = CameraPreviewTransform.screenAspectInSensorCoordinates(
            screenAspect = screenAspect,
            sensorOrientationDegrees = sensorOrientation,
            displayRotation = lastDisplayRotation,
            lensFacing = cameraInfo.lensFacing,
        )
        val meteringRoiFraction = if (meteringMode == MeteringMode.ANGLE) {
            AngleMeteringMath.physicalRoiFraction(
                angleDegrees = meteringAngleDegrees,
                focalLengthMm = cameraInfo.focalLengthMm.toDouble(),
                sensorWidthMm = cameraInfo.sensorWidthMm.toDouble(),
                sensorHeightMm = cameraInfo.sensorHeightMm.toDouble(),
                sensorFrameAspect = sensorFrameAspect.toDouble(),
            )
        } else {
            null
        }
        val displayedReferences = captureDisplayedPreviewReferences(requests)
        val plan = MeteringPlan(
            frameFormat = frameFormat,
            displayZoom = displayZoom,
            meteringMode = meteringMode,
            target = null,
            meteringAngleDegrees = meteringAngleDegrees,
            requestedSource = null,
            screenAspect = screenAspect,
            sensorOrientation = sensorOrientation,
            sensorFrameAspect = sensorFrameAspect,
            meteringRoiFraction = meteringRoiFraction,
            displayedPreviewReference = null,
            zoneBatchTargets = requests.map { request ->
                ZoneRawBatchTarget(
                    markerId = request.markerId,
                    target = request.target,
                    previewReference = displayedReferences[request.markerId],
                )
            },
        )
        val handler = cameraHandler ?: run {
            meteringOperationActive = false
            callback.onMeteringError(localized("相机尚未就绪", "Camera is not ready"))
            return true
        }
        handler.post {
            if (!rawHardwareAvailable || manualSafePreviewActive) {
                meteringOperationActive = false
                postMeterError(
                    localized(
                        "当前摄像头无法使用 RAW 批量重新测光",
                        "RAW batch remeasurement is unavailable for this camera",
                    ),
                )
                return@post
            }
            distanceCapture.beginAutomaticSampling()
            startMeteringPlan(plan)
        }
        return true
    }

    /** Routes calibration captures through single-source sessions before restoring neutral AE. */
    private fun startMeteringPlan(plan: MeteringPlan) {
        if (pendingResidentSessionProfile != null) {
            meteringOperationActive = false
            postMeterError(localized("相机会话正在切换，请稍候", "The camera session is switching"))
            return
        }
        val requestedSource = plan.requestedSource
        if (requestedSource != null && calibrationStorageCameraId == null) {
            calibrationStorageCameraId = calibrationCameraId()
            Log.i(
                TAG,
                "Calibration run pinned route=${currentCalibrationIdentity()} " +
                    "storage=$calibrationStorageCameraId",
            )
        }
        val calibrationProfile = requestedSource?.let(CalibrationSessionProfilePolicy::profileFor)
        if (calibrationProfile != null && (sessionProfile != calibrationProfile ||
                cameraDevice == null || captureSession == null)
        ) {
            switchCalibrationSession(calibrationProfile, plan)
            return
        }
        meteringOperationActive = true
        prepareMeteringPreviewBaseline {
            if (plan.zoneBatchTargets.isNotEmpty()) {
                beginZoneRawTransaction(plan)
                return@prepareMeteringPreviewBaseline
            }
            val workflowMeteringProfile = activeCombinationPlan?.let { workflow ->
                if (trackingFramesEnabled) workflow.zoneMeteringProfile
                else workflow.normalMeteringProfile
            }
            val useTransientRaw = plan.requestedSource == null &&
                workflowMeteringProfile == CameraSessionProfile.RAW_ISOLATED &&
                rawHardwareAvailable && !manualSafePreviewActive
            if (useTransientRaw || workflowMeteringProfile == null &&
                ZoneSessionPolicy.shouldUseTransientRaw(
                    zoneActive = trackingFramesEnabled,
                    requestedSource = plan.requestedSource,
                    pipelineMode = meteringPipelineMode,
                    rawSupported = rawHardwareAvailable &&
                        recoveryState.profile?.usesRaw == true,
                    manualSafePreview = manualSafePreviewActive,
                )
            ) {
                beginZoneRawTransaction(plan)
            } else {
                startMeasurement(plan)
            }
        }
    }

    private fun switchCalibrationSession(
        profile: CameraSessionProfile,
        plan: MeteringPlan,
    ) {
        val texture = textureView?.surfaceTexture
        if (!started || texture == null) {
            meteringOperationActive = false
            postMeterError(localized("相机尚未就绪", "Camera is not ready"))
            return
        }
        pendingCalibrationMeteringPlan = plan
        calibrationSessionProfile = profile
        Log.i(TAG, "Switching to isolated calibration session profile=$profile source=${plan.requestedSource}")
        closeCamera(preserveExposurePreview = true)
        openCamera(texture)
    }

    /** Freezes the Zone reference, then swaps preview + YUV for one short RAW-only session. */
    private fun beginZoneRawTransaction(plan: MeteringPlan) {
        if (!started || cameraDevice == null || captureSession == null || rawOutputSize == null) {
            meteringOperationActive = false
            postMeterError(localized("RAW 流尚未就绪", "The RAW stream is not ready"))
            return
        }
        val frozenPlan = freezeZoneReference(plan)
        val transaction = zoneRawTransactions.begin(
            plan = frozenPlan,
            expectedPhysicalCameraId = cameraInfo.activePhysicalCameraId,
            residentCameraInfo = cameraInfo,
            residentCharacteristics = characteristics,
        )
        pendingResidentSessionProfile = null
        previewRequests.resetCaptureState()
        postInfo(
            cameraInfo.copy(
                rawAvailable = false,
                status = localized("正在切换到 RAW 测光", "Switching to RAW metering"),
            ),
        )
        if (!reconfigureSession(CameraSessionProfile.RAW_ISOLATED)) {
            transaction.failure = ZoneRawFailure(
                message = localized("无法启动 RAW 测光，请重试", "Unable to start RAW metering"),
                meteringMode = plan.meteringMode,
                target = plan.target,
                meteringRoiFraction = plan.meteringRoiFraction,
            )
            restoreZoneResidentSession(transaction)
        }
    }

    private fun freezeZoneReference(plan: MeteringPlan): MeteringPlan {
        val recentFrame = zoneCameraFrames.latestFrame(MAX_METERING_REFERENCE_AGE_NS)
            ?: return plan
        if (plan.zoneBatchTargets.isNotEmpty()) {
            return plan.copy(
                zoneBatchTargets = plan.zoneBatchTargets.map { batchTarget ->
                    batchTarget.copy(
                        previewReference = MeteringAnalysis.createPreviewReference(
                            frame = recentFrame,
                            frameAspect = plan.screenAspect,
                            zoom = plan.displayZoom,
                            target = batchTarget.target,
                        ) ?: batchTarget.previewReference,
                    )
                },
            )
        }
        val target = plan.target ?: return plan
        val reference = MeteringAnalysis.createPreviewReference(
            frame = recentFrame,
            frameAspect = plan.screenAspect,
            zoom = plan.displayZoom,
            target = target,
        ) ?: return plan
        return plan.copy(displayedPreviewReference = reference)
    }

    private fun desiredResidentSessionProfile(): CameraSessionProfile {
        val calibrationProfile = calibrationSessionProfile
        if (calibrationProfile != null) return calibrationProfile
        if (manualSafePreviewActive) return CameraSessionProfile.PREVIEW_ONLY
        activeCombinationPlan?.let { plan ->
            return if (trackingFramesEnabled) plan.zoneResidentProfile
            else plan.normalResidentProfile
        }
        val normalProfile = recoveryState.resolveProfile(
            mode = meteringPipelineMode,
            rawSupported = rawHardwareAvailable,
            trackingSupported = trackingHardwareAvailable,
        )
        return ZoneSessionPolicy.residentProfile(
            normalProfile = normalProfile,
            zoneActive = trackingFramesEnabled,
            manualSafePreview = manualSafePreviewActive,
            trackingSupported = trackingHardwareAvailable,
            zoneYuvUnavailable = zoneYuvSessionUnavailable,
        )
    }

    private fun switchResidentSession(profile: CameraSessionProfile) {
        if (!started || cameraDevice == null) return
        pendingResidentSessionProfile = profile
        postInfo(
            cameraInfo.copy(
                rawAvailable = profile.usesRaw && rawHardwareAvailable,
                status = if (trackingFramesEnabled) {
                    localized("正在准备 Zone 跟踪", "Preparing Zone tracking")
                } else {
                    localized("正在恢复测光会话", "Restoring the metering session")
                },
            ),
        )
        if (!reconfigureSession(profile)) {
            val error = IllegalStateException("Unable to replace the resident camera session")
            if (!handleResidentSessionFailure(error)) {
                handleSessionFailure(cameraGeneration)
            }
        }
    }

    private fun reconfigureSession(profile: CameraSessionProfile): Boolean {
        val handler = cameraHandler ?: return false
        if (!started || cameraDevice == null) return false
        activeSessionProfile = profile
        cameraFailureStage = CameraFailureStage.CONFIGURING
        previewFpsRange = choosePreviewFpsRange(profile)
        return try {
            sessionCoordinator.reconfigure(
                profile = profile,
                rawSize = rawOutputSize,
                trackingSize = trackingOutputSize,
                physicalCameraId = selectedPhysicalCameraId,
                generation = cameraGeneration,
                handler = handler,
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to reconfigure camera session profile=$profile", error)
            false
        }
    }

    private fun beginPendingSystemWorkflowProbe(
        device: CameraDevice,
        session: CameraCaptureSession,
        previewSurface: Surface?,
        generation: Int,
    ): Boolean {
        if (combinationSelectionMode != MeteringCombinationSelectionMode.SYSTEM ||
            calibrationSessionProfile != null || manualSafePreviewActive
        ) {
            return false
        }
        val planId = pendingSystemWorkflowProbePlanId ?: return false
        val plan = activeCombinationPlan?.takeIf { it.id == planId } ?: return false
        combinationWorkflowProbe.begin(
            plan = plan,
            completion = { result ->
                cameraHandler?.post {
                    if (pendingSystemWorkflowProbePlanId != plan.id) return@post
                    pendingSystemWorkflowProbePlanId = null
                    if (result.isSuccess) {
                        combinationSelectionStore.saveSystem(
                            cameraRouteId = cameraInfo.cameraId,
                            mode = meteringPipelineMode,
                            planId = plan.id,
                        )
                        postInfo(cameraInfo.copy(status = readyCameraStatus()))
                    } else {
                        advanceSystemCombination(
                            localized(
                                "组合流程检测失败，正在测试下一个组合",
                                "Combination workflow failed. Testing the next combination",
                            ),
                        )
                    }
                }
            },
        )
        postInfo(
            cameraInfo.copy(
                status = localized(
                    "正在检测相机输出组合",
                    "Checking camera output combination",
                ),
            ),
        )
        combinationWorkflowProbe.onSessionConfigured(
            device = device,
            session = session,
            previewSurface = previewSurface,
            generation = generation,
        )
        return true
    }

    private fun restoreZoneResidentSession(transaction: ZoneRawTransaction) {
        if (!zoneRawTransactions.isActive(transaction)) return
        if (!transaction.sessionState.beginRestore()) return
        transaction.restoreStartedAtNs = System.nanoTime()
        previewRequests.resetCaptureState()
        val residentProfile = desiredResidentSessionProfile()
        characteristics = transaction.residentCharacteristics
        postInfo(
            transaction.residentCameraInfo.copy(
                rawAvailable = residentProfile.usesRaw && rawHardwareAvailable,
                status = localized("正在恢复预览", "Restoring preview"),
            ),
        )
        if (!reconfigureSession(residentProfile)) {
            failZoneRawRestore(transaction)
        }
    }

    private fun completeZoneRawTransaction(transaction: ZoneRawTransaction) {
        if (!zoneRawTransactions.isActive(transaction)) return
        if (!transaction.sessionState.markResidentSessionConfigured()) return
        transaction.restoreCompletedAtNs = System.nanoTime()
        if (!zoneRawTransactions.clear(transaction)) return
        if (transaction.resultDelivered) {
            meteringOperationActive = false
            recoveryState.recordRawMeasurementSucceeded()
            logZoneRawLatency(transaction)
            mainHandler.post { callback.onMeteringRestoreStateChanged(false) }
            return
        }
        val failure = transaction.failure ?: ZoneRawFailure(
            message = localized("RAW 测光失败，请重试", "RAW metering failed. Please try again"),
            meteringMode = transaction.plan.meteringMode,
            target = transaction.plan.target,
            meteringRoiFraction = transaction.plan.meteringRoiFraction,
        )
        if (transaction.plan.zoneBatchTargets.isNotEmpty()) {
            meteringOperationActive = false
            postMeterError(failure.message)
            return
        }
        downgradeAfterCompatibleMeasurement = calibrationStorageCameraId == null &&
            recoveryState.recordRawMeasurementFailed(meteringPipelineMode)
        measureCompatiblePreview(
            failure.meteringMode,
            failure.target,
            failure.meteringRoiFraction,
        )
    }

    private fun handleZoneRawSessionFailure(error: Exception?): Boolean {
        val transaction = zoneRawTransaction ?: return false
        return when (transaction.sessionState.phase) {
            ZoneRawSessionPhase.SWITCHING_TO_RAW -> {
                transaction.failure = transaction.failure ?: ZoneRawFailure(
                    message = localized(
                        "当前设备无法启动独立 RAW 流，已恢复预览流测光",
                        "This device could not start isolated RAW; preview metering was restored",
                    ),
                    meteringMode = transaction.plan.meteringMode,
                    target = transaction.plan.target,
                    meteringRoiFraction = transaction.plan.meteringRoiFraction,
                )
                Log.w(TAG, "Isolated Zone RAW session failed; restoring resident session", error)
                restoreZoneResidentSession(transaction)
                true
            }
            ZoneRawSessionPhase.RESTORING -> {
                Log.e(TAG, "Unable to restore resident session after Zone RAW", error)
                failZoneRawRestore(transaction)
                true
            }
            ZoneRawSessionPhase.METERING,
            ZoneRawSessionPhase.COMPLETE,
            -> false
        }
    }

    private fun failZoneRawRestore(transaction: ZoneRawTransaction) {
        if (!zoneRawTransactions.clear(transaction)) return
        pendingResidentSessionProfile = null
        meteringOperationActive = false
        if (transaction.resultDelivered) {
            logZoneRawLatency(transaction)
            mainHandler.post { callback.onMeteringRestoreStateChanged(false) }
        }
        postMeterError(
            transaction.failure?.message
                ?: localized("无法恢复相机预览，本次测光已中止", "Unable to restore preview; metering stopped"),
        )
        if (started) {
            scheduleRecovery(
                CameraSessionProfile.PREVIEW_ONLY,
                localized("正在恢复安全预览", "Restoring safe preview"),
                delayMs = 0L,
            )
        }
    }

    /** Delivers one reliable Zone point before restoring preview/YUV; the operation remains locked. */
    private fun deliverZoneRawResult(transaction: ZoneRawTransaction, reading: MeterReading) {
        if (transaction.resultDelivered || !zoneRawTransactions.isActive(transaction)) return
        transaction.resultDelivered = true
        transaction.resultReadyAtNs = System.nanoTime()
        mainHandler.post {
            callback.onMeteringRestoreStateChanged(true)
            callback.onMeterReading(reading)
        }
    }

    private fun deliverZoneRawBatchResult(
        transaction: ZoneRawTransaction,
        results: List<ZoneMeteringResult>,
    ) {
        if (transaction.resultDelivered || !zoneRawTransactions.isActive(transaction)) return
        transaction.resultDelivered = true
        transaction.resultReadyAtNs = System.nanoTime()
        mainHandler.post {
            callback.onMeteringRestoreStateChanged(true)
            callback.onZoneMeteringBatchResult(results)
        }
    }

    private fun logZoneRawLatency(transaction: ZoneRawTransaction) {
        val requested = transaction.plan.requestedAtNs
        val rawConfigured = transaction.rawSessionConfiguredAtNs
        val resultReady = transaction.resultReadyAtNs
        val restoreStarted = transaction.restoreStartedAtNs
        val restoreCompleted = transaction.restoreCompletedAtNs
        fun elapsed(from: Long?, to: Long?): Long? =
            if (from == null || to == null || to < from) null else (to - from) / 1_000_000L
        Log.i(
            TAG,
            "Zone RAW latency tapToRawConfigMs=${elapsed(requested, rawConfigured)} " +
                "tapToResultMs=${elapsed(requested, resultReady)} " +
                "rawResultToRestoreMs=${elapsed(resultReady, restoreStarted)} " +
                "restoreMs=${elapsed(restoreStarted, restoreCompleted)} " +
                "tapToReadyMs=${elapsed(requested, restoreCompleted)} " +
                "resultDelivered=${transaction.resultDelivered}",
        )
    }

    private fun handleResidentSessionFailure(error: Exception?): Boolean {
        val failedProfile = pendingResidentSessionProfile ?: return false
        pendingResidentSessionProfile = null
        if (failedProfile == CameraSessionProfile.COMPATIBLE && trackingFramesEnabled) {
            zoneYuvSessionUnavailable = true
            Log.w(TAG, "Zone YUV resident session failed; falling back to preview-only", error)
            switchResidentSession(CameraSessionProfile.PREVIEW_ONLY)
            return true
        }
        return false
    }

    /** Restores the profile selected by the user's normal metering mode after a calibration run. */
    fun finishCalibrationSession() {
        cameraHandler?.post {
            pendingCalibrationMeteringPlan = null
            val storageCameraId = calibrationStorageCameraId
            calibrationStorageCameraId = null
            if (calibrationSessionProfile == null) {
                if (storageCameraId != null) {
                    Log.i(TAG, "Calibration run released storage=$storageCameraId without session restore")
                }
                return@post
            }
            calibrationSessionProfile = null
            if (!started) return@post
            val texture = textureView?.surfaceTexture ?: return@post
            Log.i(TAG, "Restoring normal metering session after calibration")
            closeCamera(preserveExposurePreview = true)
            openCamera(texture)
        }
    }

    private fun startMeasurement(plan: MeteringPlan) {
        if (plan.zoneBatchTargets.isNotEmpty() && !cameraInfo.rawAvailable) {
            finishMeasurementStartFailure(
                plan,
                localized(
                    "当前摄像头无法读取 RAW 流",
                    "The RAW stream is unavailable for this camera",
                ),
            )
            return
        }
        if (plan.requestedSource == MeteringSource.ISP_PREVIEW) {
            measureProcessedPreview(plan.meteringMode, plan.target, plan.meteringRoiFraction)
            return
        }
        if (plan.requestedSource == MeteringSource.YUV_PREVIEW ||
            plan.requestedSource == null && !cameraInfo.rawAvailable
        ) {
            measureCompatiblePreview(plan.meteringMode, plan.target, plan.meteringRoiFraction)
            return
        }
        if (!cameraInfo.rawAvailable) {
            finishMeasurementStartFailure(
                plan,
                localized(
                    "当前摄像头无法读取 RAW 流",
                    "The RAW stream is unavailable for this camera",
                ),
            )
            return
        }
        if (rawMeter.isMeasuring || vignettingCapture.isActive) {
            finishMeasurementStartFailure(
                plan,
                localized("请等待当前操作完成", "Wait for the current operation"),
            )
            return
        }
        val rawContext = rawMeteringContext()
        if (rawContext == null || !cameraInfo.rawAvailable) {
            finishMeasurementStartFailure(
                plan,
                localized(
                    "RAW 流尚未就绪",
                    "The RAW stream is not ready",
                ),
            )
            return
        }

        val screenToSensorTransform = ScreenToSensorCoordinateTransform(
            rotationDegrees = CameraPreviewTransform.relativeRotationDegrees(
                plan.sensorOrientation,
                lastDisplayRotation,
                cameraInfo.lensFacing,
            ),
            mirrored = CameraPreviewTransform.shouldMirrorPreview(cameraInfo.lensFacing),
            sensorViewport = cameraInfo.previewSensorViewport,
        )
        val recentTrackingFrame = zoneCameraFrames.latestFrame(MAX_METERING_REFERENCE_AGE_NS)
        val previewReference = if (plan.target != null && recentTrackingFrame != null) {
            MeteringAnalysis.createPreviewReference(
                frame = recentTrackingFrame,
                frameAspect = plan.screenAspect,
                zoom = plan.displayZoom,
                target = plan.target,
            ) ?: plan.displayedPreviewReference
        } else {
            plan.displayedPreviewReference
        }
        Log.i(
            TAG,
            "Starting RAW metering for frame format=${plan.frameFormat.id} " +
                "mode=${plan.meteringMode} angle=${plan.meteringAngleDegrees} " +
                "roi=${plan.meteringRoiFraction}",
        )
        pausePreviewForRawCapture()
        val accepted = if (plan.zoneBatchTargets.isNotEmpty()) {
            rawMeter.startBatch(
                context = rawContext,
                frameAspect = plan.sensorFrameAspect,
                zoom = plan.displayZoom.coerceAtLeast(1f),
                meteringMode = plan.meteringMode,
                meteringRoiFraction = plan.meteringRoiFraction,
                targets = plan.zoneBatchTargets,
                screenToSensorTransform = screenToSensorTransform,
            )
        } else {
            rawMeter.start(
                context = rawContext,
                frameAspect = plan.sensorFrameAspect,
                zoom = plan.displayZoom.coerceAtLeast(1f),
                meteringMode = plan.meteringMode,
                meteringRoiFraction = plan.meteringRoiFraction,
                target = plan.target,
                previewReference = previewReference,
                screenToSensorTransform = screenToSensorTransform,
            )
        }
        if (!accepted) {
            resumePreviewAfterRawCapture()
            finishMeasurementStartFailure(
                plan,
                localized("请等待当前操作完成", "Wait for the current operation"),
            )
        }
    }

    private fun finishMeasurementStartFailure(plan: MeteringPlan, message: String) {
        val transaction = zoneRawTransaction
        if (transaction?.sessionState?.phase == ZoneRawSessionPhase.METERING) {
            transaction.failure = ZoneRawFailure(
                message = message,
                meteringMode = plan.meteringMode,
                target = plan.target,
                meteringRoiFraction = plan.meteringRoiFraction,
            )
            restoreZoneResidentSession(transaction)
        } else {
            meteringOperationActive = false
            postMeterError(message)
        }
    }

    /** Captures one full-resolution DNG and a compact RAW sample map for history point metering. */
    fun captureRawRecord(
        outputFile: File,
        screenAspect: Float,
        zoom: Float,
        completion: (Result<RawRecordArtifact>) -> Unit,
    ) {
        val handler = cameraHandler
        if (handler == null) {
            completion(Result.failure(IllegalStateException(localized("相机尚未就绪", "Camera is not ready"))))
            return
        }
        handler.post {
            if (meteringOperationActive || rawMeter.isMeasuring || compatibleMeter.isMeasuring ||
                vignettingCapture.isActive || rawRecordCapture.isActive
            ) {
                mainHandler.post {
                    completion(Result.failure(IllegalStateException(localized("请等待当前操作完成", "Wait for the current operation"))))
                }
                return@post
            }
            val context = rawMeteringContext()
            if (!cameraInfo.rawAvailable || context == null) {
                mainHandler.post {
                    completion(Result.failure(UnsupportedOperationException(localized("当前摄像头不支持 RAW 记录", "This camera does not support RAW recording"))))
                }
                return@post
            }
            meteringOperationActive = true
            val accepted = rawRecordCapture.start(
                context = context,
                outputFile = outputFile,
                screenAspect = screenAspect,
                zoom = zoom,
                request = { rawMeter.buildCaptureRequest(context) },
                beforeCapture = ::pausePreviewForRawCapture,
                completion = completion,
            )
            if (!accepted) {
                meteringOperationActive = false
                mainHandler.post {
                    completion(
                        Result.failure(
                            IllegalStateException(localized("请等待当前操作完成", "Wait for the current operation")),
                        ),
                    )
                }
            }
        }
    }

    /** Captures one RAW frame and estimates the illuminant from a centered gray-card patch. */
    internal fun estimateColorTemperature(
        screenAspect: Float,
        zoom: Float,
        completion: (Result<ColorTemperatureReading>) -> Unit,
    ) {
        val handler = cameraHandler
        if (handler == null) {
            completion(Result.failure(IllegalStateException(localized("相机尚未就绪", "Camera is not ready"))))
            return
        }
        handler.post {
            if (meteringOperationActive || rawMeter.isMeasuring || compatibleMeter.isMeasuring ||
                vignettingCapture.isActive || rawRecordCapture.isActive ||
                colorTemperatureEstimator.isEstimating
            ) {
                mainHandler.post {
                    completion(Result.failure(IllegalStateException(localized("请等待当前操作完成", "Wait for the current operation"))))
                }
                return@post
            }
            val context = rawMeteringContext()
            if (!cameraInfo.rawAvailable || context == null) {
                mainHandler.post {
                    completion(Result.failure(UnsupportedOperationException(localized("当前摄像头不支持 RAW，无法估算色温", "RAW is unavailable on this camera"))))
                }
                return@post
            }
            if (!RawColorTemperatureAnalysis.supportsCalibration(context.characteristics)) {
                mainHandler.post {
                    completion(Result.failure(UnsupportedOperationException(localized("当前摄像头缺少 RAW 色彩校准矩阵", "RAW color calibration matrices are unavailable"))))
                }
                return@post
            }
            val sensorOrientation = context.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: cameraInfo.sensorOrientationDegrees
            val sensorAspect = CameraPreviewTransform.screenAspectInSensorCoordinates(
                screenAspect,
                sensorOrientation,
                lastDisplayRotation,
                cameraInfo.lensFacing,
            )
            meteringOperationActive = true
            try {
                pausePreviewForRawCapture()
                val accepted = colorTemperatureEstimator.start(
                    context = context,
                    request = rawMeter.buildCaptureRequest(context),
                    frameAspect = sensorAspect,
                    zoom = zoom.coerceAtLeast(1f),
                    completion = completion,
                )
                if (!accepted) {
                    meteringOperationActive = false
                    resumePreviewAfterRawCapture()
                    mainHandler.post {
                        completion(Result.failure(IllegalStateException(localized("请等待当前操作完成", "Wait for the current operation"))))
                    }
                }
            } catch (error: Exception) {
                meteringOperationActive = false
                resumePreviewAfterRawCapture()
                mainHandler.post { completion(Result.failure(error)) }
            }
        }
    }

    private fun rawMeteringContext(): RawMeteringContext? {
        val device = cameraDevice ?: return null
        val session = captureSession ?: return null
        val reader = rawReader ?: return null
        val handler = cameraHandler ?: return null
        val chars = characteristics ?: return null
        return RawMeteringContext(
            device = device,
            session = session,
            rawSurface = reader.surface,
            handler = handler,
            latestResult = latestResult,
            characteristics = chars,
            cameraInfo = calibrationStorageCameraId?.let { storageCameraId ->
                // Logical-camera sessions briefly report no active physical id after every
                // reopen. Keep all sources in one calibration run on the identity pinned when
                // the user started it, while retaining the current session's geometry/metadata.
                cameraInfo.copy(
                    cameraId = storageCameraId,
                    runtimeCameraId = storageCameraId,
                    activePhysicalCameraId = null,
                )
            } ?: cameraInfo,
            selectedPhysicalCameraId = selectedPhysicalCameraId,
        )
    }

    fun calibrateVignetting() {
        val handler = cameraHandler ?: run {
            callback.onVignettingCalibrationError(
                localized("相机尚未就绪", "Camera is not ready"),
            )
            return
        }
        handler.post {
            if (rawMeter.isMeasuring || compatibleMeter.isMeasuring ||
                vignettingCapture.isActive
            ) {
                postVignettingError(
                    localized("请等待当前操作完成", "Wait for the current operation"),
                )
                return@post
            }
            val rawContext = rawMeteringContext()
            if (!cameraInfo.rawAvailable || rawContext == null) {
                postVignettingError(
                    localized(
                        "当前镜头不支持暗角校准",
                        "This lens does not support vignetting calibration",
                    ),
                )
                return@post
            }
            val accepted = vignettingCapture.start(
                context = rawContext,
                cameraId = cameraInfo.calibrationCameraId,
                request = { rawMeter.buildCaptureRequest(rawContext) },
                beforeCapture = ::pausePreviewForRawCapture,
            )
            if (!accepted) {
                postVignettingError(localized("请等待当前操作完成", "Wait for the current operation"))
            }
        }
    }

    private fun captureDisplayedPreviewReference(
        target: ZoneMeteringTarget,
    ): PreviewLumaReference? {
        val texture = textureView ?: return null
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
    private fun captureDisplayedPreviewReferences(
        requests: List<ZoneMeteringRequest>,
    ): Map<Int, PreviewLumaReference?> {
        val texture = textureView ?: return emptyMap()
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

    @Synchronized
    fun currentUserCalibrationRecord(): CameraCalibrationRecord? =
        calibrationStore.record(calibrationCameraId())

    @Synchronized
    fun currentCalibrationCameraId(): String = calibrationCameraId()

    @Synchronized
    internal fun currentCalibrationIdentity(): CalibrationCaptureIdentity = CalibrationCaptureIdentity(
        routeId = CalibrationRouteIdentity.resolve(
            selectedCameraId = cameraInfo.cameraId,
            requestedCameraId = requestedCameraId,
        ),
        activePhysicalCameraId = cameraInfo.activePhysicalCameraId,
    )

    @Synchronized
    fun isRawMeteringAvailable(): Boolean = cameraInfo.rawAvailable && rawReader != null

    @Synchronized
    fun isYuvMeteringAvailable(): Boolean =
        trackingReader != null && compatibleMeter.yuvAvailable

    /** Hardware-level sources that calibration can open sequentially in isolated sessions. */
    fun calibrationCapabilities(): MeteringCalibrationCapabilities =
        MeteringCalibrationCapabilities(
            rawAvailable = rawHardwareAvailable,
            // Missing static declarations are not proof that a vendor never reports the values.
            // Both processed paths validate and retry live CaptureResults during calibration.
            yuvAvailable = trackingHardwareAvailable,
            ispPreviewAvailable = true,
        )

    @Synchronized
    fun preferredCompatibleMeteringSource(): MeteringSource =
        compatibleMeter.preferredSource(trackingReader != null)

    @Synchronized
    fun updateUserCalibration(
        referenceEv100: Double,
        measurements: Map<MeteringSource, Double>,
    ): CameraCalibrationRecord {
        val cameraId = calibrationCameraId()
        val updated = calibrationStore.updateUserCorrections(
            cameraId = cameraId,
            referenceEv100 = referenceEv100,
            measurements = measurements,
        )
        Log.e(
            TAG,
            "User calibration updated: camera=$cameraId reference=$referenceEv100 measurements=$measurements " +
                "rawCorrection=${updated.rawCorrectionEv} " +
                "yuvCorrection=${updated.yuvCorrectionEv} " +
                "ispCorrection=${updated.ispPreviewCorrectionEv}",
        )
        return updated
    }

    @Synchronized
    fun resetUserCalibration(cameraId: String = calibrationCameraId()) {
        calibrationStore.resetUserCorrection(cameraId)
        Log.e(TAG, "User calibration reset: camera=$cameraId")
    }

    @Synchronized
    fun restoreUserCalibration(
        updatedAtEpochMs: Long,
        cameraId: String = calibrationCameraId(),
    ): CameraCalibrationRecord? {
        val restored = calibrationStore.restore(cameraId, updatedAtEpochMs) ?: return null
        Log.i(
            TAG,
            "User calibration restored: camera=$cameraId updated=${restored.updatedAtEpochMs} " +
                "rawCorrection=${restored.rawCorrectionEv} " +
                "compatibleCorrection=${restored.compatibleCorrectionEv}",
        )
        return restored
    }

    @Synchronized
    fun restoreVignettingCalibration(createdAtEpochMs: Long): VignettingCalibrationInfo? {
        val cameraId = calibrationCameraId()
        val restored = vignettingCalibrationStore.restore(cameraId, createdAtEpochMs) ?: return null
        Log.i(
            TAG,
            "Vignetting calibration restored: camera=$cameraId created=${restored.createdAtEpochMs}",
        )
        return restored
    }

    @Synchronized
    fun resetVignettingCalibration() {
        val cameraId = calibrationCameraId()
        vignettingCalibrationStore.reset(cameraId)
        Log.i(TAG, "Vignetting calibration reset: camera=$cameraId")
    }

    private fun measureCompatiblePreview(
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
        meteringRoiFraction: Float? = null,
        forceProcessedPreview: Boolean = false,
    ) {
        if (compatibleMeter.isMeasuring || rawMeter.isMeasuring ||
            vignettingCapture.isActive
        ) {
            meteringOperationActive = false
            postMeterError(localized("请等待当前操作完成", "Wait for the current operation"))
            return
        }
        val useProcessedPreview = forceProcessedPreview
        val needsYuvRequest = !useProcessedPreview && target == null &&
            trackingReader != null && compatibleMeter.yuvAvailable
        val accepted = compatibleMeter.measure(
            meteringMode = meteringMode,
            target = target,
            context = compatibleMeteringContext(),
            forceProcessedPreview = useProcessedPreview,
            meteringRoiFraction = meteringRoiFraction,
        )
        if (!accepted) {
            meteringOperationActive = false
            postMeterError(localized("请等待当前操作完成", "Wait for the current operation"))
        } else if (needsYuvRequest) {
            previewRequests.beginCompatibleYuvRequest()
        }
    }

    private fun compatibleMeteringContext() = CompatibleMeteringContext(
        textureView = textureView,
        cameraHandler = cameraHandler,
        trackingReaderAvailable = trackingReader != null,
        cameraReady = { cameraDevice != null && captureSession != null },
        takeResultForTimestamp = previewResultStore::takeExact,
        characteristics = { characteristics },
        cameraId = ::calibrationCameraId,
    )

    private fun measureProcessedPreview(
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
        meteringRoiFraction: Float?,
    ) {
        measureCompatiblePreview(
            meteringMode,
            target,
            meteringRoiFraction,
            forceProcessedPreview = true,
        )
    }

    private fun afterCompatibleMeasurement(
        requiresPreviewOnlySession: Boolean,
        calibrationRunActive: Boolean,
    ) {
        cameraHandler?.post {
            finishCompatibleYuvRequest()
            if (calibrationRunActive) {
                // MainActivity may already have queued the next isolated source or the normal
                // session restore. Do not race it with the ordinary YUV fallback downgrade.
                downgradeAfterCompatibleMeasurement = false
                return@post
            }
            when {
                requiresPreviewOnlySession &&
                    sessionProfile == CameraSessionProfile.COMPATIBLE -> {
                    if (started && cameraDevice != null) {
                        if (advanceSystemCombination(
                                localized(
                                    "YUV 组合不可用，正在测试下一个组合",
                                    "The YUV combination is unavailable. Testing the next one",
                                ),
                            )
                        ) {
                            return@post
                        }
                        if (combinationSelectionMode ==
                            MeteringCombinationSelectionMode.MANUAL
                        ) {
                            activeCombinationPlan = CameraCombinationPolicy.compatibilityIsp
                            switchResidentSession(CameraSessionProfile.PREVIEW_ONLY)
                            return@post
                        }
                        if (trackingFramesEnabled && recoveryState.profile?.usesRaw == true &&
                            !downgradeAfterCompatibleMeasurement
                        ) {
                            // Losing Zone YUV must not also disable a separately working RAW path.
                            zoneYuvSessionUnavailable = true
                            switchResidentSession(CameraSessionProfile.PREVIEW_ONLY)
                        } else {
                            scheduleRecovery(
                                CameraSessionProfile.PREVIEW_ONLY,
                                localized(
                                    "已切换到更稳定的预览方式",
                                    "Using a more stable preview method",
                                ),
                                SESSION_RECOVERY_DELAY_MS,
                            )
                        }
                    }
                }
                downgradeAfterCompatibleMeasurement -> {
                    downgradeAfterCompatibleMeasurement = false
                    if (!started || cameraDevice == null) return@post
                    if (advanceSystemCombination(
                            localized(
                                "RAW 组合连续失败，正在测试下一个组合",
                                "The RAW combination failed repeatedly. Testing the next one",
                            ),
                        )
                    ) {
                        return@post
                    }
                    val compatible = if (trackingHardwareAvailable && compatibleMeter.yuvAvailable) {
                        CameraSessionProfile.COMPATIBLE
                    } else {
                        CameraSessionProfile.PREVIEW_ONLY
                    }
                    if (combinationSelectionMode == MeteringCombinationSelectionMode.MANUAL) {
                        activeCombinationPlan = if (compatible == CameraSessionProfile.COMPATIBLE) {
                            CameraCombinationPolicy.stableYuv
                        } else {
                            CameraCombinationPolicy.compatibilityIsp
                        }
                    }
                    if (sessionProfile == compatible) {
                        recoveryState.forceProfile(compatible)
                        val readyInfo = cameraInfo.copy(
                            rawAvailable = false,
                            status = readyCameraStatus(),
                        )
                        cameraInfo = readyInfo
                        postInfo(readyInfo)
                        return@post
                    }
                    scheduleRecovery(
                        compatible,
                        localized(
                            "RAW 流不可用，已改用预览流",
                            "RAW is unavailable; using the preview stream",
                        ),
                        SESSION_RECOVERY_DELAY_MS,
                    )
                }
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (started) openCamera(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        previewSurfaceCoordinator.onSurfaceSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        cameraHandler?.post { closeCamera() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        previewSurfaceCoordinator.onFrameAvailable()
        previewHealth.onTextureUpdated(
            textureView = textureView,
            surface = surface,
            samplingAllowed = !combinationWorkflowProbe.isActive &&
                combinationSelectionMode != MeteringCombinationSelectionMode.MANUAL,
        )
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(surfaceTexture: SurfaceTexture?) {
        if (!started || opening || cameraDevice != null || surfaceTexture == null) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postInfo(
                cameraInfo.copy(
                    status = localized("等待相机权限", "Waiting for camera permission"),
                ),
            )
            return
        }
        val handler = cameraHandler ?: return
        val generation = ++cameraGeneration
        cameraFailureStage = CameraFailureStage.OPENING
        try {
            val configuration = openConfigurationResolver.resolve(
                requestedCameraId = requestedCameraId,
                routeCandidateIndex = recoveryState.routeCandidateIndex,
                meteringPipelineMode = meteringPipelineMode,
            )
            if (configuration == null) {
                postInfo(
                    CameraUiInfo(
                        status = localized("没有可用的摄像头", "No camera is available"),
                    ),
                )
                return
            }
            val descriptor = configuration.descriptor
            val effectivePhysicalId = configuration.route.physicalCameraId
            val chars = configuration.streamCharacteristics
            // Keep the catalog selection stable even when a vendor camera must temporarily use
            // its logical route. The logical/physical fields below describe the hardware route;
            // cameraId remains the user-facing lens identity used by preferences and the picker.
            val activeCameraId = descriptor.cameraId
            requestedCameraId = activeCameraId
            selectedPhysicalCameraId = effectivePhysicalId
            logicalCharacteristics = configuration.logicalCharacteristics
            characteristics = chars
            val activeContext = runtimeMetadata.resetPhysicalCamera(
                logicalCameraId = configuration.route.cameraIdToOpen,
                requestedPhysicalCameraId = effectivePhysicalId,
            )
            absoluteExposureMetadataAvailable = configuration.absoluteExposureMetadataAvailable
            rawHardwareAvailable = configuration.rawHardwareAvailable
            trackingHardwareAvailable = configuration.trackingHardwareAvailable
            val chosenPreview = configuration.previewSize
            // Invalidate a queued matrix from the previous route before this SurfaceTexture is
            // rebound with a potentially different vendor stream size.
            previewSurfaceCoordinator.invalidateForStreamChange()
            previewSize = chosenPreview
            val trackingSize = configuration.trackingSize
            val normalProfile = calibrationSessionProfile ?: recoveryState.resolveProfile(
                mode = meteringPipelineMode,
                rawSupported = rawHardwareAvailable,
                trackingSupported = trackingHardwareAvailable,
            )
            var profile = calibrationSessionProfile ?: ZoneSessionPolicy.residentProfile(
                normalProfile = normalProfile,
                zoneActive = trackingFramesEnabled,
                manualSafePreview = manualSafePreviewActive,
                trackingSupported = trackingHardwareAvailable,
                zoneYuvUnavailable = zoneYuvSessionUnavailable,
            )
            val availableRawSize = configuration.rawSize
            rawOutputSize = availableRawSize
            trackingOutputSize = trackingSize
            val matrixCandidates = configuration.matrixCandidates
            val systemCandidates = configuration.systemCandidates
            currentCombinationCandidates = matrixCandidates
            systemCombinationCandidates = systemCandidates
            if (calibrationSessionProfile == null && !manualSafePreviewActive) {
                val storedManualPlanId = if (combinationSelectionMode ==
                    MeteringCombinationSelectionMode.MANUAL
                ) {
                    combinationSelectionStore.selectedPlanId(activeCameraId)?.takeIf { planId ->
                        matrixCandidates.any { it.plan.id == planId }
                    }
                } else {
                    null
                }
                val manualPlanRequested = manualProbePlanId != null || storedManualPlanId != null
                if (combinationSelectionMode == MeteringCombinationSelectionMode.MANUAL &&
                    !manualPlanRequested
                ) {
                    // A manual result is scoped to this route and OS build. Keep the visible
                    // setting aligned with the automatic workflow when that result expires.
                    combinationSelectionMode = MeteringCombinationSelectionMode.SYSTEM
                    mainHandler.post(callback::onCombinationSelectionFallbackToSystem)
                }
                val cachedSystemPlanId = if (!manualPlanRequested) {
                    combinationSelectionStore.selectedSystemPlanId(
                        activeCameraId,
                        meteringPipelineMode,
                    )?.takeIf { it !in rejectedSystemCombinationIds }
                } else {
                    null
                }
                val selectedPlanId = manualProbePlanId ?: if (manualPlanRequested) {
                    storedManualPlanId
                } else {
                    cachedSystemPlanId
                }
                val candidatePool = if (manualPlanRequested) {
                    matrixCandidates
                } else {
                    systemCandidates.filterNot { it.plan.id in rejectedSystemCombinationIds }
                }
                val selectedPlan = selectedPlanId?.let { planId ->
                    candidatePool.firstOrNull { it.plan.id == planId }?.plan
                } ?: candidatePool.firstOrNull {
                    it.plan.normalResidentProfile == profile
                }?.plan
                activeCombinationPlan = selectedPlan
                pendingSystemWorkflowProbePlanId = if (!manualPlanRequested &&
                    selectedPlan != null && selectedPlan.id != cachedSystemPlanId
                ) {
                    selectedPlan.id
                } else {
                    null
                }
                if (selectedPlan != null) {
                    profile = if (trackingFramesEnabled) selectedPlan.zoneResidentProfile
                    else selectedPlan.normalResidentProfile
                }
            }
            activeSessionProfile = profile
            Log.i(
                TAG,
                "Stream matrix candidates mode=$meteringPipelineMode selected=" +
                    "${activeCombinationPlan?.id}: " +
                    systemCandidates.joinToString { candidate ->
                        "${candidate.plan.id}:${candidate.matrixSupport}"
                    },
            )
            val rawSize = availableRawSize.takeIf { profile.usesRaw }
            val configuredTrackingSize = trackingSize.takeIf { profile.usesTracking }
            val chosenRange = frameRateController.requestCeiling?.let { ceiling ->
                CameraStreamSelector.chooseFpsRange(
                    characteristics = chars,
                    previewSize = chosenPreview,
                    trackingSize = configuredTrackingSize,
                    requestedCeiling = ceiling,
                    lowLight = frameRateController.isLowLight,
                )
            }
            previewFpsRange = chosenRange

            cameraInfo = configuration.cameraInfo(
                activePhysicalCameraId = activeContext.activePhysicalCameraId,
                profile = profile,
                previewFpsRange = chosenRange,
                status = localized("正在打开摄像头", "Opening camera"),
            )
            postInfo(cameraInfo)

            sessionCoordinator.configureOutputs(
                surfaceTexture = surfaceTexture,
                previewSize = chosenPreview,
                rawSize = rawSize,
                trackingSize = configuredTrackingSize,
                handler = handler,
            )
            Log.i(
                TAG,
                "Opening selection=${descriptor.cameraId}, logical=${descriptor.logicalCameraId}, " +
                    "openId=${configuration.route.cameraIdToOpen}, route=${configuration.route.kind}, " +
                    "physical=$effectivePhysicalId, profile=$profile, " +
                    "focal=${configuration.focalLengthMm}, raw=${cameraInfo.rawAvailable}, " +
                    "hardwareLevel=${configuration.hardwareLevel}",
            )
            sessionCoordinator.open(
                logicalCameraId = configuration.route.cameraIdToOpen,
                physicalCameraId = effectivePhysicalId,
                generation = generation,
                handler = handler,
            )
        } catch (error: Exception) {
            handlePreparationFailure(error, generation)
        }
    }

    private fun handlePreparationFailure(error: Exception, generation: Int) {
        if (generation != cameraGeneration) return
        Log.e(TAG, "Unable to prepare camera profile=$sessionProfile", error)
        val failure = (error as? CameraAccessException)?.let { access ->
            when (access.reason) {
                CameraAccessException.CAMERA_IN_USE -> CameraFailureKind.IN_USE
                CameraAccessException.MAX_CAMERAS_IN_USE -> CameraFailureKind.RESOURCE_LIMIT
                CameraAccessException.CAMERA_DISABLED -> CameraFailureKind.DISABLED
                CameraAccessException.CAMERA_DISCONNECTED -> CameraFailureKind.DISCONNECTED
                CameraAccessException.CAMERA_ERROR -> CameraFailureKind.SERVICE
                else -> CameraFailureKind.UNKNOWN
            }
        } ?: CameraFailureKind.UNKNOWN
        handleCameraFailure(failure, CameraFailureStage.OPENING, generation)
    }

    private fun handleSessionFailure(generation: Int) {
        if (generation != cameraGeneration) return
        if (abortIsolatedCalibrationSessionIfActive()) return
        if (advanceSystemCombination(
                localized(
                    "当前组合无法启动，正在测试下一个组合",
                    "This combination could not start. Testing the next combination",
                ),
            )
        ) {
            return
        }
        val next = recoveryState.nextProfile(
            mode = meteringPipelineMode,
            rawSupported = rawHardwareAvailable,
            trackingSupported = trackingHardwareAvailable,
        )
        if (next != null) {
            scheduleRecovery(
                next,
                localized(
                    "当前方式无法启动，正在尝试更稳定的方式",
                    "This method could not start. Trying a more stable one",
                ),
                SESSION_RECOVERY_DELAY_MS,
            )
        } else if (!tryNextCameraRoute()) {
            finishCameraFailure(
                localized(
                    "相机无法正常启动，请尝试稳定模式或兼容模式，或重启手机",
                    "The camera could not start. Try Stable or Compatibility mode, or restart the phone",
                ),
            )
        }
    }

    private fun handleCameraFailure(
        failure: CameraFailureKind,
        stage: CameraFailureStage,
        generation: Int,
    ) {
        if (generation != cameraGeneration) return
        if (abortIsolatedCalibrationSessionIfActive()) return
        val decision = recoveryState.decideFailure(
            failure = failure,
            stage = stage,
            mode = meteringPipelineMode,
            rawSupported = rawHardwareAvailable,
            trackingSupported = trackingHardwareAvailable,
        )
        when (decision.action) {
            CameraRecoveryAction.RETRY -> scheduleRecovery(
                decision.profile ?: sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY,
                recoveryMessage(failure),
                decision.delayMs,
            )

            CameraRecoveryAction.DOWNGRADE -> if (!advanceSystemCombination(
                    recoveryMessage(failure),
                    decision.delayMs,
                )
            ) {
                scheduleRecovery(
                    decision.profile ?: sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY,
                    recoveryMessage(failure),
                    decision.delayMs,
                )
            }

            CameraRecoveryAction.STOP -> {
                val canChangeCameraRoute = failure == CameraFailureKind.DEVICE ||
                    failure == CameraFailureKind.SERVICE ||
                    failure == CameraFailureKind.DISCONNECTED ||
                    failure == CameraFailureKind.UNKNOWN
                if (!canChangeCameraRoute || !tryNextCameraRoute()) {
                    finishCameraFailure(finalFailureMessage(failure))
                }
            }
        }
    }

    private fun advanceSystemCombination(
        message: String,
        delayMs: Long = SESSION_RECOVERY_DELAY_MS,
    ): Boolean {
        if (combinationSelectionMode != MeteringCombinationSelectionMode.SYSTEM) return false
        val active = activeCombinationPlan ?: return false
        rejectedSystemCombinationIds += active.id
        // Do not revive a workflow on the next launch after either the HAL or the live preview
        // has disproved a previously cached result.
        combinationSelectionStore.clearSystem(cameraInfo.cameraId, meteringPipelineMode)
        val next = systemCombinationCandidates.firstOrNull {
            it.plan.id !in rejectedSystemCombinationIds
        }?.plan ?: return false
        activeCombinationPlan = next
        val resident = if (trackingFramesEnabled) next.zoneResidentProfile
        else next.normalResidentProfile
        scheduleRecovery(resident, message, delayMs)
        Log.i(
            TAG,
            "Combination search rejected=${active.id}; next=${next.id}; resident=$resident",
        )
        return true
    }

    /** A failed calibration-only session advances the UI to the next source instead of recovery. */
    private fun abortIsolatedCalibrationSessionIfActive(): Boolean {
        if (calibrationSessionProfile == null && pendingCalibrationMeteringPlan == null) return false
        pendingCalibrationMeteringPlan = null
        calibrationSessionProfile = null
        closeCamera()
        postMeterError(
            localized(
                "当前校准流无法安全启动，已跳过该来源",
                "This calibration stream could not start safely; the source was skipped",
            ),
        )
        return true
    }

    private fun tryNextCameraRoute(): Boolean {
        val descriptor = cameraCatalog.discover().firstOrNull { it.cameraId == requestedCameraId }
            ?: return false
        val candidates = CameraRouteResolver.candidates(descriptor)
        if (!recoveryState.advanceCameraRoute(candidates.size)) return false
        val next = candidates[recoveryState.routeCandidateIndex]
        val message = if (next.isLogicalFallback) {
            localized(
                "所选镜头暂时不可用，正在切换主摄",
                "The selected lens is unavailable. Switching to the main camera",
            )
        } else {
            localized(
                "正在尝试此镜头的兼容连接方式",
                "Trying a compatible connection for this lens",
            )
        }
        scheduleRecovery(
            CameraSessionProfile.PREVIEW_ONLY,
            message,
            SESSION_RECOVERY_DELAY_MS,
        )
        Log.i(
            TAG,
            "Advancing camera route selection=${descriptor.cameraId} " +
                "index=${recoveryState.routeCandidateIndex} kind=${next.kind} " +
                "openId=${next.cameraIdToOpen} physical=${next.physicalCameraId}",
        )
        return true
    }

    private fun scheduleRecovery(
        profile: CameraSessionProfile,
        message: String,
        delayMs: Long,
    ) {
        val handler = cameraHandler ?: return
        if (!started) return
        if (!recoveryState.beginRecovery(profile)) {
            finishCameraFailure(
                localized(
                    "相机多次启动失败，请稍后重试或重启手机",
                    "The camera failed repeatedly. Try again later or restart the phone",
                ),
            )
            return
        }
        notifyInterruptedOperations()
        closeCamera()
        postInfo(
            cameraInfo.copy(
                rawAvailable = profile.usesRaw && rawHardwareAvailable,
                status = message,
            ),
        )
        val recoveryGeneration = cameraGeneration
        handler.postDelayed(
            {
                if (!started || recoveryGeneration != cameraGeneration ||
                    cameraDevice != null || opening
                ) return@postDelayed
                val texture = textureView
                if (texture?.isAvailable == true) openCamera(texture.surfaceTexture)
            },
            delayMs,
        )
    }

    private fun finishCameraFailure(message: String) {
        notifyInterruptedOperations()
        previewHealth.clearConfirmation()
        closeCamera()
        postInfo(cameraInfo.copy(rawAvailable = false, status = message))
    }

    private fun notifyInterruptedOperations() {
        if (meteringOperationActive || rawMeter.isMeasuring || compatibleMeter.isMeasuring) {
            meteringOperationActive = false
            postMeterError(
                localized(
                    "本次测光已中止，请重试",
                    "This measurement was interrupted. Try again",
                ),
            )
        }
        if (vignettingCapture.isActive) {
            postVignettingError(
                localized(
                    "本次校准已中止，请重试",
                    "This calibration was interrupted. Try again",
                ),
            )
        }
    }

    private fun recoveryMessage(failure: CameraFailureKind): String = when (failure) {
        CameraFailureKind.IN_USE -> localized(
            "相机正被其他应用使用，正在重试",
            "Another app is using the camera. Retrying",
        )
        CameraFailureKind.RESOURCE_LIMIT -> localized(
            "相机暂时不可用，正在重试",
            "The camera is temporarily unavailable. Retrying",
        )
        CameraFailureKind.DISABLED -> localized(
            "相机已被系统停用",
            "The camera has been disabled by the system",
        )
        CameraFailureKind.DEVICE -> localized(
            "相机运行异常，正在尝试更稳定的方式",
            "The camera stopped unexpectedly. Trying a more stable method",
        )
        CameraFailureKind.SERVICE -> localized(
            "相机暂时无响应，正在重试",
            "The camera is not responding. Retrying",
        )
        CameraFailureKind.DISCONNECTED -> localized(
            "相机连接中断，正在重新连接",
            "The camera was disconnected. Reconnecting",
        )
        CameraFailureKind.UNKNOWN -> localized(
            "相机暂时无法使用，正在重试",
            "The camera is temporarily unavailable. Retrying",
        )
    }

    private fun finalFailureMessage(failure: CameraFailureKind): String = when (failure) {
        CameraFailureKind.IN_USE -> localized(
            "相机正被其他应用使用，请关闭其他相机应用后重试",
            "Another app is using the camera. Close it and try again",
        )
        CameraFailureKind.RESOURCE_LIMIT -> localized(
            "相机资源不足，请关闭其他相机应用后重试",
            "Camera resources are busy. Close other camera apps and try again",
        )
        CameraFailureKind.DISABLED -> localized(
            "相机已被系统停用，请检查隐私或管理设置",
            "The camera is disabled. Check privacy or device management settings",
        )
        CameraFailureKind.DEVICE -> localized(
            "相机无法正常启动，请尝试稳定模式或兼容模式，或重启手机",
            "The camera could not start. Try Stable or Compatibility mode, or restart the phone",
        )
        CameraFailureKind.SERVICE -> localized(
            "相机服务无法恢复，请重启手机后重试",
            "The camera could not recover. Restart the phone and try again",
        )
        CameraFailureKind.DISCONNECTED -> localized(
            "相机连接已中断，请稍后重试",
            "The camera was disconnected. Try again later",
        )
        CameraFailureKind.UNKNOWN -> localized(
            "相机暂时无法使用，请稍后重试",
            "The camera is unavailable. Try again later",
        )
    }

    private fun resetRecoveryState() {
        if (pendingSystemWorkflowProbePlanId != null) combinationWorkflowProbe.abandon()
        recoveryState.reset()
        activeCombinationPlan = null
        rejectedSystemCombinationIds.clear()
        pendingSystemWorkflowProbePlanId = null
        manualSafePreviewActive = false
        zoneYuvSessionUnavailable = false
        downgradeAfterCompatibleMeasurement = false
        frameRateController.resetForCamera()
        previewHealth.resetRecoveryState()
    }

    private fun startPreview(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
        generation: Int,
    ): Boolean {
        try {
            previewHealth.armConfirmation(generation)
            previewRequests.submit(device, session, preview)
            previewSurfaceCoordinator.onPreviewStarted()
            previewStreamGeneration += 1L
            val readyInfo = cameraInfo.copy(
                previewStreamGeneration = previewStreamGeneration,
                status = readyCameraStatus(),
            )
            cameraInfo = readyInfo
            postInfo(readyInfo)
            if (!readyInfo.rawAvailable && !transientZoneRawAvailable() &&
                !manualSafePreviewActive &&
                meteringPipelineMode == MeteringPipelineMode.AUTO &&
                calibrationSessionProfile == null
            ) {
                mainHandler.post {
                    // A preview-ready callback can already be queued when the activity pauses or
                    // another lens starts opening. Never surface that stale RAW warning.
                    if (started && generation == cameraGeneration) {
                        callback.onRawUnavailable()
                    }
                }
            }
            updatePreviewTransform(
                lastViewWidth,
                lastViewHeight,
                lastDisplayRotation,
                lastDisplayZoom,
            )
            resumePendingCalibrationMetering(generation)
            cameraHandler?.postDelayed(
                {
                    if (generation == cameraGeneration && captureSession === session) {
                        recoveryState.markPreviewStable()
                        previewHealth.markLongRunningPreviewStable()
                        if (combinationSelectionMode == MeteringCombinationSelectionMode.SYSTEM &&
                            calibrationSessionProfile == null && !manualSafePreviewActive &&
                            pendingSystemWorkflowProbePlanId == null &&
                            !combinationWorkflowProbe.isActive
                        ) {
                            activeCombinationPlan?.let { plan ->
                                combinationSelectionStore.saveSystem(
                                    cameraRouteId = cameraInfo.cameraId,
                                    mode = meteringPipelineMode,
                                    planId = plan.id,
                                )
                            }
                        }
                    }
                },
                STABLE_PREVIEW_RESET_DELAY_MS,
            )
            return true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start preview for profile=$sessionProfile", error)
            if (generation == cameraGeneration) handlePreviewRequestFailure(generation)
            return false
        }
    }

    /** FPS is a Camera2 session parameter; providing it up front avoids 60-fps reconfigure lag. */
    private fun createSessionParameters(
        device: CameraDevice,
        hasPreview: Boolean,
    ): CaptureRequest? {
        if (!hasPreview) return null
        val range = previewFpsRange ?: return null
        val sessionKeys = (logicalCharacteristics ?: characteristics)?.availableSessionKeys
        if (sessionKeys?.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) != true) return null
        return device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }.build()
    }

    private fun resumePendingCalibrationMetering(generation: Int) {
        val plan = pendingCalibrationMeteringPlan ?: return
        if (generation != cameraGeneration || !started) return
        val expectedProfile = plan.requestedSource?.let(CalibrationSessionProfilePolicy::profileFor)
        if (expectedProfile == null || sessionProfile != expectedProfile) return
        pendingCalibrationMeteringPlan = null
        Log.i(
            TAG,
            "Starting isolated calibration measurement source=${plan.requestedSource} " +
                "profile=$expectedProfile generation=$generation",
        )
        startMeteringPlan(plan)
    }

    private fun applyRequestedExposurePreview() {
        if (previewBaseline.isActive || exposurePreviewCalibration.isActive) return
        exposurePreviewState.apply(characteristics, cameraInfo, latestResult)
    }

    /** Restores neutral AE and waits for synchronized result frames before sampling the scene. */
    private fun prepareMeteringPreviewBaseline(continuation: () -> Unit) {
        if (!exposurePreviewState.neutralizeForMetering()) {
            continuation()
            return
        }
        mainHandler.post { callback.onMeteringBaselineRestoring() }
        previewBaseline.start(cameraGeneration, continuation)
        updatePreviewRepeatingRequest()
    }

    private fun updatePreviewRepeatingRequest() = previewRequests.update()

    /** Freezes the last displayed frame and drains repeating preview/YUV before RAW captures. */
    private fun pausePreviewForRawCapture() = previewRequests.pauseForRawCapture()

    private fun resumePreviewAfterRawCapture() = previewRequests.resumeAfterRawCapture()

    private fun finishCompatibleYuvRequest() = previewRequests.finishCompatibleYuvRequest()

    private fun handlePreviewRequestFailure(generation: Int) {
        if (generation != cameraGeneration) return
        if (previewFpsRange != null && frameRateController.useNextCompatibilityCeiling()) {
            scheduleRecovery(
                sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY,
                localized(
                    "正在使用更稳定的预览设置",
                    "Trying a more stable preview setting",
                ),
                SESSION_RECOVERY_DELAY_MS,
            )
        } else {
            handleSessionFailure(generation)
        }
    }

    private fun readyCameraStatus(): String {
        val size = "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height}"
        if (frameRateController.isLowLight) {
            return if (previewFpsRange?.let {
                    it.lower < it.upper || it.upper < PreviewFrameRateMode.LOW.requestedCeiling
                } == true
            ) {
                localized(
                    "$size · 环境光较暗，已自动降低取景帧率",
                    "$size · Low light; viewfinder frame rate reduced",
                )
            } else {
                localized(
                    "$size · 环境光过暗",
                    "$size · Ambient light is too low",
                )
            }
        }
        if (manualSafePreviewActive) {
            return localized("$size · 手动安全预览", "$size · Manual safe preview")
        }
        return when (meteringPipelineMode) {
            MeteringPipelineMode.AUTO -> if (cameraInfo.rawAvailable || transientZoneRawAvailable()) {
                localized("$size · 高精度测光", "$size · High-accuracy metering")
            } else {
                localized("$size · 预览流测光", "$size · Preview-stream metering")
            }
            MeteringPipelineMode.ISOLATED ->
                localized("$size · 稳定模式", "$size · Stable mode")
            MeteringPipelineMode.FAST ->
                localized("$size · 兼容模式", "$size · Compatibility mode")
        }
    }

    private fun choosePreviewFpsRange(profile: CameraSessionProfile): Range<Int>? {
        val ceiling = frameRateController.requestCeiling ?: return null
        val chars = characteristics ?: return null
        val size = previewSize ?: return null
        return CameraStreamSelector.chooseFpsRange(
            characteristics = chars,
            previewSize = size,
            trackingSize = trackingOutputSize.takeIf { profile.usesTracking },
            requestedCeiling = ceiling,
            lowLight = frameRateController.isLowLight,
        )
    }

    private fun transientZoneRawAvailable(): Boolean =
        rawHardwareAvailable && !manualSafePreviewActive &&
            (activeCombinationPlan?.let { plan ->
                val meteringProfile = if (trackingFramesEnabled) plan.zoneMeteringProfile
                else plan.normalMeteringProfile
                meteringProfile == CameraSessionProfile.RAW_ISOLATED
            } ?: (trackingFramesEnabled && recoveryState.profile?.usesRaw == true))

    /** Uses capture metadata only; no bitmap/YUV sampling is needed for low-light adaptation. */
    private fun observePreviewLighting(result: CaptureResult, timestampNs: Long?) {
        if (!exposurePreviewState.isNeutral || previewBaseline.isActive ||
            exposurePreviewCalibration.isActive
        ) {
            return
        }
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?: cameraInfo.aperture.takeIf { it > 0f }
        val changed = frameRateController.observeExposure(
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY),
            aperture = aperture,
            timestampNs = timestampNs,
            aeRequestsFlash = result.get(CaptureResult.CONTROL_AE_STATE) ==
                CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
        )
        if (!changed) return
        val profile = activeSessionProfile ?: return
        if (!profile.usesPreview) return
        val previousRange = previewFpsRange
        previewFpsRange = choosePreviewFpsRange(profile)
        val nextInfo = cameraInfo.copy(
            previewFps = previewFpsRange?.upper ?: cameraInfo.previewFps,
            previewFpsLower = previewFpsRange?.lower ?: cameraInfo.previewFpsLower,
            previewFpsUpper = previewFpsRange?.upper ?: cameraInfo.previewFpsUpper,
            status = readyCameraStatus(),
        )
        cameraInfo = nextInfo
        Log.i(
            TAG,
            "Preview lighting changed lowLight=${frameRateController.isLowLight} " +
                "fps=$previousRange->$previewFpsRange",
        )
        if (previewFpsRange != previousRange) updatePreviewRepeatingRequest()
        postInfo(nextInfo)
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            updateActivePhysicalCamera(result)
            val effectiveResult = effectiveCaptureResult(result)
            latestResult = effectiveResult
            observeAbsoluteExposureMetadata(effectiveResult)
            val timestamp = effectiveResult.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp != null) previewResultStore.put(timestamp, effectiveResult)
            runtimeMetadata.observeActualPreviewFps(timestamp, cameraInfo)
                ?.let(::postRuntimeInfo)
            runtimeMetadata.observePreviewSensorViewport(
                result = effectiveResult,
                currentInfo = cameraInfo,
                previewSize = previewSize,
                outputAspectOverride = previewOutputAspectOverride,
            )?.let(::postRuntimeInfo)
            updateDynamicLensInfo(effectiveResult)
            distanceCapture.onCaptureResult(effectiveResult, timestamp)
            previewBaseline.onCaptureResult(request, effectiveResult)
            exposurePreviewCalibration.onCaptureResult(request, effectiveResult)
            observePreviewLighting(effectiveResult, timestamp)
            // A few physical-camera HALs omit SENSOR_TIMESTAMP from the physical result even
            // though the logical TotalCaptureResult carries the timestamp for the same frame.
            compatibleMeter.onCaptureResult(
                effectiveResult,
                result.get(CaptureResult.SENSOR_TIMESTAMP),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun effectiveCaptureResult(result: TotalCaptureResult): CaptureResult {
        val physicalId = selectedPhysicalCameraId ?: return result
        return result.physicalCameraResults[physicalId] ?: result
    }

    /**
     * Promotes an unconfirmed vendor route after any real preview result proves it usable.
     * The flag is deliberately monotonic for one open route: one later incomplete result must
     * never revoke a capability already demonstrated by an earlier valid frame.
     */
    private fun observeAbsoluteExposureMetadata(result: CaptureResult) {
        if (absoluteExposureMetadataAvailable) return
        val chars = characteristics ?: return
        if (!ExposureMetadataPolicy.hasUsableFrameMetadata(
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY),
                resultAperture = result.get(CaptureResult.LENS_APERTURE),
                staticApertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES),
            )
        ) return
        absoluteExposureMetadataAvailable = true
        Log.i(TAG, "Absolute exposure metadata confirmed by a live CaptureResult")
        postInfo(cameraInfo.copy(absoluteExposureMetadataAvailable = true))
    }

    private fun updateActivePhysicalCamera(result: TotalCaptureResult) {
        val update = runtimeMetadata.observeActivePhysicalCamera(
            result = result,
            logicalCharacteristics = logicalCharacteristics,
            currentCharacteristics = characteristics,
            currentInfo = cameraInfo,
        ) ?: return
        characteristics = update.characteristics
        postInfo(update.cameraInfo)
        distanceCapture.invalidate("Active physical camera changed")
    }

    private fun updateDynamicLensInfo(result: CaptureResult) {
        runtimeMetadata.observeDynamicLensInfo(result, cameraInfo)?.let(::postRuntimeInfo)
    }

    private fun onTrackingImageAvailable(reader: ImageReader) {
        if (compatibleMeter.onImageAvailable(reader)) return
        zoneCameraFrames.onImageAvailable(
            reader,
            cameraInfo.sensorOrientationDegrees,
            lastDisplayRotation,
        )
    }

    private fun onRawImageAvailable(reader: ImageReader) {
        if (combinationWorkflowProbe.onRawImageAvailable(reader)) return
        if (rawRecordCapture.onImageAvailable(reader)) return
        if (vignettingCapture.onImageAvailable(reader)) return
        if (colorTemperatureEstimator.onImageAvailable(reader)) return
        rawMeter.onImageAvailable(reader)
    }

    private fun postVignettingError(message: String) {
        mainHandler.post { callback.onVignettingCalibrationError(message) }
    }

    private fun closeCamera(preserveExposurePreview: Boolean = false) {
        cameraGeneration += 1
        previewSurfaceCoordinator.reset()
        cameraFailureStage = CameraFailureStage.OPENING
        previewBaseline.cancel()
        exposurePreviewCalibration.cancelForCameraClose()
        if (!preserveExposurePreview) {
            pendingCalibrationMeteringPlan = null
            calibrationSessionProfile = null
            calibrationStorageCameraId = null
            exposurePreviewState.reset()
        }
        colorTemperatureEstimator.cancel(cameraHandler)?.let { completion ->
            mainHandler.post {
                completion(Result.failure(IllegalStateException(localized("色温估算已中止", "Color-temperature estimation was interrupted"))))
            }
        }
        rawRecordCapture.cancel(cameraHandler)?.let { completion ->
            mainHandler.post {
                completion(Result.failure(IllegalStateException(localized("RAW 记录已中止", "RAW recording was interrupted"))))
            }
        }
        rawMeter.cancel(cameraHandler)
        compatibleMeter.cancel(cameraHandler, resetYuvAvailability = true)
        zoneRawTransactions.reset()
        pendingResidentSessionProfile = null
        meteringOperationActive = false
        downgradeAfterCompatibleMeasurement = false
        previewRequests.resetCaptureState()
        vignettingCapture.cancel(cameraHandler)
        sessionCoordinator.close()
        zoneCameraFrames.reset()
        previewSize = null
        rawOutputSize = null
        trackingOutputSize = null
        previewFpsRange = null
        distanceCapture.invalidate("Camera closed")
        activeSessionProfile = null
        characteristics = null
        logicalCharacteristics = null
        selectedPhysicalCameraId = null
        runtimeMetadata.reset()
        latestResult = null
        previewResultStore.clear()
        previewHealth.refreshMonitoring()
    }

    private fun postInfo(info: CameraUiInfo) {
        cameraInfo = info
        val generation = cameraGeneration
        Log.e(
            TAG,
                "Camera status: ${info.status}; id=${info.cameraId}; " +
                "logical=${info.logicalCameraId}; runtime=${info.runtimeCameraId}; " +
                "physical=${info.physicalCameraId}; " +
                "activePhysical=${info.activePhysicalCameraId}; " +
                "focal=${info.focalLengthMm}; rawHardware=${info.rawHardwareAvailable}; " +
                "rawSession=${info.rawAvailable}; " +
                "manual=${info.manualSensorAvailable}; " +
                "absoluteExposureMetadata=${info.absoluteExposureMetadataAvailable}; " +
                "preview=${info.previewSize}",
        )
        mainHandler.post {
            // A close/reopen can overtake a queued main-thread callback. Only the latest state
            // from the still-current camera generation may influence persisted selection or UI
            // geometry; recovery itself remains owned by the camera thread.
            if (generation == cameraGeneration && cameraInfo == info) {
                callback.onCameraInfo(info)
            }
        }
    }

    /** Publishes high-frequency display metadata without turning it into an error-level log. */
    private fun postRuntimeInfo(info: CameraUiInfo) {
        cameraInfo = info
        val generation = cameraGeneration
        mainHandler.post {
            if (generation == cameraGeneration && cameraInfo.cameraId == info.cameraId) {
                callback.onCameraInfo(info)
            }
        }
    }

    private fun postMeterError(message: String) {
        mainHandler.post { callback.onMeteringError(message) }
    }

    private fun calibrationCameraId(): String =
        calibrationStorageCameraId
            ?: cameraInfo.calibrationCameraId.ifBlank { requestedCameraId ?: "0" }

    private fun localized(chinese: String, english: String): String =
        callback.localized(chinese, english)

    companion object {
        private const val TAG = "lightstop"
        private const val MAX_METERING_REFERENCE_AGE_NS = 350_000_000L
        private const val PREVIEW_REFERENCE_LONG_EDGE = 384
        private const val SESSION_RECOVERY_DELAY_MS = 300L
        private const val STABLE_PREVIEW_RESET_DELAY_MS = 10_000L
        private const val MAX_TOTAL_RECOVERY_ATTEMPTS = 6
        private const val RAW_FAILURES_BEFORE_DOWNGRADE = 2
    }
}
