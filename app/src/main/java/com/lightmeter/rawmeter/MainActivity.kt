package com.lightmeter.rawmeter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File

class MainActivity : Activity(), CameraControllerCallback {
    private lateinit var state: MeterState
    private lateinit var meterLayout: MeterLayout
    private lateinit var cameraController: CameraController
    private lateinit var calibrationEnvironmentStore: CalibrationEnvironmentStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rawDialogVisible = false
    private var compatibilityModeDialogVisible = false
    private var angleCompatibilityDialogVisible = false
    private var calibrationResetDialogVisible = false
    private var vignettingDialogVisible = false
    private var vignettingResetDialogVisible = false
    private var vignettingCalibrationPending = false
    private val calibrationCoordinator = MeteringCalibrationCoordinator()
    private var zoneMeasurementPending = false
    private var activityResumed = false
    private var appliedPipelineMode: MeteringPipelineMode? = null
    private var cameraPermissionDialogVisible = false
    private var cameraPermissionRequestInFlight = false
    private var pendingCalibrationEnvironmentChange: CalibrationEnvironmentChange? = null
    private var calibrationDisplayCameraId: String? = null
    private var calibrationDisplaySelectionId: String? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var parameterLocation: RecordedLocation? = null
    private var parameterLocationListener: LocationListener? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val preview = meterLayout.textureView
            if (preview.display?.displayId == displayId) {
                updatePreviewTransform(preview.width, preview.height)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        state = MeterState(this)
        calibrationEnvironmentStore = CalibrationEnvironmentStore(this)
        applyRequestedOrientationFromState()

        meterLayout = MeterLayout(this, state)
        cameraController = CameraController(this, this)
        appliedPipelineMode = state.meteringPipelineMode
        cameraController.setMeteringPipelineMode(state.meteringPipelineMode)
        cameraController.setPreviewHealthDetectionEnabled(
            state.previewHealthDetectionMode == PreviewHealthDetectionMode.ON,
        )
        val cameraPermissionGranted =
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val selectedCamera = if (cameraPermissionGranted) {
            refreshCameraCatalog()
        } else {
            // Preserve the stored route until permission-sensitive CameraCharacteristics can be
            // queried. An incomplete pre-permission catalog must not overwrite a physical lens.
            state.selectedCameraId.ifBlank { null }
        }
        selectedCamera?.let(cameraController::selectCamera)
        cameraController.attach(meterLayout.textureView)
        meterLayout.listener = object : MeterLayout.Listener {
            override fun onMeasureRequested() {
                cameraController.measure(
                    state.frameFormat,
                    state.frameLandscape,
                    state.zoom,
                    state.meteringMode,
                    meteringAngleDegrees = state.angleMeteringDegrees,
                )
            }

            override fun onOrientationToggle() {
                if (!canControlWindowOrientation()) {
                    state.landscape = resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE
                    Toast.makeText(
                        this@MainActivity,
                        localized(
                            "大屏设备的方向由系统窗口控制",
                            "Window orientation is controlled by the system on large screens",
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                    meterLayout.refresh(frameChanged = true)
                    return
                }
                state.landscape = !state.landscape
                applyRequestedOrientationFromState()
            }

            override fun onPreviewGeometryChanged(width: Int, height: Int) {
                updatePreviewTransform(width, height)
            }

            override fun onControlsChanged(frameChanged: Boolean) {
                val previousMode = appliedPipelineMode
                val currentMode = state.meteringPipelineMode
                appliedPipelineMode = currentMode
                cameraController.setMeteringPipelineMode(currentMode)
                cameraController.setPreviewHealthDetectionEnabled(
                    state.previewHealthDetectionMode == PreviewHealthDetectionMode.ON,
                )
                if (previousMode != null && previousMode != currentMode) {
                    refreshCalibrationCorrections()
                    if (currentMode == MeteringPipelineMode.FAST) {
                        // Some vendor window managers can consume a dialog opened inside the
                        // option's ACTION_UP dispatch. Post it after this touch sequence finishes.
                        mainHandler.post {
                            if (appliedPipelineMode == MeteringPipelineMode.FAST) {
                                showCompatibilityModeWarning()
                            }
                        }
                    }
                }
                meterLayout.refresh(frameChanged)
                if (!frameChanged) {
                    updatePreviewTransform(
                        meterLayout.textureView.width,
                        meterLayout.textureView.height,
                    )
                }
                updateExposurePreviewFromMeter()
            }

            override fun onSettingRejected(key: SettingKey, value: String) {
                if (key == SettingKey.METERING_MODE && value == MeteringMode.ANGLE.name) {
                    showAngleCompatibilityWarning()
                }
            }

            override fun onCalibrationOpened() {
                refreshCalibrationCorrections()
            }

            override fun onSafePreviewRequested() {
                val accepted = cameraController.switchToSafePreview()
                if (accepted) meterLayout.closeSettings()
                Toast.makeText(
                    this@MainActivity,
                    if (accepted) {
                        localized(
                            "正在切换到安全预览；重新选择摄像头或重启后恢复",
                            "Switching to safe preview; select a camera or restart to restore",
                        )
                    } else {
                        localized(
                            "请等待当前测量或校准完成后再切换",
                            "Wait for the current measurement or calibration before switching",
                        )
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }

            override fun onCameraSelected(cameraId: String) {
                if (state.measuring || calibrationCoordinator.isActive ||
                    vignettingCalibrationPending || zoneMeasurementPending
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        localized("请等待本次测光完成", "Wait for this measurement"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                state.setCameraHidden(cameraId, false)
                if (state.selectCamera(cameraId)) {
                    state.transientMessage = localized("正在切换摄像头", "Switching camera")
                    meterLayout.refresh(frameChanged = true)
                    cameraController.selectCamera(cameraId)
                }
            }

            override fun onCameraNoteRequested(cameraId: String) {
                showCameraNoteDialog(cameraId)
            }

            override fun onCameraVisibilityRequested(cameraId: String, hidden: Boolean) {
                if (!state.setCameraHidden(cameraId, hidden)) {
                    Toast.makeText(
                        this@MainActivity,
                        localized("至少保留一个可见摄像头", "Keep at least one camera visible"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                if (hidden && cameraId == state.selectedCameraId) {
                    state.availableCameras.firstOrNull { !state.isCameraHidden(it.cameraId) }
                        ?.let { replacement ->
                            state.selectCamera(replacement.cameraId)
                            cameraController.selectCamera(replacement.cameraId)
                        }
                }
                meterLayout.refresh(frameChanged = true)
            }

            override fun onCalibrationMeasureRequested(referenceEv100: Double) {
                startMeteringCalibration(referenceEv100)
            }

            override fun onCalibrationResetRequested() {
                showCalibrationResetDialog()
            }

            override fun onCalibrationHistoryRestoreRequested(updatedAtEpochMs: Long) {
                val restored = cameraController.restoreUserCalibration(
                    updatedAtEpochMs = updatedAtEpochMs,
                    cameraId = calibrationDisplayCameraId
                        ?: cameraController.currentCalibrationCameraId(),
                ) ?: return
                meterLayout.calibrationView.showHistoryRestored(restored)
                meterLayout.refresh()
            }

            override fun onVignettingCalibrationOpened() {
                showVignettingGuideDialog()
            }

            override fun onVignettingCalibrationRequested() {
                if (!vignettingCalibrationPending && !calibrationCoordinator.isActive) {
                    startVignettingCalibration()
                }
            }

            override fun onVignettingCalibrationResetRequested() {
                showVignettingResetDialog()
            }

            override fun onVignettingHistoryRestoreRequested(createdAtEpochMs: Long) {
                val restored = cameraController.restoreVignettingCalibration(createdAtEpochMs)
                    ?: return
                val cameraId = state.cameraInfo.calibrationCameraId.ifBlank { state.selectedCameraId }
                state.refreshVignettingCalibration(cameraId)
                meterLayout.vignettingCalibrationView.showHistoryRestored(restored)
                meterLayout.refresh()
            }

            override fun onZoneMeasureRequested(
                marker: ZoneMarker,
                target: ZoneMeteringTarget?,
            ) {
                if (zoneMeasurementPending || state.measuring) return
                zoneMeasurementPending = true
                state.measuring = true
                meterLayout.zoneView.invalidate()
                val accepted = cameraController.measure(
                    state.frameFormat,
                    state.frameLandscape,
                    state.zoom,
                    state.meteringMode,
                    target,
                    meteringAngleDegrees = state.angleMeteringDegrees,
                )
                if (!accepted) {
                    zoneMeasurementPending = false
                    state.measuring = false
                    meterLayout.failZoneMeasurement()
                    state.transientMessage = localized(
                        "请等待当前测量完成",
                        "Wait for the current measurement to finish",
                    )
                    meterLayout.refresh()
                    clearTransientMessageLater()
                }
            }

            override fun onZoneTrackingActiveChanged(active: Boolean) {
                cameraController.setTrackingFramesEnabled(active)
            }

            override fun onParameterGpsEnableRequested() {
                enableParameterGps()
            }

            override fun onColorTemperatureEstimateRequested() {
                val screenAspect = if (state.frameLandscape) {
                    state.frameFormat.landscapeAspect
                } else {
                    1f / state.frameFormat.landscapeAspect
                }
                cameraController.estimateColorTemperature(
                    screenAspect = screenAspect,
                    zoom = state.zoom,
                ) { result ->
                    result.onSuccess(meterLayout::showColorTemperatureReading)
                        .onFailure { error ->
                            meterLayout.showColorTemperatureError(
                                error.message ?: localized("色温估算失败，请重试", "Unable to estimate color temperature"),
                            )
                        }
                }
            }

            override fun onParameterCaptureRequested(
                draftId: String,
                snapshot: ParameterMeterSnapshot,
                options: ParameterRecordOptions,
                previewPath: String,
            ) {
                captureParameterRecord(draftId, snapshot, options, previewPath)
            }
        }
        setContentView(meterLayout)
        registerPredictiveBackCallback()
        window.decorView.post { hideSystemBars() }
        ensureCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, mainHandler)
        hideSystemBars()
        meterLayout.resumeZoneTracking()
        cameraController.setTrackingFramesEnabled(meterLayout.isZoneMode)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            refreshCameraCatalog()?.let(cameraController::selectCamera)
            cameraController.start()
            maybeShowCalibrationEnvironmentChange()
        } else {
            ensureCameraPermission()
        }
        if (meterLayout.isParameterGpsEnabled() &&
            (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        ) {
            enableParameterGps()
        }
    }

    override fun onPause() {
        activityResumed = false
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        parameterLocationListener?.let { listener ->
            (getSystemService(LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener)
        }
        parameterLocationListener = null
        cameraController.setTrackingFramesEnabled(false)
        meterLayout.pauseZoneTracking()
        if (zoneMeasurementPending) {
            zoneMeasurementPending = false
            state.measuring = false
            meterLayout.failZoneMeasurement()
        }
        if (calibrationCoordinator.isActive) {
            clearCalibrationRun()
            meterLayout.calibrationView.showError(
                localized("校准已中止，请重新测量", "Calibration was interrupted. Measure again"),
            )
        }
        if (vignettingCalibrationPending) {
            vignettingCalibrationPending = false
            meterLayout.vignettingCalibrationView.showError(
                localized("暗角校准已中止", "Vignetting calibration was interrupted"),
            )
        }
        if (state.measuring) {
            state.measuring = false
            state.transientMessage = null
            meterLayout.refresh()
        }
        cameraController.stop()
        super.onPause()
    }

    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (handleBackNavigation()) return
        super.onBackPressed()
    }

    private fun handleBackNavigation(): Boolean {
        if (meterLayout.closeInformationFromBack()) return true
        if (meterLayout.closeParameterHistoryFromBack()) return true
        if (meterLayout.closeCameraManagement()) return true
        if (meterLayout.closeVignettingCalibration()) return true
        if (meterLayout.closeCalibration()) return true
        if (meterLayout.closeFilmSelector()) return true
        if (meterLayout.closeParameterEditorFromBack()) return true
        if (meterLayout.closeSettings()) return true
        if (meterLayout.closeTools()) return true
        if (meterLayout.closeZoneMode()) return true
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        state.landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        meterLayout.refresh(frameChanged = true)
    }

    /** Android 16 ignores fixed orientation on sw600dp+ displays for target 36 apps. */
    private fun canControlWindowOrientation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA ||
            resources.configuration.smallestScreenWidthDp < 600

    private fun applyRequestedOrientationFromState() {
        if (!canControlWindowOrientation()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            state.landscape = resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            return
        }
        requestedOrientation = if (state.landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val granted = permissions.indices.any { index ->
                permissions[index] in LOCATION_PERMISSIONS &&
                    grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) {
                enableParameterGps()
            } else {
                meterLayout.setParameterGpsEnabled(false)
                Toast.makeText(
                    this,
                    localized("未获得定位权限，GPS 记录保持关闭", "Location permission was denied; GPS recording remains off"),
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        cameraPermissionRequestInFlight = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            refreshCameraCatalog()?.let(cameraController::selectCamera)
            if (activityResumed) cameraController.start()
            maybeShowCalibrationEnvironmentChange()
        } else {
            val message = localized(
                "需要相机权限才能进行测光。",
                "Camera permission is required for metering.",
            )
            state.cameraInfo = state.cameraInfo.copy(status = message)
            meterLayout.refresh()
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            showCameraPermissionRecoveryDialog()
        }
    }

    override fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun onCameraInfo(info: CameraUiInfo) {
        if (info.cameraId.isNotBlank() && info.cameraId != state.selectedCameraId) {
            state.selectCamera(info.cameraId)
        }
        state.cameraInfo = info
        val switchingMessage = localized("正在切换摄像头", "Switching camera")
        if (state.transientMessage == switchingMessage &&
            info.cameraId == state.selectedCameraId &&
            info.status != switchingMessage
        ) {
            state.transientMessage = null
        }
        if (state.zoom > info.maxDisplayZoom) state.zoom = info.maxDisplayZoom
        if (meterLayout.isCalibrationOpen) {
            refreshCalibrationCorrections()
        }
        meterLayout.refresh(frameChanged = meterLayout.isVignettingCalibrationOpen)
        updateExposurePreviewFromMeter()
    }

    override fun onRawUnavailable() {
        if (!activityResumed) return
        showRawUnavailableDialog(force = false)
    }

    override fun tryReserveZoneTrackingFrame(): Boolean =
        meterLayout.tryReserveZoneTrackingFrame()

    override fun cancelZoneTrackingFrameReservation() {
        meterLayout.cancelZoneTrackingFrameReservation()
    }

    override fun onZoneTrackingFrame(frame: ZoneTrackingFrame) {
        meterLayout.offerZoneTrackingFrame(frame)
    }

    override fun onMeteringStarted(source: MeteringSource, frameCount: Int) {
        if (!activityResumed) return
        if (calibrationCoordinator.isActive) {
            val step = calibrationCoordinator.currentStep()
            meterLayout.calibrationView.setMeasuring(
                measuring = true,
                frameCount = frameCount,
                source = source,
                step = step?.position,
                totalSteps = step?.total,
            )
            return
        }
        if (zoneMeasurementPending) {
            state.measuring = true
            meterLayout.zoneView.invalidate()
            return
        }
        state.measuring = true
        state.transientMessage = if (source == MeteringSource.RAW) {
            localized(
                "正在读取 RAW 流（$frameCount 张）",
                "Reading RAW stream ($frameCount frames)",
            )
        } else {
            localized(
                "正在读取预览流",
                "Reading preview stream",
            )
        }
        meterLayout.refresh()
    }

    override fun onMeteringBaselineRestoring() {
        if (!activityResumed) return
        if (calibrationCoordinator.isActive) {
            meterLayout.calibrationView.setRestoringAutoExposure()
        } else {
            state.measuring = true
            state.transientMessage = localized(
                "正在恢复自动曝光",
                "Restoring auto exposure",
            )
            meterLayout.refresh()
        }
    }

    override fun onMeterReading(reading: MeterReading) {
        if (!activityResumed) return
        if (calibrationCoordinator.isActive) {
            handleCalibrationReading(reading)
            return
        }
        if (zoneMeasurementPending) {
            zoneMeasurementPending = false
            state.measuring = false
            state.lastReading = reading
            meterLayout.completeZoneMeasurement(reading)
            meterLayout.refresh()
            updateExposurePreviewFromMeter()
            return
        }
        state.measuring = false
        state.lastReading = reading
        state.lastNormalReading = reading
        state.sceneEv100 = reading.sceneEv100
        state.transientMessage = when {
            reading.source != MeteringSource.RAW ->
                localized(
                    "预览流测光完成 · EV ${"%.1f".format(reading.sceneEv100)}",
                    "Preview-stream metering complete · EV ${"%.1f".format(reading.sceneEv100)}",
                )
            reading.clippedFraction > 0.1 ->
                localized(
                    "中央高光接近饱和 · EV ${"%.1f".format(reading.sceneEv100)}",
                    "Center highlights are near clipping · EV ${"%.1f".format(reading.sceneEv100)}",
                )
            reading.rawLuma < 0.002 ->
                localized(
                    "中央信号较弱 · EV ${"%.1f".format(reading.sceneEv100)}",
                    "Center signal is weak · EV ${"%.1f".format(reading.sceneEv100)}",
                )
            else ->
                localized(
                    "RAW 流测光完成 · EV ${"%.1f".format(reading.sceneEv100)}",
                    "RAW-stream metering complete · EV ${"%.1f".format(reading.sceneEv100)}",
                )
        }
        meterLayout.refresh()
        updateExposurePreviewFromMeter()
        clearTransientMessageLater()
    }

    override fun onMeteringError(message: String) {
        if (!activityResumed) return
        if (calibrationCoordinator.isActive) {
            continueCalibrationAfterError(message)
            return
        }
        if (zoneMeasurementPending) {
            zoneMeasurementPending = false
            state.measuring = false
            meterLayout.failZoneMeasurement()
            state.transientMessage = message
            meterLayout.refresh()
            updateExposurePreviewFromMeter()
            clearTransientMessageLater()
            return
        }
        state.measuring = false
        state.transientMessage = message
        meterLayout.refresh()
        updateExposurePreviewFromMeter()
        clearTransientMessageLater()
    }

    override fun onExposurePreviewUnavailable() {
        if (!activityResumed || state.exposurePreviewMode != ExposurePreviewMode.ON) return
        Toast.makeText(
            this,
            localized(
                "当前摄像头不支持手动曝光或曝光补偿，无法生成曝光预览",
                "This camera supports neither manual exposure nor exposure compensation, " +
                    "so exposure preview is unavailable",
            ),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun updateExposurePreviewFromMeter() {
        val selection = if (state.exposurePreviewMode == ExposurePreviewMode.ON &&
            !state.measuring && !calibrationCoordinator.isActive && !vignettingCalibrationPending
        ) {
            meterLayout.currentExposurePreviewSelection()
        } else {
            null
        }
        cameraController.updateExposurePreview(selection)
    }

    override fun onVignettingCalibrationStarted() {
        if (!vignettingCalibrationPending) return
        meterLayout.vignettingCalibrationView.setCalibrating()
    }

    override fun onVignettingCalibrationCompleted(info: VignettingCalibrationInfo) {
        vignettingCalibrationPending = false
        val cameraId = state.cameraInfo.calibrationCameraId.ifBlank { state.selectedCameraId }
        state.refreshVignettingCalibration(cameraId)
        meterLayout.vignettingCalibrationView.showResult(info)
        meterLayout.refresh()
    }

    override fun onVignettingCalibrationError(message: String) {
        vignettingCalibrationPending = false
        meterLayout.vignettingCalibrationView.showError(message)
        meterLayout.refresh()
    }

    private fun startMeteringCalibration(referenceEv100: Double) {
        if (calibrationCoordinator.isActive || !activityResumed) return
        val sources = MeteringCalibrationPlan.create(
            mode = state.meteringPipelineMode,
            capabilities = cameraController.calibrationCapabilities(),
        )
        if (sources.isEmpty()) {
            meterLayout.calibrationView.showError(
                localized("当前相机没有可校准的测光流", "This camera has no calibratable metering stream"),
            )
            return
        }
        val firstStep = calibrationCoordinator.start(
            referenceEv100 = referenceEv100,
            sources = sources,
            cameraIdentity = cameraController.currentCalibrationIdentity(),
        ) ?: return
        startCalibrationMeasurement(firstStep.step)
    }

    private fun startCalibrationMeasurement(step: MeteringCalibrationStep) {
        if (!calibrationCoordinator.isActive || !activityResumed) return
        meterLayout.calibrationView.setMeasuring(
            true,
            source = step.source,
            step = step.position,
            totalSteps = step.total,
        )
        val accepted = cameraController.measure(
            state.frameFormat,
            state.frameLandscape,
            state.zoom,
            state.meteringMode,
            meteringAngleDegrees = state.angleMeteringDegrees,
            requestedSource = step.source,
        )
        if (!accepted) {
            clearCalibrationRun()
            meterLayout.calibrationView.showError(
                localized(
                    "请等待当前测量完成后再校准",
                    "Wait for the current measurement to finish before calibrating",
                ),
            )
        }
    }

    private fun handleCalibrationReading(reading: MeterReading) {
        handleCalibrationTransition(
            calibrationCoordinator.onReading(reading, cameraController.currentCalibrationIdentity()),
        )
    }

    private fun continueCalibrationAfterError(message: String) {
        handleCalibrationTransition(calibrationCoordinator.onStageError(message))
    }

    private fun handleCalibrationTransition(transition: MeteringCalibrationTransition) {
        when (transition) {
            is MeteringCalibrationTransition.Next -> startCalibrationMeasurement(transition.step)
            is MeteringCalibrationTransition.Complete -> finishCalibration(transition.result)
            MeteringCalibrationTransition.LensChanged -> {
                cameraController.finishCalibrationSession()
                meterLayout.calibrationView.showError(
                    localized(
                        "校准期间镜头发生切换，未保存本次结果",
                        "The lens changed during calibration; this run was not saved",
                    ),
                )
                meterLayout.refresh()
            }
            MeteringCalibrationTransition.Idle -> clearCalibrationRun()
        }
    }

    private fun finishCalibration(result: MeteringCalibrationCompletion) {
        if (!result.referenceEv100.isFinite() || result.measurements.isEmpty()) {
            clearCalibrationRun()
            meterLayout.calibrationView.showError(
                result.terminalError ?: localized("校准输入已失效，请重试", "Calibration input expired. Try again"),
            )
            return
        }
        val record = cameraController.updateUserCalibration(
            referenceEv100 = result.referenceEv100,
            measurements = result.measurements,
        )
        clearCalibrationRun()
        if (result.terminalError == null && !result.hasFailures) {
            meterLayout.calibrationView.showResult(record)
        } else {
            meterLayout.calibrationView.showPartialResult(
                record,
                localized(
                    "部分测光流已校准；未完成的来源保留原有修正",
                    "Some streams were calibrated; incomplete sources kept their prior corrections",
                ),
            )
        }
        meterLayout.refresh()
    }

    private fun clearCalibrationRun() {
        calibrationCoordinator.cancel()
        cameraController.finishCalibrationSession()
    }

    private fun refreshCalibrationCorrections() {
        if (calibrationDisplaySelectionId != state.selectedCameraId) {
            calibrationDisplaySelectionId = state.selectedCameraId
            calibrationDisplayCameraId = null
        }
        val liveCameraId = cameraController.currentCalibrationCameraId()
        val cameraId = CalibrationDisplayIdentity.resolve(
            boundCameraId = calibrationDisplayCameraId,
            liveCameraId = liveCameraId,
        )
        calibrationDisplayCameraId = cameraId
        val record = state.cameraCalibrationRecord(cameraId)
        val showRawStream = shouldShowRawCalibration()
        val rawCorrection = if (showRawStream) {
            record?.rawCorrectionEv ?: 0.0
        } else {
            null
        }
        meterLayout.calibrationView.setCurrentCorrections(
            cameraId = cameraId,
            rawCorrectionEv = rawCorrection,
            yuvCorrectionEv = record?.yuvCorrectionEv,
            ispPreviewCorrectionEv = record?.ispPreviewCorrectionEv,
            legacyCompatibleCorrectionEv = record?.legacyCompatibleCorrectionEv,
            showRawStream = showRawStream,
        )
    }

    /** RAW is displayed only when this mode can use it and the selected camera exposes it. */
    private fun shouldShowRawCalibration(): Boolean =
        CalibrationStreamPolicy.includesRaw(
            mode = state.meteringPipelineMode,
            rawSupported = state.currentCamera()?.rawAvailable ?: state.cameraInfo.rawAvailable,
        )

    /** Re-query permission-sensitive Camera2 metadata without changing the existing picker flow. */
    private fun refreshCameraCatalog(): String? {
        val selectedCamera = state.updateCameraCatalog(cameraController.availableCameras())
        pendingCalibrationEnvironmentChange = calibrationEnvironmentStore.evaluate(
            cameras = state.availableCameras,
            hasCalibrationArtifacts = state.hasCalibrationArtifacts(),
        )
        if (::meterLayout.isInitialized) meterLayout.refresh(frameChanged = true)
        return selectedCamera
    }

    private fun ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            maybeShowCalibrationEnvironmentChange()
            return
        }
        if (cameraPermissionRequestInFlight || cameraPermissionDialogVisible) return
        if (cameraPermissionRequestMarker().isFile) {
            showCameraPermissionRecoveryDialog()
            return
        }
        runCatching { cameraPermissionRequestMarker().createNewFile() }
        requestCameraPermission()
    }

    private fun cameraPermissionRequestMarker(): File =
        File(noBackupFilesDir, CAMERA_PERMISSION_REQUEST_MARKER)

    private fun requestCameraPermission() {
        cameraPermissionRequestInFlight = true
        requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    private fun showCameraPermissionRecoveryDialog() {
        if (!activityResumed || cameraPermissionDialogVisible ||
            cameraPermissionRequestInFlight || isFinishing
        ) return
        cameraPermissionDialogVisible = true
        val canRequestAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        AlertDialog.Builder(this)
            .setTitle(localized("需要相机权限", "Camera permission required"))
            .setMessage(
                localized(
                    if (canRequestAgain) {
                        "测光需要访问相机画面和曝光参数。应用不会上传相机画面。"
                    } else {
                        "相机权限已被关闭。请在系统设置中允许相机权限后再进行测光。"
                    },
                    if (canRequestAgain) {
                        "Metering needs access to camera frames and exposure metadata. " +
                            "Camera frames are not uploaded."
                    } else {
                        "Camera permission is disabled. Allow it in system settings before " +
                            "using the meter."
                    },
                ),
            )
            .setNegativeButton(localized("稍后处理", "Later"), null)
            .setPositiveButton(
                localized(
                    if (canRequestAgain) "重新授权" else "打开设置",
                    if (canRequestAgain) "Try again" else "Open settings",
                ),
            ) { _, _ ->
                if (canRequestAgain) {
                    requestCameraPermission()
                } else {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
            }
            .setOnDismissListener { cameraPermissionDialogVisible = false }
            .show()
    }

    private fun maybeShowCalibrationEnvironmentChange() {
        val change = pendingCalibrationEnvironmentChange ?: return
        if (!activityResumed || isFinishing ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) return
        pendingCalibrationEnvironmentChange = null
        calibrationEnvironmentStore.markPrompted(change)
        AlertDialog.Builder(this)
            .setTitle(localized("建议重新校准", "Recalibration recommended"))
            .setMessage(
                localized(
                    "检测到设备或者相机环境变化，建议重新校准。",
                    "A device or camera-environment change was detected. Recalibration is " +
                        "recommended.",
                ),
            )
            .setNegativeButton(localized("稍后处理", "Later"), null)
            .setPositiveButton(localized("立即校准", "Calibrate now")) { _, _ ->
                // Open the Settings calibration choices; do not start either workflow directly.
                meterLayout.showCalibrationSettings()
            }
            .show()
    }

    private fun registerPredictiveBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = OnBackInvokedCallback {
            if (!handleBackNavigation()) finishAfterTransition()
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback,
        )
        backInvokedCallback = callback
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        backInvokedCallback = null
        super.onDestroy()
    }

    private fun showRawUnavailableDialog(force: Boolean) {
        val preferences = getSharedPreferences("raw_light_meter_state", MODE_PRIVATE)
        if (!force && preferences.getBoolean("suppress_raw_warning", false)) return
        val cameraId = state.cameraInfo.cameraId.ifBlank { state.selectedCameraId }
        val cameraWarningKey = "suppress_raw_warning_camera_${cameraId.hashCode()}"
        if (!force && preferences.getBoolean(cameraWarningKey, false)) return
        if (rawDialogVisible || isFinishing) return
        rawDialogVisible = true
        val checked = booleanArrayOf(false)
        val camera = state.availableCameras.firstOrNull { it.cameraId == cameraId }
        val cameraName = camera?.let(state::cameraName)
            ?: localized("当前摄像头", "Current camera")
        AlertDialog.Builder(this)
            .setTitle(localized("已切换到预览测光", "Preview metering enabled"))
            .setMessage(
                localized(
                    "$cameraName 不支持 RAW 流，应用已自动使用手机处理后的预览画面测光。" +
                        "测光仍然可用，建议再完成一次预览流校准。",
                    "$cameraName does not support a RAW stream. The app is using the " +
                        "phone-processed preview for metering. Metering remains available; " +
                        "preview-stream calibration is recommended.",
                ),
            )
            .setMultiChoiceItems(
                arrayOf(localized("不再提示这个摄像头", "Do not warn for this camera again")),
                checked,
            ) { _, _, enabled ->
                checked[0] = enabled
            }
            .setPositiveButton(localized("关闭", "Close")) { _, _ ->
                if (checked[0]) {
                    preferences.edit().putBoolean(cameraWarningKey, true).apply()
                }
            }
            .setOnDismissListener { rawDialogVisible = false }
            .show()
    }

    private fun showCompatibilityModeWarning() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        if (preferences.getBoolean(SUPPRESS_COMPATIBILITY_MODE_WARNING, false) ||
            compatibilityModeDialogVisible || isFinishing
        ) return
        compatibilityModeDialogVisible = true
        AlertDialog.Builder(this)
            .setTitle(localized("兼容模式提示", "Compatibility mode"))
            .setMessage(
                localized(
                    "兼容模式不使用 RAW 流。如果高精度模式或稳定模式可以正常使用，" +
                        "更推荐采用高精度模式或稳定模式。",
                    "Compatibility mode does not use the RAW stream. If High accuracy or " +
                        "Stable mode works normally, either is recommended instead.",
                ),
            )
            .setPositiveButton(localized("确定", "OK"), null)
            .setNeutralButton(localized("不再提示", "Don't show again")) { _, _ ->
                preferences.edit().putBoolean(SUPPRESS_COMPATIBILITY_MODE_WARNING, true).apply()
            }
            .setOnDismissListener { compatibilityModeDialogVisible = false }
            .show()
    }

    private fun showAngleCompatibilityWarning() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        if (preferences.getBoolean(SUPPRESS_ANGLE_COMPATIBILITY_WARNING, false) ||
            angleCompatibilityDialogVisible || isFinishing
        ) return
        angleCompatibilityDialogVisible = true
        AlertDialog.Builder(this)
            .setTitle(localized("按角度测光不可用", "Angle metering unavailable"))
            .setMessage(
                localized(
                    "兼容模式的预览分辨率不足，无法提供有意义的角度测光精度。" +
                        "请先选择高精度模式或稳定模式。",
                    "Compatibility mode does not have enough preview resolution for meaningful " +
                        "angle-metering accuracy. Select High accuracy or Stable mode first.",
                ),
            )
            .setPositiveButton(localized("确定", "OK"), null)
            .setNeutralButton(localized("不再提示", "Don't show again")) { _, _ ->
                preferences.edit()
                    .putBoolean(SUPPRESS_ANGLE_COMPATIBILITY_WARNING, true)
                    .apply()
            }
            .setOnDismissListener { angleCompatibilityDialogVisible = false }
            .show()
    }

    private fun showCalibrationResetDialog() {
        if (calibrationResetDialogVisible || isFinishing) return
        calibrationResetDialogVisible = true
        val english = state.menuLanguage == MenuLanguage.ENGLISH
        val cameraName = state.currentCamera()?.let(state::cameraName)
            ?: if (english) "current camera" else "当前摄像头"
        AlertDialog.Builder(this)
            .setTitle(if (english) "Reset calibration" else "重置测光校准")
            .setMessage(
                if (english) {
                    "Reset the current correction for $cameraName? Its last three records " +
                        "will remain available for restore. Other lenses are unchanged."
                } else {
                    "是否重置“$cameraName”的当前测光修正？最近三次记录会保留，" +
                        "之后仍可回退；其他镜头不受影响。"
                },
            )
            .setNegativeButton(if (english) "Cancel" else "取消", null)
            .setPositiveButton(if (english) "Reset" else "重置") { _, _ ->
                cameraController.resetUserCalibration(
                    calibrationDisplayCameraId ?: cameraController.currentCalibrationCameraId(),
                )
                meterLayout.calibrationView.showReset(shouldShowRawCalibration())
            }
            .setOnDismissListener { calibrationResetDialogVisible = false }
            .show()
    }

    private fun showVignettingResetDialog() {
        if (vignettingResetDialogVisible || isFinishing || vignettingCalibrationPending) return
        vignettingResetDialogVisible = true
        val cameraName = state.currentCamera()?.let(state::cameraName)
            ?: localized("当前镜头", "current lens")
        AlertDialog.Builder(this)
            .setTitle(localized("重置暗角矫正", "Reset vignetting correction"))
            .setMessage(
                localized(
                    "是否重置“$cameraName”的当前暗角矫正？最近三次记录会保留，" +
                        "之后仍可回退；其他镜头不受影响。",
                    "Reset the current vignetting correction for $cameraName? Its last three " +
                        "records will remain available for restore. Other lenses are unchanged.",
                ),
            )
            .setNegativeButton(localized("取消", "Cancel"), null)
            .setPositiveButton(localized("重置", "Reset")) { _, _ ->
                cameraController.resetVignettingCalibration()
                val cameraId = state.cameraInfo.calibrationCameraId.ifBlank { state.selectedCameraId }
                state.refreshVignettingCalibration(cameraId)
                meterLayout.vignettingCalibrationView.showReset()
                meterLayout.refresh()
            }
            .setOnDismissListener { vignettingResetDialogVisible = false }
            .show()
    }

    private fun showVignettingGuideDialog() {
        if (vignettingDialogVisible || isFinishing ||
            calibrationCoordinator.isActive || vignettingCalibrationPending
        ) return
        val camera = state.currentCamera()
        val rawAvailable = if (camera != null && state.cameraInfo.cameraId == camera.cameraId) {
            state.cameraInfo.rawAvailable
        } else {
            camera?.rawAvailable ?: state.cameraInfo.rawAvailable
        }
        if (!rawAvailable) {
            meterLayout.vignettingCalibrationView.showError(
                localized(
                    "当前镜头不支持暗角校准",
                    "This lens does not support vignetting calibration",
                ),
            )
            return
        }
        val preferences = getSharedPreferences("raw_light_meter_state", MODE_PRIVATE)
        if (preferences.getBoolean("suppress_vignetting_guide", false)) return
        vignettingDialogVisible = true
        AlertDialog.Builder(this)
            .setTitle(localized("暗角矫正提示", "Vignetting correction guide"))
            .setMessage(
                localized(
                    "请将当前镜头对准亮度均匀的画面，让它填满整个取景框。" +
                        "避开阴影、反光和过曝，拍摄时保持手机稳定。\n\n" +
                        "进入页面后，请点击“拍摄并矫正”开始。",
                    "Aim the current lens at a uniformly lit scene and fill the full frame. " +
                        "Avoid shadows, glare, and clipping, and hold the phone steady.\n\n" +
                        "Tap “Capture and calibrate” on the page when ready.",
                ),
            )
            .setNeutralButton(localized("不再提示", "Don't show again")) { _, _ ->
                preferences.edit().putBoolean("suppress_vignetting_guide", true).apply()
            }
            .setPositiveButton(localized("确定", "OK"), null)
            .setOnDismissListener { vignettingDialogVisible = false }
            .show()
    }

    private fun startVignettingCalibration() {
        vignettingCalibrationPending = true
        meterLayout.vignettingCalibrationView.setCalibrating()
        cameraController.calibrateVignetting()
    }

    private fun showCameraNoteDialog(cameraId: String) {
        val camera = state.availableCameras.firstOrNull { it.cameraId == cameraId } ?: return
        val editor = EditText(this).apply {
            setText(state.cameraNote(cameraId))
            hint = camera.automaticName(state.menuLanguage)
            isSingleLine = true
            selectAll()
        }
        val padding = (20f * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(
                editor,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(localized("摄像头备注", "Camera note"))
            .setMessage(camera.technicalSummary())
            .setView(container)
            .setNegativeButton(localized("取消", "Cancel"), null)
            .setNeutralButton(localized("清除", "Clear")) { _, _ ->
                state.setCameraNote(cameraId, "")
                meterLayout.refresh()
            }
            .setPositiveButton(localized("保存", "Save")) { _, _ ->
                state.setCameraNote(cameraId, editor.text.toString())
                meterLayout.refresh()
            }
            .show()
    }

    private fun enableParameterGps() {
        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            requestPermissions(LOCATION_PERMISSIONS, LOCATION_PERMISSION_REQUEST)
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager
        val candidates = if (hasFine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER)
        }
        val provider = candidates
            .firstOrNull { name ->
                manager != null && runCatching { manager.isProviderEnabled(name) }.getOrDefault(false)
            }
        if (manager == null || provider == null) {
            meterLayout.setParameterGpsEnabled(false)
            Toast.makeText(
                this,
                localized("系统定位未开启，GPS 记录保持关闭", "System location is off; GPS recording remains disabled"),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        meterLayout.setParameterGpsEnabled(true)
        refreshParameterLocation(manager, provider)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun refreshParameterLocation(manager: LocationManager, provider: String) {
        parameterLocationListener?.let(manager::removeUpdates)
        manager.getLastKnownLocation(provider)?.let(::rememberParameterLocation)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                rememberParameterLocation(location)
                parameterLocationListener?.let(manager::removeUpdates)
                parameterLocationListener = null
            }

            @Deprecated("Legacy LocationListener callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (parameterLocationListener === this) {
                    parameterLocationListener = null
                    meterLayout.setParameterGpsEnabled(false)
                }
            }
        }
        parameterLocationListener = listener
        runCatching { manager.requestSingleUpdate(provider, listener, mainLooper) }
            .onFailure {
                parameterLocationListener = null
                meterLayout.setParameterGpsEnabled(false)
            }
    }

    private fun rememberParameterLocation(location: Location) {
        parameterLocation = RecordedLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
        )
    }

    private fun captureParameterRecord(
        draftId: String,
        snapshot: ParameterMeterSnapshot,
        options: ParameterRecordOptions,
        previewPath: String,
    ) {
        if (options.recordGps && parameterLocation == null) {
            meterLayout.setParameterGpsEnabled(false)
            Toast.makeText(
                this,
                localized("暂时无法取得定位，本条记录不包含 GPS", "No location fix is available; this record will not include GPS"),
                Toast.LENGTH_LONG,
            ).show()
        }
        val draft = ParameterCaptureDraft(
            id = draftId,
            snapshot = snapshot,
            previewTempPath = previewPath,
            cameraId = state.cameraInfo.calibrationCameraId.ifBlank { state.selectedCameraId },
            location = parameterLocation.takeIf { options.recordGps },
            capturedAtEpochMs = System.currentTimeMillis().takeIf { options.recordTime },
        )
        if (!options.recordRaw) {
            meterLayout.completeParameterCapture(draft)
            return
        }
        val rawFile = meterLayout.createParameterRawFile(draftId)
        val previewAspect = if (state.frameLandscape) {
            state.frameFormat.landscapeAspect
        } else {
            1f / state.frameFormat.landscapeAspect
        }
        cameraController.captureRawRecord(rawFile, previewAspect, state.zoom) { result ->
            val completed = result.fold(
                onSuccess = { artifact ->
                    draft.copy(
                        rawTempPath = artifact.filePath,
                        rawGrid = artifact.rawGrid.rebasedForKnownPoints(
                            snapshot.ev100,
                            snapshot.zonePoints,
                        ),
                    )
                },
                onFailure = { error ->
                    rawFile.delete()
                    Toast.makeText(
                        this,
                        error.message ?: localized("RAW 记录失败，仍可保存其他参数", "RAW capture failed; other parameters can still be saved"),
                        Toast.LENGTH_LONG,
                    ).show()
                    draft
                },
            )
            if (activityResumed) {
                meterLayout.completeParameterCapture(completed)
            } else {
                meterLayout.discardParameterCapture(completed)
            }
        }
    }

    private fun updatePreviewTransform(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        @Suppress("DEPRECATION")
        val rotation = meterLayout.textureView.display?.rotation ?: Surface.ROTATION_0
        val zoom = if (meterLayout.isVignettingCalibrationOpen) 1f else state.zoom
        cameraController.updatePreviewTransform(width, height, rotation, zoom)
    }

    private fun clearTransientMessageLater() {
        mainHandler.removeCallbacksAndMessages(TRANSIENT_TOKEN)
        mainHandler.postAtTime(
            {
                state.transientMessage = null
                meterLayout.refresh()
            },
            TRANSIENT_TOKEN,
            android.os.SystemClock.uptimeMillis() + 4_000L,
        )
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val decor = window.decorView
            if (!decor.isAttachedToWindow) {
                decor.post { hideSystemBars() }
                return
            }
            try {
                decor.windowInsetsController?.let { controller ->
                    controller.hide(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
                    )
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (_: RuntimeException) {
                // Some vendor builds expose the controller before PhoneWindow has attached it.
                decor.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 41
        private const val LOCATION_PERMISSION_REQUEST = 42
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        private const val PREFERENCES_NAME = "raw_light_meter_state"
        private const val SUPPRESS_COMPATIBILITY_MODE_WARNING =
            "suppress_compatibility_mode_warning"
        private const val SUPPRESS_ANGLE_COMPATIBILITY_WARNING =
            "suppress_angle_compatibility_warning"
        private const val CAMERA_PERMISSION_REQUEST_MARKER = "camera-permission-requested"
        private val TRANSIENT_TOKEN = Any()
    }
}
