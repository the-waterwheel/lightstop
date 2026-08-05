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
    private var calibrationMeasurementPending = false
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
                if (state.measuring || calibrationMeasurementPending || zoneMeasurementPending) {
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

            override fun onZoneMeasureRequested(marker: ZoneMarker) {
                if (zoneMeasurementPending || state.measuring) return
                zoneMeasurementPending = true
                state.measuring = true
                meterLayout.zoneView.invalidate()
                cameraController.measure(
                    state.frameFormat,
                    state.frameLandscape,
                    state.zoom,
                    state.meteringMode,
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
        cameraController.stop()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (meterLayout.closeCameraManagement()) return
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
            state.cameraInfo = state.cameraInfo.copy(status = getString(R.string.camera_permission))
            meterLayout.refresh()
            Toast.makeText(this, R.string.camera_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCameraInfo(info: CameraUiInfo) {
        if (info.cameraId.isNotBlank() && info.cameraId != state.selectedCameraId) {
            state.selectCamera(info.cameraId)
        }
        state.cameraInfo = info
        val switchingMessage = localized("正在切换摄像头", "Switching camera")
        if (state.transientMessage == switchingMessage &&
            info.cameraId == state.selectedCameraId &&
            info.status != "正在切换摄像头"
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

    override fun onMeteringStarted(source: MeteringSource) {
        if (calibrationMeasurementPending) {
            meterLayout.calibrationView.setMeasuring(true)
            return
        }
        if (zoneMeasurementPending) {
            state.measuring = true
            meterLayout.zoneView.invalidate()
            return
        }
        state.measuring = true
        state.transientMessage = if (source == MeteringSource.RAW) {
            "正在读取 5 帧中央 RAW"
        } else {
            "正在读取中央预览亮度 · 兼容模式"
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
                "兼容测光 · 设备不支持 RAW，结果可能不太准确"
            reading.clippedFraction > 0.1 ->
                "中央高光接近饱和 · EV ${"%.1f".format(reading.sceneEv100)}"
            reading.rawLuma < 0.002 ->
                "中央信号较弱 · EV ${"%.1f".format(reading.sceneEv100)}"
            else ->
                "${reading.frameCount} 帧 RAW · EV100 ${"%.1f".format(reading.sceneEv100)}"
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
                    "Reset the user correction for $cameraName? Other cameras are unchanged."
                } else {
                    "是否确定重置“$cameraName”的用户测光修正？其他摄像头不会受到影响。"
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

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun updatePreviewTransform(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
        cameraController.updatePreviewTransform(width, height, rotation, state.zoom)
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
