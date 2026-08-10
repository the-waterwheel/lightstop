package com.lightmeter.rawmeter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity(), CameraControllerCallback {
    private lateinit var state: MeterState
    private lateinit var meterLayout: MeterLayout
    private lateinit var cameraController: CameraController
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rawDialogVisible = false
    private var calibrationResetDialogVisible = false
    private var vignettingDialogVisible = false
    private var vignettingResetDialogVisible = false
    private var calibrationMeasurementPending = false
    private var vignettingCalibrationPending = false
    private var calibrationReferenceEv100 = Double.NaN
    private var zoneMeasurementPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        state = MeterState(this)
        requestedOrientation = if (state.landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        meterLayout = MeterLayout(this, state)
        cameraController = CameraController(this, this)
        val selectedCamera = state.updateCameraCatalog(cameraController.availableCameras())
        selectedCamera?.let(cameraController::selectCamera)
        cameraController.attach(meterLayout.textureView)
        meterLayout.listener = object : MeterLayout.Listener {
            override fun onMeasureRequested() {
                cameraController.measure(
                    state.frameFormat,
                    state.frameLandscape,
                    state.zoom,
                    state.meteringMode,
                )
            }

            override fun onOrientationToggle() {
                state.landscape = !state.landscape
                requestedOrientation = if (state.landscape) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            override fun onPreviewGeometryChanged(width: Int, height: Int) {
                updatePreviewTransform(width, height)
            }

            override fun onControlsChanged(frameChanged: Boolean) {
                meterLayout.refresh(frameChanged)
                if (!frameChanged) {
                    updatePreviewTransform(
                        meterLayout.textureView.width,
                        meterLayout.textureView.height,
                    )
                }
            }

            override fun onCalibrationOpened() {
                meterLayout.calibrationView.setCurrentCorrection(
                    cameraController.currentUserCalibrationEv(),
                )
            }

            override fun onCameraSelected(cameraId: String) {
                if (state.measuring || calibrationMeasurementPending ||
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
                calibrationMeasurementPending = true
                calibrationReferenceEv100 = referenceEv100
                meterLayout.calibrationView.setMeasuring(true)
                cameraController.measure(
                    state.frameFormat,
                    state.frameLandscape,
                    state.zoom,
                    state.meteringMode,
                )
            }

            override fun onCalibrationResetRequested() {
                showCalibrationResetDialog()
            }

            override fun onCalibrationHistoryRestoreRequested(updatedAtEpochMs: Long) {
                val restored = cameraController.restoreUserCalibration(updatedAtEpochMs) ?: return
                meterLayout.calibrationView.showHistoryRestored(restored)
                meterLayout.refresh()
            }

            override fun onVignettingCalibrationOpened() {
                showVignettingGuideDialog()
            }

            override fun onVignettingCalibrationRequested() {
                if (!vignettingCalibrationPending && !calibrationMeasurementPending) {
                    startVignettingCalibration()
                }
            }

            override fun onVignettingCalibrationResetRequested() {
                showVignettingResetDialog()
            }

            override fun onVignettingHistoryRestoreRequested(createdAtEpochMs: Long) {
                val restored = cameraController.restoreVignettingCalibration(createdAtEpochMs)
                    ?: return
                val cameraId = state.cameraInfo.cameraId.ifBlank { state.selectedCameraId }
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
                cameraController.measure(
                    state.frameFormat,
                    state.frameLandscape,
                    state.zoom,
                    state.meteringMode,
                    target,
                )
            }

            override fun onZoneTrackingActiveChanged(active: Boolean) {
                cameraController.setTrackingFramesEnabled(active)
            }
        }
        setContentView(meterLayout)
        window.decorView.post { hideSystemBars() }
        ensureCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        meterLayout.resumeZoneTracking()
        cameraController.setTrackingFramesEnabled(meterLayout.isZoneMode)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraController.start()
        }
    }

    override fun onPause() {
        cameraController.setTrackingFramesEnabled(false)
        meterLayout.pauseZoneTracking()
        if (zoneMeasurementPending) {
            zoneMeasurementPending = false
            state.measuring = false
            meterLayout.failZoneMeasurement()
        }
        if (vignettingCalibrationPending) {
            vignettingCalibrationPending = false
            meterLayout.vignettingCalibrationView.showError(
                localized("暗角校准已中止", "Vignetting calibration was interrupted"),
            )
        }
        cameraController.stop()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (meterLayout.closeCameraManagement()) return
        if (meterLayout.closeVignettingCalibration()) return
        if (meterLayout.closeCalibration()) return
        if (meterLayout.closeSettings()) return
        if (meterLayout.closeZoneMode()) return
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        state.landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        meterLayout.requestLayout()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            cameraController.start()
        } else {
            val message = localized(
                "需要相机权限才能进行 RAW 测光。",
                "Camera permission is required for RAW metering.",
            )
            state.cameraInfo = state.cameraInfo.copy(status = message)
            meterLayout.refresh()
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
            meterLayout.calibrationView.setCurrentCorrection(
                cameraController.currentUserCalibrationEv(),
            )
        }
        meterLayout.refresh()
    }

    override fun onRawUnavailable() {
        showRawUnavailableDialog(force = false)
    }

    override fun onZoneTrackingFrame(frame: ZoneTrackingFrame) {
        meterLayout.offerZoneTrackingFrame(frame)
    }

    override fun onMeteringStarted(source: MeteringSource, frameCount: Int) {
        if (calibrationMeasurementPending) {
            meterLayout.calibrationView.setMeasuring(true, frameCount)
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
                "正在读取 $frameCount 帧中央 RAW",
                "Reading $frameCount center RAW frames",
            )
        } else {
            localized(
                "正在读取 $frameCount 帧中央预览亮度 · 兼容模式",
                "Reading $frameCount center preview frames · Compatible mode",
            )
        }
        meterLayout.refresh()
    }

    override fun onMeterReading(reading: MeterReading) {
        if (calibrationMeasurementPending) {
            calibrationMeasurementPending = false
            val reference = calibrationReferenceEv100
            calibrationReferenceEv100 = Double.NaN
            val correction = cameraController.updateUserCalibration(
                referenceEv100 = reference,
                measuredEv100 = reading.sceneEv100,
            )
            meterLayout.calibrationView.showResult(
                referenceEv100 = reference,
                measuredEv100 = reading.sceneEv100,
                correctionEv = correction,
            )
            return
        }
        if (zoneMeasurementPending) {
            zoneMeasurementPending = false
            state.measuring = false
            state.lastReading = reading
            meterLayout.completeZoneMeasurement(reading)
            meterLayout.refresh()
            return
        }
        state.measuring = false
        state.lastReading = reading
        state.sceneEv100 = reading.sceneEv100
        state.transientMessage = when {
            reading.source == MeteringSource.ISP_PREVIEW ->
                localized(
                    "兼容测光 · RAW 不可用，结果可能不太准确",
                    "Compatible metering · RAW unavailable; result may be less accurate",
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
                    "${reading.frameCount} 帧 RAW · EV100 ${"%.1f".format(reading.sceneEv100)}",
                    "${reading.frameCount} RAW frames · EV100 ${"%.1f".format(reading.sceneEv100)}",
                )
        }
        meterLayout.refresh()
        clearTransientMessageLater()
    }

    override fun onMeteringError(message: String) {
        if (calibrationMeasurementPending) {
            calibrationMeasurementPending = false
            calibrationReferenceEv100 = Double.NaN
            meterLayout.calibrationView.showError(message)
            return
        }
        if (zoneMeasurementPending) {
            zoneMeasurementPending = false
            state.measuring = false
            meterLayout.failZoneMeasurement()
            state.transientMessage = message
            meterLayout.refresh()
            clearTransientMessageLater()
            return
        }
        state.measuring = false
        state.transientMessage = message
        meterLayout.refresh()
        clearTransientMessageLater()
    }

    override fun onVignettingCalibrationStarted() {
        if (!vignettingCalibrationPending) return
        meterLayout.vignettingCalibrationView.setCalibrating()
    }

    override fun onVignettingCalibrationCompleted(info: VignettingCalibrationInfo) {
        vignettingCalibrationPending = false
        val cameraId = state.cameraInfo.cameraId.ifBlank { state.selectedCameraId }
        state.refreshVignettingCalibration(cameraId)
        meterLayout.vignettingCalibrationView.showResult(info)
        meterLayout.refresh()
    }

    override fun onVignettingCalibrationError(message: String) {
        vignettingCalibrationPending = false
        meterLayout.vignettingCalibrationView.showError(message)
        meterLayout.refresh()
    }

    private fun ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
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
            .setTitle(localized("RAW 不可用", "RAW unavailable"))
            .setMessage(
                localized(
                    "$cameraName 不支持 RAW_SENSOR，将使用经过 ISP 处理的预览画面测光。" +
                        "测光仍然可用，但结果可能不准确。",
                    "$cameraName does not support RAW_SENSOR. Metering will use the " +
                        "ISP-processed preview and may be inaccurate.",
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
                cameraController.resetUserCalibration()
                meterLayout.calibrationView.showReset()
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
                val cameraId = state.cameraInfo.cameraId.ifBlank { state.selectedCameraId }
                state.refreshVignettingCalibration(cameraId)
                meterLayout.vignettingCalibrationView.showReset()
                meterLayout.refresh()
            }
            .setOnDismissListener { vignettingResetDialogVisible = false }
            .show()
    }

    private fun showVignettingGuideDialog() {
        if (vignettingDialogVisible || isFinishing ||
            calibrationMeasurementPending || vignettingCalibrationPending
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
                    "无法输出 RAW，无需校准",
                    "RAW output is unavailable; no calibration is needed",
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
                        "此提示不会开始拍摄；进入页面后，请点击“拍摄并矫正”开始。",
                    "Aim the current lens at a uniformly lit scene and fill the full frame. " +
                        "Avoid shadows, glare, and clipping, and hold the phone steady.\n\n" +
                        "This guide does not start a capture. Tap “Capture and calibrate” " +
                        "on the page when ready.",
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

    private fun updatePreviewTransform(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
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
        private val TRANSIENT_TOKEN = Any()
    }
}
