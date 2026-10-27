package com.lightmeter.rawmeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Bitmap
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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Public camera facade used by MainActivity.
 *
 * This class selects capabilities and coordinates lifecycle, calibration and fallback. Camera2
 * resources, RAW metering, compatible metering, result pairing and recovery history are owned by
 * dedicated internal components below this facade.
 *
 * 维护边界 / Maintenance boundary:
 * - 这里只保留 Camera2 生命周期、能力/会话选择和跨组件调度；不要继续堆放功能实现。
 * - 新增功能的状态、超时和取消由对应 Coordinator 持有，数学/图像分析放在独立算法类。
 * - 负片流程归 CameraFilmPreviewCoordinator，参考图采样归 CameraPreviewReferenceSampler，
 *   状态文案归 CameraStatusFormatter，临时 RAW 事务归 CameraZoneRawCoordinator；不要搬回本类。
 * - 公共入口可以薄委托，但不能通过把本 Controller 传给组件来绕过状态所有权。
 * - 拆分必须保持线程、generation、Image/Surface 所有权及 RAW/YUV 隔离恢复契约。
 * See docs/CAMERA_CONTROLLER_BOUNDARIES_ZH.md before adding another responsibility.
 */
class CameraController(
    private val context: Context,
    private val callback: CameraControllerCallback,
    private val meteringViewport: () -> EvaluativeMeteringViewport? = { null },
) : TextureView.SurfaceTextureListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusFormatter = CameraStatusFormatter(::localized)
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
                publishCalibrationContext()
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
                if (zoneRaw.onRawSessionConfigured(activeSessionProfile)) return
                if (!zoneRaw.isActive && calibrationSessionProfile == null &&
                    pendingResidentSessionProfile == null
                ) {
                    val desiredProfile = desiredResidentSessionProfile()
                    if (desiredProfile != activeSessionProfile) {
                        switchResidentSession(desiredProfile)
                        return
                    }
                }
                val restoringZoneRaw = zoneRaw.isRestoring
                val preview = previewSurface
                if (preview == null) {
                    onSessionConfigurationFailed(
                        generation,
                        IllegalStateException("Preview session configured without a preview Surface"),
                    )
                    return
                }
                pendingResidentSessionProfile = null
                if (!startPreview(device, session, preview, generation)) {
                    failManualAcceptance(IllegalStateException("Manual workflow preview failed to start"))
                    return
                }
                if (restoringZoneRaw) zoneRaw.onResidentSessionConfigured()
                if (pendingManualAcceptance != null) completeManualAcceptance()
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
                if (pendingManualAcceptance != null) {
                    failManualAcceptance(
                        error ?: IllegalStateException("Manual workflow resident session failed"),
                    )
                    return
                }
                if (zoneRaw.onSessionFailure(error) || handleResidentSessionFailure(error)) {
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
        context = context,
        cameraHandler = { cameraHandler },
        cameraDevice = { cameraDevice },
        captureSession = { captureSession },
        previewSurface = { sessionCoordinator.previewSurface },
        characteristics = { characteristics },
        requestCharacteristics = { logicalCharacteristics ?: characteristics },
        cameraInfo = { cameraInfo },
        cameraGeneration = { cameraGeneration },
        previewCaptureCallback = { previewCaptureCallback },
        motionGeometryAllowed = {
            activeSessionProfile?.usesTracking == true &&
                (!cameraInfo.isLogicalMultiCamera || selectedRouteKind == CameraRouteKind.FIXED_PHYSICAL)
        },
        onSamplingChanged = {
            if (activeSessionProfile?.usesPreview == true && !meteringOperationActive &&
                !combinationWorkflowProbe.isActive) updatePreviewRepeatingRequest()
        },
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
                callback.onMeterReading(freezeCalibrationSample(reading))
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
                updateActivePhysicalCamera(totalResult)
                zoneRaw.observePhysicalCamera(cameraInfo.activePhysicalCameraId)
                latestResult = result
                updateDynamicLensInfo(result)
            }

            override fun onRawMeteringStarted(frameCount: Int) {
                mainHandler.post { callback.onMeteringStarted(MeteringSource.RAW, frameCount) }
            }

            override fun onRawMeteringReading(reading: MeterReading) {
                if (zoneRaw.onReading(reading)) return
                resumePreviewAfterRawCapture()
                meteringOperationActive = false
                mainHandler.post { callback.onMeterReading(freezeCalibrationSample(reading)) }
            }

            override fun onRawZoneBatchReading(results: List<ZoneMeteringResult>) {
                if (zoneRaw.onBatchReading(results)) return
                resumePreviewAfterRawCapture()
                meteringOperationActive = false
                mainHandler.post { callback.onZoneMeteringBatchResult(results) }
            }

            override fun onRawMeteringError(
                message: String,
                meteringMode: MeteringMode,
                target: ZoneMeteringTarget?,
                meteringRoiFraction: Float?,
            ) {
                if (zoneRaw.onMeteringError(
                        ZoneRawFailure(message, meteringMode, target, meteringRoiFraction),
                    )
                ) return
                resumePreviewAfterRawCapture()
                if (isConfirmedRawWorkflow()) {
                    meteringOperationActive = false
                    postMeterError(message)
                    return
                }
                val canUsePreview = textureView?.isAvailable == true &&
                    cameraDevice != null && captureSession != null
                if (canUsePreview) {
                    // Fallback applies to this reading only. The next request still tries RAW.
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
        rawCorrectionEv = { calibrationStore.totalCorrection(it, MeteringSource.RAW) },
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
    /** A successfully probed RAW workflow is protected from runtime precision fallback. */
    @Volatile
    private var rawWorkflowConfirmation = CameraWorkflowConfirmationPolicy.unverified()
    private var manualProbePlanId: String? = null
    private var manualProbeId: Long? = null
    private var manualProbeTimeout: Runnable? = null
    private val manualVerification = ManualCombinationVerificationCoordinator()
    private val manualVerificationEpoch = AtomicLong()
    private val manualVerificationOwnerEpoch: Long get() = manualVerificationEpoch.get()
    private var notifiedManualRevalidationKey: String? = null
    private var manualSelectionRequiresChoice = false
    @Volatile private var pendingManualRevalidation: ManualCombinationRevalidationRequest? = null
    @Volatile private var manualAcceptanceRecoveryRequired = false
    private var pendingManualAcceptance: ManualAcceptanceTransaction? = null
    private var manualAcceptanceTimeout: Runnable? = null
    private val combinationWorkflowProbe = CameraCombinationWorkflowProbeRunner(
        mainHandler = mainHandler,
        cameraHandler = { cameraHandler },
        currentGeneration = { cameraGeneration },
        currentProfile = { activeSessionProfile },
        rawSurface = { rawReader?.surface },
        rawCharacteristics = { characteristics },
        expectedRawSize = { rawOutputSize },
        requestedPhysicalCameraId = { selectedPhysicalCameraId },
        routeKind = { selectedRouteKind },
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
    private var selectedRouteKind: CameraRouteKind = CameraRouteKind.LOGICAL_AUTO
    private var activeOpenConfiguration: CameraOpenConfiguration? = null
    @Volatile
    private var geometryContext = CameraGeometryContext.EMPTY
    private val metadataResolver = PhysicalCaptureResultResolver()
    private var meteringPipelineMode = MeteringPipelineMode.AUTO
    @Volatile
    private var rawHardwareAvailable = false
    @Volatile
    private var trackingHardwareAvailable = false
    @Volatile
    private var absoluteExposureMetadataAvailable = false
    private val recoveryState = CameraRecoveryStateMachine(
        maxRecoveryAttempts = MAX_TOTAL_RECOVERY_ATTEMPTS,
    )
    private val sessionProfile: CameraSessionProfile?
        get() = activeSessionProfile ?: calibrationSessionProfile ?: recoveryState.profile
    private var activeSessionProfile: CameraSessionProfile? = null
    private var cameraGeneration = 0
    private var calibrationSessionProfile: CameraSessionProfile? = null
    private var pendingCalibrationMeteringPlan: MeteringPlan? = null
    private val zoneRaw = CameraZoneRawCoordinator(
        mainHandler = mainHandler,
        host = object : CameraZoneRawHost {
            override val rawSessionReady get() = started && cameraDevice != null &&
                captureSession != null && rawOutputSize != null
            override val started get() = this@CameraController.started
            override val cameraInfo get() = this@CameraController.cameraInfo
            override val characteristics get() = this@CameraController.characteristics
            override val rawHardwareAvailable get() = this@CameraController.rawHardwareAvailable
            override val confirmedRawWorkflow get() = isConfirmedRawWorkflow()
            override fun resetCaptureState() = previewRequests.resetCaptureState()
            override fun clearPendingResidentSession() { pendingResidentSessionProfile = null }
            override fun desiredResidentProfile() = desiredResidentSessionProfile()
            override fun restoreCharacteristics(characteristics: CameraCharacteristics?) {
                this@CameraController.characteristics = characteristics
            }
            override fun reconfigureSession(profile: CameraSessionProfile) =
                this@CameraController.reconfigureSession(profile)
            override fun startMeasurement(plan: MeteringPlan) = this@CameraController.startMeasurement(plan)
            override fun postInfo(info: CameraUiInfo) = this@CameraController.postInfo(info)
            override fun finishOperation(rawSucceeded: Boolean) {
                meteringOperationActive = false
            }
            override fun fallbackToCompatible(failure: ZoneRawFailure) {
                measureCompatiblePreview(failure.meteringMode, failure.target, failure.meteringRoiFraction)
            }
            override fun postMeterError(message: String) = this@CameraController.postMeterError(message)
            override fun recoverSafePreview() = scheduleRecovery(
                CameraSessionProfile.PREVIEW_ONLY,
                localized("正在恢复安全预览", "Restoring safe preview"),
                delayMs = 0L,
            )
        },
        listener = object : CameraZoneRawListener {
            override fun onRestoreStateChanged(restoring: Boolean) = callback.onMeteringRestoreStateChanged(restoring)
            override fun onReading(reading: MeterReading) = callback.onMeterReading(reading)
            override fun onBatchReading(results: List<ZoneMeteringResult>) = callback.onZoneMeteringBatchResult(results)
        },
        latestReferenceFrame = { zoneCameraFrames.latestFrame(MAX_METERING_REFERENCE_AGE_NS) },
        localized = ::localized,
    )
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
    private val previewReferenceSampler = CameraPreviewReferenceSampler { textureView }
    private var trackingFramesEnabled = false
    private val exposurePreviewState = ExposurePreviewStateCoordinator(
        onRequestChanged = ::updatePreviewRepeatingRequest,
        onUnavailable = { mainHandler.post(callback::onExposurePreviewUnavailable) },
    )
    private val exposurePreviewUpdateQueued = AtomicBoolean(false)
    private val previewBaseline = MeteringPreviewBaselineCoordinator { cameraHandler }
    private val previewRequests: CameraPreviewRequestCoordinator = CameraPreviewRequestCoordinator(
        cameraHandler = { cameraHandler },
        cameraDevice = { cameraDevice },
        captureSession = { captureSession },
        previewSurface = { sessionCoordinator.previewSurface },
        trackingSurface = { trackingReader?.surface },
        trackingFramesEnabled = {
            trackingFramesEnabled || (activeSessionProfile?.usesTracking == true && distanceCapture.wantsFrames)
        },
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
        filmPreviewActive = { filmPreview.isEnabled },
        filmPreviewLocked = { filmPreview.isLocked },
        filmPreviewPreparingCapture = { filmPreview.isPreparingCapture },
        requestCharacteristics = { logicalCharacteristics ?: characteristics },
        exposureTransitionActive = { exposurePreviewState.isTransitioning },
    )
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
        processedPreview = { filmPreview.hasRenderer },
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
            zoneRaw.isActive || pendingResidentSessionProfile != null
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
        confirmedRawWorkflow = { isConfirmedRawWorkflow() },
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

    private val filmPreview = CameraFilmPreviewCoordinator(
        mainHandler = mainHandler,
        localized = ::localized,
        host = object : CameraFilmPreviewHost {
            override val cameraHandler get() = this@CameraController.cameraHandler
            override val cameraGeneration get() = this@CameraController.cameraGeneration
            override val cameraInfo get() = this@CameraController.cameraInfo
            override val requestCharacteristics get() = logicalCharacteristics ?: characteristics
            override val sessionAvailable get() = captureSession != null
            override val textureView get() = this@CameraController.textureView
            override val started get() = this@CameraController.started
            override val stopInProgress get() = this@CameraController.stopInProgress
            override val displayRotation get() = lastDisplayRotation
            override val displayZoom get() = lastDisplayZoom
            override fun closeCamera() = this@CameraController.closeCamera()
            override fun resetRecoveryState() = this@CameraController.resetRecoveryState()
            override fun openCamera(output: SurfaceTexture?) = this@CameraController.openCamera(output)
            override fun updatePreviewRepeatingRequest() = this@CameraController.updatePreviewRepeatingRequest()
            override fun finishCameraFailure(message: String) = this@CameraController.finishCameraFailure(message)
        },
    )
    internal var onFilmNegativePreviewError: ((String) -> Unit)?
        get() = filmPreview.onError
        set(value) { filmPreview.onError = value }
    internal var onFilmNegativePreviewReady: ((String) -> Unit)?
        get() = filmPreview.onReady
        set(value) { filmPreview.onReady = value }
    internal var onFilmNegativeBaseInvalidated: (() -> Unit)?
        get() = filmPreview.onBaseInvalidated
        set(value) { filmPreview.onBaseInvalidated = value }
    internal var onFilmNegativeProcessingChanged: ((Boolean) -> Unit)?
        get() = filmPreview.onProcessingChanged
        set(value) { filmPreview.onProcessingChanged = value }

    internal fun setFilmNegativePreviewEnabled(enabled: Boolean) = filmPreview.setEnabled(enabled)
    internal fun updateFilmNegativeSettings(settings: FilmNegativeSettings) = filmPreview.updateSettings(settings)
    internal fun setFilmNegativeCameraLocked(locked: Boolean, completion: (Boolean) -> Unit) =
        filmPreview.setCameraLocked(locked, completion)
    internal fun sampleFilmNegativeBase(automatic: Boolean = false, completion: (FloatArray?) -> Unit) =
        filmPreview.sampleBase(automatic, completion)
    internal fun captureFilmNegativeFrame(maxEdge: Int = 1280, completion: (FilmNegativeFrozenFrame?) -> Unit) =
        filmPreview.captureFrame(maxEdge, completion)
    internal fun selectFilmNegativeFrame(completion: (FilmNegativeSelection?) -> Unit) =
        filmPreview.selectFrame(completion)
    internal fun acceptFilmNegativeFrame(frame: FilmNegativeFrozenFrame, settings: FilmNegativeSettings,
        completion: (Boolean) -> Unit) = filmPreview.acceptFrame(frame, settings, completion)
    internal fun retryFilmNegativePreview() = filmPreview.retry()

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
                zoneRaw.isActive || meteringOperationActive || combinationWorkflowProbe.isActive
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
                invalidateManualVerification()
                meteringPipelineMode = mode
                resetRecoveryState()
            }
            return
        }
        handler.post {
            if (meteringPipelineMode == mode) return@post
            invalidateManualVerification()
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
            invalidateManualVerification()
            manualProbePlanId = null
        }
    }

    internal fun manualCombinationCandidates(): List<CameraCombinationCandidate> =
        currentCombinationCandidates

    /** Replays every distinct session stage of the workflow before asking the user to judge it. */
    internal fun probeManualCombination(
        planId: String,
        revalidationRequest: ManualCombinationRevalidationRequest? = null,
        completion: (Result<ManualCombinationProbeEvidence>) -> Unit,
    ): Boolean {
        val candidate = currentCombinationCandidates.firstOrNull { it.plan.id == planId }
            ?: return false
        val handler = cameraHandler ?: return false
        val selectionAtRequest = requestedCameraId
        val epochAtRequest = manualVerificationOwnerEpoch
        if (!started || meteringOperationActive || calibrationStorageCameraId != null ||
            vignettingCapture.isActive || combinationWorkflowProbe.isActive
        ) {
            return false
        }
        return handler.post {
            if (!started || meteringOperationActive || calibrationStorageCameraId != null ||
                vignettingCapture.isActive || combinationWorkflowProbe.isActive ||
                pendingManualAcceptance != null || selectionAtRequest != requestedCameraId ||
                epochAtRequest != manualVerificationOwnerEpoch ||
                (revalidationRequest != null && !isManualRevalidationCurrent(revalidationRequest))
            ) {
                mainHandler.post {
                    completion(Result.failure(IllegalStateException("Camera is busy")))
                }
                return@post
            }
            val plan = candidate.plan
            val selectionCameraId = requestedCameraId
            if (selectionCameraId == null) {
                mainHandler.post {
                    completion(Result.failure(IllegalStateException("No selected camera for manual probe")))
                }
                return@post
            }
            // Close the old camera BEFORE owning the new probe. Later closes are external to it.
            closeCamera(preserveExposurePreview = true)
            pendingManualRevalidation = null
            manualProbePlanId = plan.id
            activeCombinationPlan = plan
            val probeId = manualVerification.begin(
                planId = plan.id,
                selectionCameraId = selectionCameraId,
                ownerEpoch = manualVerificationOwnerEpoch,
            )
            manualProbeId = probeId
            val probeTimeout = Runnable {
                if (manualProbeId == probeId) {
                    combinationWorkflowProbe.cancel(IllegalStateException("Manual workflow probe timed out"))
                }
            }
            manualProbeTimeout = probeTimeout
            Log.i(TAG, "Manual probe started probe=$probeId plan=${plan.id} owner=$manualVerificationOwnerEpoch")
            combinationWorkflowProbe.begin(
                plan = plan,
                completeOnCameraThread = true,
                validateOwner = {
                    manualVerification.matchesBoundRoute(
                        probeId, exactConfirmationRouteId(), cameraGeneration, manualVerificationOwnerEpoch,
                    )
                },
                completion = { result ->
                    val evidence = if (result.isSuccess && activeCombinationPlan?.id == plan.id) {
                        manualVerification.complete(
                            probeId = probeId,
                            routeIdentity = exactConfirmationRouteId(),
                            completedCameraGeneration = cameraGeneration,
                            ownerEpoch = manualVerificationOwnerEpoch,
                        )
                    } else null
                    if (evidence == null) manualVerification.failProbe(probeId)
                    if (manualProbeId == probeId) {
                        manualProbeId = null
                        manualProbeTimeout?.let { handler.removeCallbacks(it) }
                        manualProbeTimeout = null
                    }
                    Log.i(TAG, "Manual probe completed probe=$probeId success=${evidence != null}")
                    mainHandler.post {
                        completion(
                            evidence?.let { Result.success(it) } ?: Result.failure(
                                IllegalStateException(
                                    if (result.isSuccess) "Manual probe completed without matching route evidence"
                                    else result.exceptionOrNull()?.message ?: "Manual probe failed",
                                ),
                            ),
                        )
                    }
                },
            )
            if (!handler.postDelayed(probeTimeout, 20_000L)) {
                combinationWorkflowProbe.cancel(IllegalStateException("Camera handler rejected probe timeout"))
                return@post
            }
            manualSafePreviewActive = false
            val texture = textureView?.surfaceTexture
            if (texture == null) {
                combinationWorkflowProbe.cancel(
                    IllegalStateException("Preview Surface is unavailable"),
                )
            } else {
                openCamera(texture)
            }
        }
    }

    internal fun acceptManualCombination(
        planId: String,
        probeId: Long,
        completion: (ManualAcceptanceOutcome) -> Unit,
    ): Boolean {
        val handler = cameraHandler ?: return false
        if (!handler.post {
                val plan = currentCombinationCandidates
                    .firstOrNull { it.plan.id == planId }?.plan
                val selectedCameraId = requestedCameraId
                val routeIdentity = exactConfirmationRouteId()
                val evidence = if (started && pendingManualAcceptance == null && plan != null && selectedCameraId != null) {
                    manualVerification.beginApply(
                        probeId = probeId,
                        planId = plan.id,
                        selectionCameraId = selectedCameraId,
                        routeIdentity = routeIdentity,
                        ownerEpoch = manualVerificationOwnerEpoch,
                        currentCameraGeneration = cameraGeneration,
                    )
                } else {
                    null
                }
                if (!started || plan == null || evidence == null) {
                    mainHandler.post {
                        completion(ManualAcceptanceOutcome.RetryRequired(false, "Manual verification evidence expired"))
                    }
                    return@post
                }
                // The evidence is consumed on the camera thread.  Keep APPLYING until the
                // resident session is actually configured; no new capture can slip in between.
                combinationSelectionMode = MeteringCombinationSelectionMode.MANUAL
                manualProbePlanId = null
                activeCombinationPlan = plan
                val pending = ManualAcceptanceTransaction(evidence, plan) { outcome ->
                    mainHandler.post { completion(outcome) }
                }
                pendingManualAcceptance = pending
                try {
                    if (plan.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW) {
                        combinationSelectionStore.saveVerified(evidence.selectionCameraId, plan.id, evidence.routeIdentity)
                    } else {
                        combinationSelectionStore.save(evidence.selectionCameraId, plan.id, evidence.routeIdentity)
                    }
                    pending.markSaved()
                } catch (error: Exception) {
                    failManualAcceptance(error)
                    return@post
                }
                // Human approval survives a failed resident restore; never fall back to YUV.
                rawWorkflowConfirmation = confirmationForManualAcceptance(pending)
                val timeout = Runnable {
                    if (pendingManualAcceptance === pending) {
                        failManualAcceptance(IllegalStateException("Timed out restoring accepted workflow"))
                    }
                }
                manualAcceptanceTimeout = timeout
                if (!handler.postDelayed(timeout, 8_000L)) {
                    failManualAcceptance(IllegalStateException("Camera handler rejected restore timeout"))
                    return@post
                }
                val desired = desiredResidentSessionProfile()
                if (cameraDevice == null || captureSession == null || desired != activeSessionProfile) {
                    switchResidentSession(desired)
                } else {
                    completeManualAcceptance()
                }
            }
        ) {
            return false
        }
        return true
    }

    internal fun cancelManualCombinationSelection() {
        invalidateManualVerification()
        val handler = cameraHandler ?: return
        handler.post {
            combinationWorkflowProbe.cancel(IllegalStateException("Cancelled"))
            manualProbePlanId = null
            failManualAcceptance(IllegalStateException("Manual selection cancelled"))
            manualVerification.fail()
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
        if (!exposurePreviewState.updateSelection(selection)) return
        // Closing the camera publishes a distance-state callback on the main thread.
        // Preserve its selection, but do not enqueue work on the retiring camera thread.
        if (!started || stopInProgress) return
        val handler = cameraHandler ?: return
        if (!exposurePreviewUpdateQueued.compareAndSet(false, true)) return
        val accepted = handler.post {
            exposurePreviewUpdateQueued.set(false)
            if (started && cameraHandler === handler) applyRequestedExposurePreview()
        }
        if (!accepted) exposurePreviewUpdateQueued.set(false)
    }

    /**
     * Starts an auxiliary comparison against a fresh Camera2 AE 0-EV reference. This flow does not
     * read or modify RAW/YUV/ISP metering calibration.
     */
    fun beginExposurePreviewCalibration(onReady: (Boolean) -> Unit): Boolean {
        if (meteringOperationActive || calibrationSessionProfile != null ||
            vignettingCapture.isActive || rawRecordCapture.isActive || manualRawCaptureBlocked()
        ) {
            return false
        }
        val handler = cameraHandler ?: return false
        handler.post {
            if (!started || captureSession == null || cameraDevice == null ||
                meteringOperationActive || calibrationSessionProfile != null || manualRawCaptureBlocked()
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
        invalidateManualVerification()
        requestedCameraId = cameraId
        val handler = cameraHandler ?: return
        handler.post {
            if (!started || cameraInfo.cameraId == cameraId && cameraDevice != null) return@post
            failManualAcceptance(IllegalStateException("Camera selection changed"))
            resetRecoveryState()
            closeCamera()
            if (filmPreview.isEnabled) {
                filmPreview.releaseRenderer()
            }
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
        filmPreview.onCameraStart()
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
        invalidateManualVerification()
        restartAfterStop = false
        if (stopInProgress) return
        started = false
        val handler = cameraHandler
        val thread = cameraThread
        if (handler != null && thread != null) {
            stopInProgress = true
            handler.post {
                failManualAcceptance(IllegalStateException("Camera stopped"))
                closeCamera()
                filmPreview.releaseRenderer()
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
            filmPreview.releaseRenderer()
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
        previewSurfaceCoordinator.update(viewWidth, viewHeight, displayRotation,
            if (filmPreview.isEnabled) 1f else displayZoom)
        filmPreview.orient()
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
            displayZoom = if (filmPreview.isEnabled) 1f else displayZoom,
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
        if (filmPreview.isEnabled || filmPreview.isReleasingRenderer ||
            meteringOperationActive || combinationWorkflowProbe.isActive ||
            manualRawCaptureBlocked()
        ) return false
        meteringOperationActive = true
        val viewport = meteringViewport()
        val screenAspect = viewport?.screenFrameAspect ?: if (frameLandscape) {
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
                sensorWidthMm = cameraInfo.sensorWidthMm.toDouble() * (viewport?.previewSensorViewport?.width ?: 1f),
                sensorHeightMm = cameraInfo.sensorHeightMm.toDouble() * (viewport?.previewSensorViewport?.height ?: 1f),
                sensorFrameAspect = sensorFrameAspect.toDouble(),
            )
        } else {
            null
        }
        val displayedPreviewReference = target?.let(previewReferenceSampler::capture)
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
            viewport = viewport,
        )
        val handler = cameraHandler ?: run {
            meteringOperationActive = false
            callback.onMeteringError(localized("相机尚未就绪", "Camera is not ready"))
            return true
        }
        handler.post {
            if (manualRawCaptureBlocked()) {
                meteringOperationActive = false
                postMeterError(localized("手动 RAW 组合尚未完成验证", "Manual RAW workflow is not yet verified"))
                return@post
            }
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
        if (requests.isEmpty() || meteringOperationActive || combinationWorkflowProbe.isActive ||
            manualRawCaptureBlocked()
        ) {
            return false
        }
        meteringOperationActive = true
        val viewport = meteringViewport()
        val screenAspect = viewport?.screenFrameAspect ?: if (frameLandscape) {
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
                sensorWidthMm = cameraInfo.sensorWidthMm.toDouble() * (viewport?.previewSensorViewport?.width ?: 1f),
                sensorHeightMm = cameraInfo.sensorHeightMm.toDouble() * (viewport?.previewSensorViewport?.height ?: 1f),
                sensorFrameAspect = sensorFrameAspect.toDouble(),
            )
        } else {
            null
        }
        val displayedReferences = previewReferenceSampler.captureBatch(requests)
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
            viewport = viewport,
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
            if (manualRawCaptureBlocked()) {
                meteringOperationActive = false
                postMeterError(localized("手动 RAW 组合尚未完成验证", "Manual RAW workflow is not yet verified"))
                return@post
            }
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
        if (manualRawCaptureBlocked()) {
            meteringOperationActive = false
            postMeterError(localized("手动 RAW 组合尚未完成验证", "Manual RAW workflow is not yet verified"))
            return
        }
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
                zoneRaw.begin(plan)
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
                zoneRaw.begin(plan)
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

    private fun desiredResidentSessionProfile(): CameraSessionProfile {
        // The tool owns the preview producer. Resident restoration must obey the same
        // constraint as openCamera, including callbacks arriving after a lifecycle change.
        if (filmPreview.isEnabled) return CameraSessionProfile.PREVIEW_ONLY
        val calibrationProfile = calibrationSessionProfile
        if (calibrationProfile != null) return calibrationProfile
        if (manualSafePreviewActive) return CameraSessionProfile.PREVIEW_ONLY
        if (manualSelectionRequiresChoice) return CameraSessionProfile.PREVIEW_ONLY
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
        if (!started || cameraDevice == null) {
            failManualAcceptance(IllegalStateException("Camera unavailable for resident restore"))
            return
        }
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
            if (pendingManualAcceptance != null) {
                failManualAcceptance(error)
                return
            }
            if (!handleResidentSessionFailure(error)) {
                handleSessionFailure(cameraGeneration)
            }
        }
    }

    /** Completes an accepted manual workflow only after its resident Camera2 session is ready. */
    private fun completeManualAcceptance() {
        val pending = pendingManualAcceptance ?: return
        if (!started || activeCombinationPlan?.id != pending.plan.id ||
            activeSessionProfile != desiredResidentSessionProfile() ||
            !manualVerification.confirm(
                pending.evidence, requestedCameraId, exactConfirmationRouteId(),
                manualVerificationOwnerEpoch, cameraGeneration,
            )
        ) {
            failManualAcceptance(IllegalStateException("Manual acceptance owner or session changed"))
            return
        }
        rawWorkflowConfirmation = confirmationForManualAcceptance(pending)
        manualAcceptanceRecoveryRequired = false
        manualSelectionRequiresChoice = false
        notifiedManualRevalidationKey = null
        Log.i(TAG, "Manual acceptance restored probe=${pending.evidence.probeId} " +
            "plan=${pending.plan.id} route=${pending.evidence.routeIdentity} profile=$activeSessionProfile")
        postInfo(cameraInfo.copy(status = readyCameraStatus()))
        finishManualAcceptance(pending, ManualAcceptanceOutcome.Accepted)
    }

    private fun confirmationForManualAcceptance(pending: ManualAcceptanceTransaction) =
        if (pending.plan.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW) {
            CameraWorkflowConfirmationPolicy.confirm(
                pending.plan.id, pending.evidence.routeIdentity, WorkflowConfirmationOrigin.MANUAL_PROBE,
            )
        } else CameraWorkflowConfirmationPolicy.unverified()

    private fun finishManualAcceptance(pending: ManualAcceptanceTransaction, outcome: ManualAcceptanceOutcome) {
        if (pendingManualAcceptance !== pending) return
        pendingManualAcceptance = null
        manualAcceptanceTimeout?.let { cameraHandler?.removeCallbacks(it) }
        manualAcceptanceTimeout = null
        pending.finish(outcome)
    }

    /** A saved approval is never converted into a YUV fallback if the same workflow cannot restore. */
    private fun failManualAcceptance(error: Throwable) {
        val pending = pendingManualAcceptance ?: return
        manualVerification.fail()
        manualAcceptanceRecoveryRequired = true
        Log.w(TAG, "Manual acceptance unavailable probe=${pending.evidence.probeId} saved=${pending.approvalSaved}", error)
        finishManualAcceptance(pending, ManualAcceptanceOutcome.RetryRequired(pending.approvalSaved, error.message.orEmpty()))
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
        val routeAtProbe = exactConfirmationRouteId() ?: return false
        val selectionAtProbe = cameraInfo.cameraId
        val modeAtProbe = meteringPipelineMode
        combinationWorkflowProbe.begin(
            plan = plan,
            completion = { result ->
                cameraHandler?.post {
                    if (pendingSystemWorkflowProbePlanId != plan.id || generation != cameraGeneration ||
                        routeAtProbe != exactConfirmationRouteId() || selectionAtProbe != cameraInfo.cameraId ||
                        modeAtProbe != meteringPipelineMode || combinationSelectionMode != MeteringCombinationSelectionMode.SYSTEM
                    ) return@post
                    pendingSystemWorkflowProbePlanId = null
                    if (result.isSuccess) {
                        if (plan.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW) {
                            rawWorkflowConfirmation = CameraWorkflowConfirmationPolicy.confirm(
                                planId = plan.id,
                                routeId = routeAtProbe,
                                origin = WorkflowConfirmationOrigin.SYSTEM_PROBE,
                            )
                        }
                        combinationSelectionStore.saveSystem(
                            cameraRouteId = selectionAtProbe,
                            mode = modeAtProbe,
                            planId = plan.id,
                            routeIdentity = routeAtProbe,
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
            measureCompatiblePreview(plan.meteringMode, plan.target, plan.meteringRoiFraction,
                forceProcessedPreview = true, viewport = plan.viewport)
            return
        }
        if (plan.requestedSource == MeteringSource.YUV_PREVIEW ||
            plan.requestedSource == null && !cameraInfo.rawAvailable
        ) {
            measureCompatiblePreview(plan.meteringMode, plan.target, plan.meteringRoiFraction, viewport = plan.viewport)
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
            sensorViewport = plan.viewport?.previewSensorViewport ?: cameraInfo.previewSensorViewport,
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
                evaluativeSensorCrop = plan.viewport?.sensorCrop,
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
        if (zoneRaw.onMeteringError(
                ZoneRawFailure(message, plan.meteringMode, plan.target, plan.meteringRoiFraction),
            )
        ) return
        meteringOperationActive = false
        postMeterError(message)
    }

    /** Captures one full-resolution DNG and a compact RAW sample map for history point metering. */
    internal fun captureRawRecord(
        outputFile: File,
        screenAspect: Float,
        zoom: Float,
        sensorCrop: NormalizedSensorViewport? = null,
        screenToSensorTransform: ScreenToSensorCoordinateTransform? = null,
        completion: (Result<RawRecordArtifact>) -> Unit,
    ) {
        val handler = cameraHandler
        if (handler == null) {
            completion(Result.failure(IllegalStateException(localized("相机尚未就绪", "Camera is not ready"))))
            return
        }
        handler.post {
            if (manualRawCaptureBlocked()) {
                mainHandler.post {
                    completion(Result.failure(IllegalStateException(localized("手动 RAW 组合尚未完成验证", "Manual RAW workflow is not yet verified"))))
                }
                return@post
            }
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
                sensorCrop = sensorCrop,
                screenToSensorTransform = screenToSensorTransform,
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
            if (manualRawCaptureBlocked()) {
                mainHandler.post {
                    completion(Result.failure(IllegalStateException(localized("手动 RAW 组合尚未完成验证", "Manual RAW workflow is not yet verified"))))
                }
                return@post
            }
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

    /**
     * Publishes the capture context used to validate stored calibrations. It is derived from the
     * actually configured session outputs, never from an expected configuration.
     */
    private fun publishCalibrationContext() {
        val configuration = activeOpenConfiguration
        val profile = activeSessionProfile
        if (configuration == null || profile == null) {
            calibrationStore.setActiveContext(null)
            vignettingCalibrationStore.setActiveContext(null)
            geometryContext = CameraGeometryContext.EMPTY
            return
        }
        val context = configuration.calibrationCaptureContext(
            profile = profile,
            confirmedPhysicalCameraId = cameraInfo.activePhysicalCameraId,
        )
        calibrationStore.setActiveContext(context)
        vignettingCalibrationStore.setActiveContext(context)
        publishGeometryContext()
    }

    /**
     * Maintains the preview and RAW geometry domains and advances the epoch when either changes.
     * Consumers that convert coordinates record the epoch so stale results cannot be applied after
     * a geometry change.
     */
    private fun publishGeometryContext() {
        val profile = activeSessionProfile
        if (profile == null) {
            geometryContext = CameraGeometryContext.EMPTY
            return
        }
        val info = cameraInfo
        val previewGeometry = previewSize?.let { size ->
            PreviewStreamGeometry(
                routeId = info.logicalCameraId,
                generation = info.previewStreamGeneration,
                sensorOrientationDegrees = info.sensorOrientationDegrees,
                lensFacing = info.lensFacing,
                activeArray = info.activeArray,
                previewWidth = size.width,
                previewHeight = size.height,
                sensorViewport = info.previewSensorViewport,
            )
        }
        val rawGeometry = rawOutputSize?.takeIf { profile.usesRaw }?.let { size ->
            val chars = characteristics
            RawSensorGeometry(
                routeId = info.runtimeCameraId,
                generation = info.previewStreamGeneration,
                physicalCameraId = info.activePhysicalCameraId ?: selectedPhysicalCameraId,
                sensorOrientationDegrees = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION)
                    ?: info.sensorOrientationDegrees,
                lensFacing = info.lensFacing,
                activeArray = info.activeArray,
                rawWidth = size.width,
                rawHeight = size.height,
            )
        }
        val next = geometryContext.withDomains(previewGeometry, rawGeometry)
        if (next.epoch != geometryContext.epoch) {
            distanceCapture.invalidate("Preview/RAW geometry changed")
        }
        geometryContext = next
    }

    internal fun currentGeometryEpoch(): Long = geometryContext.epoch

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
            routeKind = selectedRouteKind,
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

    /**
     * Signature for one source at the instant a calibration reading arrives. It is derived from the
     * session that produced the reading, not from whichever session happens to be last.
     */
    @Synchronized
    internal fun currentCalibrationSignature(source: MeteringSource): CalibrationSignature? {
        val configuration = activeOpenConfiguration ?: return null
        val profile = activeSessionProfile ?: return null
        return configuration.calibrationCaptureContext(
            profile = profile,
            confirmedPhysicalCameraId = cameraInfo.activePhysicalCameraId,
        ).sampleSignature(source)
    }

    @Synchronized
    fun isRawMeteringAvailable(): Boolean = cameraInfo.rawAvailable && rawReader != null

    fun captureRecordPreview(completion: (Bitmap?) -> Unit) = filmPreview.captureRecordPreview(completion)

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
        signatures: Map<MeteringSource, CalibrationSignature> = emptyMap(),
        inputLuma: Map<MeteringSource, Double> = emptyMap(),
        samples: Map<MeteringSource, CalibrationMeasurementSample> = emptyMap(),
    ): CameraCalibrationRecord {
        val cameraId = calibrationCameraId()
        val updated = calibrationStore.updateUserCorrections(
            cameraId = cameraId,
            referenceEv100 = referenceEv100,
            measurements = measurements,
            signatures = signatures,
            inputLuma = inputLuma,
            samples = samples,
        )
        Log.i(
            TAG,
            "User calibration updated: camera=$cameraId reference=$referenceEv100 measurements=$measurements " +
                "rawCorrection=${updated.rawCorrectionEv} " +
                "yuvCorrection=${updated.yuvCorrectionEv} " +
                "ispCorrection=${updated.ispPreviewCorrectionEv}",
        )
        return updated
    }

    /** Freeze the actual session's route/output before a calibration stage can reopen. */
    private fun freezeCalibrationSample(reading: MeterReading): MeterReading {
        val sample = reading.calibrationSample ?: return reading
        return reading.copy(
            calibrationSample = sample.withCaptureContext(
                cameraId = calibrationCameraId(),
                signature = currentCalibrationSignature(reading.source),
                generation = cameraGeneration.toLong(),
            ),
        )
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
        viewport: EvaluativeMeteringViewport? = null,
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
            context = compatibleMeteringContext().let { if (viewport == null) it else it.copy(evaluativeViewport = viewport) },
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
        evaluativeViewport = meteringViewport(),
        previewGeometry = geometryContext.preview,
        captureFullPreview = filmPreview::captureMeteringFrame,
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
                return@post
            }
            if (isConfirmedRawWorkflow()) {
                // A compatible frame may be explicitly requested for calibration, but it must
                // never become a runtime replacement for the confirmed RAW workflow.
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
                        if (trackingFramesEnabled && recoveryState.profile?.usesRaw == true) {
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
        cameraHandler?.post {
            closeCamera()
            filmPreview.releaseRenderer()
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        previewSurfaceCoordinator.onFrameAvailable()
        previewHealth.onTextureUpdated(
            textureView = textureView,
            surface = surface,
            samplingAllowed = !filmPreview.isEnabled &&
                !filmPreview.isReleasingRenderer && !combinationWorkflowProbe.isActive &&
                combinationSelectionMode != MeteringCombinationSelectionMode.MANUAL,
        )
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(surfaceTexture: SurfaceTexture?) {
        if (!started || opening || cameraDevice != null || surfaceTexture == null ||
            filmPreview.isReleasingRenderer) return
        val cameraInputTexture = filmPreview.inputFor(surfaceTexture) ?: return
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
                combinationWorkflowProbe.cancel(IllegalStateException("No configuration for manual probe"))
                postInfo(
                    CameraUiInfo(
                        status = localized("没有可用的摄像头", "No camera is available"),
                    ),
                )
                return
            }
            val descriptor = configuration.descriptor
            activeOpenConfiguration = configuration
            manualAcceptanceRecoveryRequired = false
            manualProbeId?.let { probeId ->
                if (!manualVerification.bindRoute(
                        probeId, descriptor.cameraId, configuration.confirmationRouteIdentity(),
                        generation, manualVerificationOwnerEpoch,
                    )
                ) {
                    combinationWorkflowProbe.cancel(IllegalStateException("Manual probe route changed"))
                    closeCamera(preserveExposurePreview = true)
                    return
                }
                Log.i(TAG, "Manual probe route bound probe=$probeId route=${configuration.confirmationRouteIdentity()} generation=$generation")
            }
            val effectivePhysicalId = configuration.route.physicalCameraId
            val chars = configuration.streamCharacteristics
            // Keep the catalog selection stable even when a vendor camera must temporarily use
            // its logical route. The logical/physical fields below describe the hardware route;
            // cameraId remains the user-facing lens identity used by preferences and the picker.
            val activeCameraId = descriptor.cameraId
            requestedCameraId = activeCameraId
            selectedPhysicalCameraId = effectivePhysicalId
            selectedRouteKind = configuration.route.kind
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
            if (filmPreview.isEnabled) profile = CameraSessionProfile.PREVIEW_ONLY
            val availableRawSize = configuration.rawSize
            rawOutputSize = availableRawSize
            trackingOutputSize = trackingSize
            val matrixCandidates = configuration.matrixCandidates
            val systemCandidates = configuration.systemCandidates
            currentCombinationCandidates = matrixCandidates
            systemCombinationCandidates = systemCandidates
            if (manualProbePlanId != null && matrixCandidates.none { it.plan.id == manualProbePlanId }) {
                combinationWorkflowProbe.cancel(IllegalStateException("Requested manual candidate is no longer available"))
                closeCamera(preserveExposurePreview = true)
                return
            }
            if (calibrationSessionProfile == null && !manualSafePreviewActive && !filmPreview.isEnabled) {
                val manualRead = if (combinationSelectionMode ==
                    MeteringCombinationSelectionMode.MANUAL
                ) combinationSelectionStore.readManualSelection(activeCameraId)
                else ManualCombinationSelectionRead.Missing
                val manualIntent = ManualCombinationIntentPolicy.resolve(
                    manualRead, matrixCandidates.map { it.plan.id }.toSet(), manualProbePlanId,
                )
                val storedManualSelection = manualIntent.selection
                val storedManualPlanId = storedManualSelection?.planId?.takeIf { it == manualIntent.planId }
                val manualIntentUnresolved = manualIntent.requiresChoice
                manualSelectionRequiresChoice = manualIntentUnresolved
                val manualPlanRequested = manualProbePlanId != null || storedManualPlanId != null
                if (combinationSelectionMode == MeteringCombinationSelectionMode.MANUAL &&
                    !manualPlanRequested && !manualIntentUnresolved
                ) {
                    // A manual result is scoped to this route and OS build. Keep the visible
                    // setting aligned with the automatic workflow when that result expires.
                    combinationSelectionMode = MeteringCombinationSelectionMode.SYSTEM
                    mainHandler.post(callback::onCombinationSelectionFallbackToSystem)
                }
                val cachedSystemSelection = if (!manualPlanRequested && !manualIntentUnresolved) {
                    combinationSelectionStore.selectedSystemPlan(
                        activeCameraId,
                        meteringPipelineMode,
                    )?.takeIf { it.planId !in rejectedSystemCombinationIds }
                } else {
                    null
                }
                val cachedSystemPlanId = cachedSystemSelection?.planId
                // R01: an old build's automatic low-precision cache must not skip RAW
                // re-validation while AUTO still has a RAW candidate. Treat it as "needs retest"
                // and fall through to a real probe; the outcome is persisted with an origin.
                val cacheNeedsRetest = CombinationCacheMigrationPolicy.needsRawRevalidation(
                    selection = cachedSystemSelection,
                    mode = meteringPipelineMode,
                    cachedPlanIsRaw = systemCandidates.firstOrNull { it.plan.id == cachedSystemPlanId }
                        ?.plan?.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW,
                    hasRawCandidate = systemCandidates.any {
                        it.plan.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW
                    },
                ) || CombinationRouteMatchPolicy.needsSystemRevalidation(
                    cachedSystemSelection, confirmationRouteId(),
                )
                val effectiveCachedPlanId = if (cacheNeedsRetest) null else cachedSystemPlanId
                if (cacheNeedsRetest) {
                    Log.i(
                        TAG,
                        "Legacy automatic combination cache needs RAW re-validation " +
                            "route=$activeCameraId cached=$cachedSystemPlanId",
                    )
                }
                val selectedPlanId = manualProbePlanId ?: if (manualPlanRequested) {
                    storedManualPlanId
                } else {
                    effectiveCachedPlanId
                }
                val candidatePool = if (manualIntentUnresolved) {
                    emptyList()
                } else if (manualPlanRequested) {
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
                rawWorkflowConfirmation = when {
                    selectedPlan != null &&
                        selectedPlan.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW &&
                        selectedPlan.id == effectiveCachedPlanId &&
                        CombinationRouteMatchPolicy.matches(cachedSystemSelection, confirmationRouteId()) ->
                        CameraWorkflowConfirmationPolicy.confirm(
                            planId = selectedPlan.id,
                            routeId = confirmationRouteId(),
                            origin = WorkflowConfirmationOrigin.SYSTEM_PROBE,
                        )

                    selectedPlan != null && manualPlanRequested &&
                        selectedPlan.id == storedManualPlanId &&
                        selectedPlan.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW &&
                        storedManualSelection?.origin == CombinationSelectionOrigin.MANUAL_VERIFIED &&
                        CombinationRouteMatchPolicy.matches(storedManualSelection, confirmationRouteId()) ->
                        CameraWorkflowConfirmationPolicy.confirm(
                            planId = selectedPlan.id,
                            routeId = confirmationRouteId(),
                            origin = WorkflowConfirmationOrigin.MANUAL_PROBE,
                        )

                    else -> CameraWorkflowConfirmationPolicy.unverified()
                }
                if (manualProbePlanId == null && selectedPlan != null && storedManualPlanId != null &&
                    (manualIntent.environmentExpired ||
                    ManualCombinationRevalidationPolicy.needsRevalidation(
                        selection = storedManualSelection,
                        isRawPlan = selectedPlan.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW,
                        currentRouteId = exactConfirmationRouteId(),
                    ))
                ) {
                    val revalidationPlan = selectedPlan
                    rawWorkflowConfirmation = CameraWorkflowConfirmation(
                        planId = revalidationPlan.id,
                        routeId = exactConfirmationRouteId().orEmpty(),
                        state = WorkflowConfirmationState.NEEDS_REVALIDATION,
                    )
                    manualVerification.markNeedsRevalidation(
                        planId = revalidationPlan.id,
                        selectionCameraId = activeCameraId,
                        ownerEpoch = manualVerificationOwnerEpoch,
                    )
                    Log.w(
                        TAG,
                        "Legacy manual RAW selection needs re-verification " +
                            "route=$activeCameraId plan=${selectedPlan?.id}",
                    )
                    publishManualRevalidation(
                        ManualCombinationRevalidationRequest(
                            selectionCameraId = activeCameraId,
                            planId = revalidationPlan.id,
                            ownerEpoch = manualVerificationOwnerEpoch,
                            reason = "manual route or environment changed",
                            canStart = true,
                        ),
                    )
                }
                if (manualIntentUnresolved) {
                    profile = CameraSessionProfile.PREVIEW_ONLY
                    publishManualRevalidation(
                        ManualCombinationRevalidationRequest(
                            selectionCameraId = activeCameraId,
                            planId = storedManualSelection?.planId,
                            ownerEpoch = manualVerificationOwnerEpoch,
                            reason = when (manualRead) {
                                is ManualCombinationSelectionRead.UnsupportedRecord -> manualRead.reason
                                else -> "saved manual plan is unavailable"
                            },
                            canStart = false,
                        ),
                    )
                }
                pendingSystemWorkflowProbePlanId = if (!manualPlanRequested &&
                    selectedPlan != null && selectedPlan.id != effectiveCachedPlanId
                ) {
                    selectedPlan.id
                } else {
                    null
                }
                if (selectedPlan != null) {
                    // Internal probe stages do not change the user's Normal/Zone selection.
                    profile = combinationWorkflowProbe.active?.currentStage
                        ?: if (trackingFramesEnabled) selectedPlan.zoneResidentProfile
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
            val chosenRange = previewFrameRateCeiling()?.let { ceiling ->
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
            publishCalibrationContext()

            filmPreview.resize(chosenPreview.width, chosenPreview.height)
            sessionCoordinator.configureOutputs(
                surfaceTexture = cameraInputTexture,
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
        if (filmPreview.isEnabled) {
            if (!tryNextCameraRoute()) finishCameraFailure(localized(
                "负片预览无法启动，请重启预览或切换摄像头", "Negative preview could not start. Retry or select another camera"))
            return
        }
        if (abortIsolatedCalibrationSessionIfActive()) return
        if (isConfirmedRawWorkflow()) {
            scheduleRecovery(
                sessionProfile ?: activeCombinationPlan?.normalResidentProfile
                    ?: CameraSessionProfile.RAW_ONLY,
                localized("RAW 工作流暂时不可用，正在重试", "RAW workflow is temporarily unavailable; retrying"),
                SESSION_RECOVERY_DELAY_MS,
            )
            return
        }
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
        if (pendingManualAcceptance != null || manualProbeId != null) {
            val error = IllegalStateException("Camera failed during manual verification: $failure")
            failManualAcceptance(error)
            combinationWorkflowProbe.cancel(error)
            finishCameraFailure(statusFormatter.failure(failure))
            return
        }
        if (abortIsolatedCalibrationSessionIfActive()) return
        val decision = recoveryState.decideFailure(
            failure = failure,
            stage = stage,
            mode = meteringPipelineMode,
            rawSupported = rawHardwareAvailable,
            trackingSupported = trackingHardwareAvailable,
            confirmedRawWorkflow = isConfirmedRawWorkflow(),
        )
        if (isConfirmedRawWorkflow()) {
            if (decision.action == CameraRecoveryAction.STOP) {
                finishCameraFailure(statusFormatter.failure(failure))
            } else {
                scheduleRecovery(
                    sessionProfile ?: activeCombinationPlan?.normalResidentProfile
                        ?: CameraSessionProfile.RAW_ONLY,
                    statusFormatter.recovery(failure),
                    decision.delayMs,
                )
            }
            return
        }
        when (decision.action) {
            CameraRecoveryAction.RETRY -> scheduleRecovery(
                decision.profile ?: sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY,
                statusFormatter.recovery(failure),
                decision.delayMs,
            )

            CameraRecoveryAction.DOWNGRADE -> if (!advanceSystemCombination(
                    statusFormatter.recovery(failure),
                    decision.delayMs,
                )
            ) {
                scheduleRecovery(
                    decision.profile ?: sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY,
                    statusFormatter.recovery(failure),
                    decision.delayMs,
                )
            }

            CameraRecoveryAction.STOP -> {
                val canChangeCameraRoute = failure == CameraFailureKind.DEVICE ||
                    failure == CameraFailureKind.SERVICE ||
                    failure == CameraFailureKind.DISCONNECTED ||
                    failure == CameraFailureKind.UNKNOWN
                if (!canChangeCameraRoute || !tryNextCameraRoute()) {
                    finishCameraFailure(statusFormatter.failure(failure))
                }
            }
        }
    }

    private fun advanceSystemCombination(
        message: String,
        delayMs: Long = SESSION_RECOVERY_DELAY_MS,
    ): Boolean {
        if (filmPreview.isEnabled || combinationSelectionMode != MeteringCombinationSelectionMode.SYSTEM ||
            isConfirmedRawWorkflow()
        ) return false
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
        if (isConfirmedRawWorkflow()) return false
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
        filmPreview.onCameraFailure(message)
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

    private fun resetRecoveryState() {
        if (pendingSystemWorkflowProbePlanId != null) combinationWorkflowProbe.abandon()
        recoveryState.reset()
        activeCombinationPlan = null
        rawWorkflowConfirmation = CameraWorkflowConfirmationPolicy.unverified()
        rejectedSystemCombinationIds.clear()
        pendingSystemWorkflowProbePlanId = null
        manualSafePreviewActive = false
        zoneYuvSessionUnavailable = false
        frameRateController.resetForCamera()
        if (filmPreview.isEnabled) frameRateController.limitToStandardRate()
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
            // A rebuilt stream starts from live AE evidence, never the RAW frame's exposure.
            exposurePreviewState.reset(preserveSelection = true)
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
                !filmPreview.isEnabled && !manualSafePreviewActive &&
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
                            !combinationWorkflowProbe.isActive && isConfirmedRawWorkflow()
                        ) {
                            activeCombinationPlan?.let { plan ->
                                combinationSelectionStore.saveSystem(
                                    cameraRouteId = cameraInfo.cameraId,
                                    mode = meteringPipelineMode,
                                    planId = plan.id,
                                    routeIdentity = confirmationRouteId(),
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
        if (!started || stopInProgress || meteringOperationActive || filmPreview.isEnabled ||
            activeSessionProfile?.usesPreview != true || previewBaseline.isActive || exposurePreviewCalibration.isActive) return
        exposurePreviewState.apply(characteristics, cameraInfo)
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
        if (pendingManualAcceptance != null || manualProbeId != null) {
            val error = IllegalStateException("Preview failed during manual verification")
            failManualAcceptance(error)
            combinationWorkflowProbe.cancel(error)
            finishCameraFailure(error.message.orEmpty())
            return
        }
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

    private fun isConfirmedRawWorkflow(): Boolean =
        activeCombinationPlan?.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW &&
            CameraWorkflowConfirmationPolicy.isRawConfirmedFor(
                confirmation = rawWorkflowConfirmation,
                planId = activeCombinationPlan?.id,
                routeId = confirmationRouteId(),
            )

    /** A failed/cancelled revalidation must not accidentally grant RAW capture eligibility. */
    private fun manualRawCaptureBlocked(): Boolean =
        manualVerification.blocksRawMetering() ||
            manualAcceptanceRecoveryRequired ||
            manualSelectionRequiresChoice ||
            (combinationSelectionMode == MeteringCombinationSelectionMode.MANUAL &&
                activeCombinationPlan?.combinationClass == CameraCombinationClass.HIGH_ACCURACY_RAW &&
                !isConfirmedRawWorkflow())

    /** The actual HAL route identity, not the user-facing lens id, for confirmation scoping. */
    private fun confirmationRouteId(): String =
        activeOpenConfiguration?.confirmationRouteIdentity() ?: cameraInfo.cameraId

    /** Evidence must be based on a resolved Camera2 route, never on the UI camera-id fallback. */
    private fun exactConfirmationRouteId(): String? =
        activeOpenConfiguration?.confirmationRouteIdentity()

    private fun invalidateManualVerification() {
        manualVerification.invalidateExternal(manualVerificationEpoch.incrementAndGet())
        notifiedManualRevalidationKey = null
        pendingManualRevalidation = null
    }

    internal fun isManualRevalidationCurrent(request: ManualCombinationRevalidationRequest): Boolean =
        started && combinationSelectionMode == MeteringCombinationSelectionMode.MANUAL &&
            pendingManualRevalidation == request &&
            request.belongsTo(requestedCameraId, manualVerificationOwnerEpoch)

    private fun publishManualRevalidation(request: ManualCombinationRevalidationRequest) {
        val key = "${request.selectionCameraId}|${request.ownerEpoch}|${request.planId}|${request.canStart}"
        if (notifiedManualRevalidationKey == key) return
        notifiedManualRevalidationKey = key
        pendingManualRevalidation = request
        mainHandler.post {
            if (isManualRevalidationCurrent(request)) callback.onManualCombinationRevalidationNeeded(request)
        }
    }

    private fun readyCameraStatus(): String = statusFormatter.ready(
        cameraInfo = cameraInfo,
        pipelineMode = meteringPipelineMode,
        lowLight = frameRateController.isLowLight,
        reducedFrameRate = previewFpsRange?.let {
            it.lower < it.upper || it.upper < PreviewFrameRateMode.LOW.requestedCeiling
        } == true,
        safePreview = manualSafePreviewActive,
        transientRawAvailable = transientZoneRawAvailable(),
    )

    private fun previewFrameRateCeiling(): Int? = frameRateController.requestCeiling?.let {
        if (filmPreview.isEnabled) it.coerceAtMost(30) else it
    }

    private fun choosePreviewFpsRange(profile: CameraSessionProfile): Range<Int>? {
        val ceiling = previewFrameRateCeiling() ?: return null
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
            val resolution = metadataResolver.resolve(
                total = result,
                routeKind = selectedRouteKind,
                requestedPhysicalCameraId = selectedPhysicalCameraId,
            )
            val effectiveResult = resolution.effectiveResult
            if (effectiveResult == null) {
                // A fixed physical output without its own result must not borrow the logical
                // metadata. The preview keeps running, but formal measurement metadata is withheld
                // until a valid frame arrives instead of exposing a stale or foreign value.
                warnRejectedPreviewMetadata(resolution.failure)
                latestResult = null
                distanceCapture.invalidate("Physical capture metadata unavailable")
                return
            }
            latestResult = effectiveResult
            observeAbsoluteExposureMetadata(effectiveResult)
            val timestamp = resolution.pairedTimestampNs
                ?: effectiveResult.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp != null) previewResultStore.put(timestamp, effectiveResult)
            if (session === captureSession && (request.tag as? PreviewRequestTag)?.cameraGeneration == cameraGeneration) {
                filmPreview.observeResult(request, effectiveResult, timestamp)
            }
            runtimeMetadata.observeActualPreviewFps(timestamp, cameraInfo)
                ?.let(::postRuntimeInfo)
            runtimeMetadata.observePreviewSensorViewport(
                result = effectiveResult,
                currentInfo = cameraInfo,
                previewSize = previewSize,
                outputAspectOverride = previewOutputAspectOverride,
            )?.let(::postRuntimeInfo)
            updateDynamicLensInfo(effectiveResult)
            if (session === captureSession && (request.tag as? PreviewRequestTag)?.cameraGeneration == cameraGeneration) {
                exposurePreviewState.onPreviewFrame(effectiveResult, SystemClock.elapsedRealtimeNanos(),
                    advance = !meteringOperationActive && !previewBaseline.isActive && !exposurePreviewCalibration.isActive && !filmPreview.isEnabled)
                applyRequestedExposurePreview()
            }
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

    private fun warnRejectedPreviewMetadata(failure: CaptureMetadataFailure?) {        val now = SystemClock.elapsedRealtime()
        if (now - lastRejectedMetadataLogMs < REJECTED_METADATA_LOG_INTERVAL_MS) return
        lastRejectedMetadataLogMs = now
        Log.w(
            TAG,
            "Preview frame metadata rejected: $failure " +
                "route=$selectedRouteKind physical=$selectedPhysicalCameraId",
        )
    }

    private var lastRejectedMetadataLogMs = 0L

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
        publishGeometryContext()
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
            observeImage = distanceCapture::onImage,
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
        filmPreview.onCameraClosed()
        pendingManualAcceptance?.let {
            finishManualAcceptance(it, ManualAcceptanceOutcome.Cancelled(it.approvalSaved))
        }
        if (manualProbeId != null) {
            combinationWorkflowProbe.cancel(IllegalStateException("Camera closed during manual probe"))
        }
        manualProbeId = null
        manualProbePlanId = null
        invalidateManualVerification()
        cameraGeneration += 1
        previewSurfaceCoordinator.reset()
        cameraFailureStage = CameraFailureStage.OPENING
        previewBaseline.cancel()
        exposurePreviewCalibration.cancelForCameraClose()
        exposurePreviewUpdateQueued.set(false)
        exposurePreviewState.reset(preserveSelection = preserveExposurePreview)
        if (!preserveExposurePreview) {
            pendingCalibrationMeteringPlan = null
            calibrationSessionProfile = null
            calibrationStorageCameraId = null
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
        zoneRaw.reset()
        pendingResidentSessionProfile = null
        meteringOperationActive = false
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
        selectedRouteKind = CameraRouteKind.LOGICAL_AUTO
        activeOpenConfiguration = null
        calibrationStore.setActiveContext(null)
        vignettingCalibrationStore.setActiveContext(null)
        runtimeMetadata.reset()
        latestResult = null
        previewResultStore.clear()
        previewHealth.refreshMonitoring()
    }

    private fun postInfo(info: CameraUiInfo) {
        cameraInfo = info
        val generation = cameraGeneration
        Log.i(
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
        private const val REJECTED_METADATA_LOG_INTERVAL_MS = 5_000L
        private const val MAX_METERING_REFERENCE_AGE_NS = 350_000_000L
        private const val SESSION_RECOVERY_DELAY_MS = 300L
        private const val STABLE_PREVIEW_RESET_DELAY_MS = 10_000L
        private const val MAX_TOTAL_RECOVERY_ATTEMPTS = 6
    }
}
