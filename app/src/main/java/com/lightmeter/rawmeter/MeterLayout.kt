package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

class MeterLayout @JvmOverloads constructor(
    context: Context,
    val state: MeterState,
    attributeSet: AttributeSet? = null,
) : ViewGroup(context, attributeSet) {

    interface Listener {
        fun onMeasureRequested()
        fun onOrientationToggle()
        fun onPreviewGeometryChanged(width: Int, height: Int)
        fun onControlsChanged(frameChanged: Boolean)
        fun onCalibrationOpened()
        fun onCalibrationMeasureRequested(referenceEv100: Double)
        fun onCalibrationResetRequested()
    }

    val textureView = TextureView(context).apply {
        isOpaque = true
    }
    val instrumentView = InstrumentView(context, state)
    val settingsView = SettingsView(context, state)
    val calibrationView = CalibrationView(context, state)
    var isSettingsOpen: Boolean = false
        private set
    var isCalibrationOpen: Boolean = false
        private set
    var listener: Listener? = null
        set(value) {
            field = value
            instrumentView.listener = object : InstrumentView.Listener {
                override fun onMeasureRequested() {
                    value?.onMeasureRequested()
                }

                override fun onOrientationToggle() {
                    value?.onOrientationToggle()
                }

                override fun onMoreRequested() {
                    showSettings()
                }

                override fun onControlsChanged(frameChanged: Boolean) {
                    if (frameChanged) requestLayout()
                    value?.onControlsChanged(frameChanged)
                }
            }
            settingsView.listener = object : SettingsView.Listener {
                override fun onCloseRequested() {
                    closeSettings()
                }

                override fun onSettingChanged(key: SettingKey) {
                    updateBackground()
                    val frameChanged = key == SettingKey.HANDEDNESS
                    if (frameChanged) requestLayout()
                    instrumentView.invalidate()
                    settingsView.invalidate()
                    value?.onControlsChanged(frameChanged)
                }

                override fun onActionRequested(key: SettingActionKey) {
                    when (key) {
                        SettingActionKey.START_CALIBRATION -> {
                            showCalibration()
                            value?.onCalibrationOpened()
                        }
                    }
                }
            }
            calibrationView.listener = object : CalibrationView.Listener {
                override fun onExitRequested() {
                    closeCalibration()
                }

                override fun onResetRequested() {
                    value?.onCalibrationResetRequested()
                }

                override fun onMeasureRequested(referenceEv100: Double) {
                    value?.onCalibrationMeasureRequested(referenceEv100)
                }
            }
        }

    init {
        updateBackground()
        addView(textureView)
        addView(instrumentView)
        settingsView.visibility = View.GONE
        addView(settingsView)
        calibrationView.visibility = View.GONE
        addView(calibrationView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        instrumentView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        settingsView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        calibrationView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        val geometry = LayoutGeometry.calculate(
            width,
            height,
            resources.displayMetrics.density,
            state.frameFormat,
            state.frameLandscape,
            state.isLeftHanded,
        )
        val cameraFrame = if (isCalibrationOpen) {
            calibrationView.calculatePreviewFrame(width, height)
        } else {
            geometry.cameraFrame
        }
        textureView.measure(
            MeasureSpec.makeMeasureSpec(cameraFrame.width().toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cameraFrame.height().toInt(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val geometry = LayoutGeometry.calculate(
            width,
            height,
            resources.displayMetrics.density,
            state.frameFormat,
            state.frameLandscape,
            state.isLeftHanded,
        )
        val cameraFrame = if (isCalibrationOpen) {
            calibrationView.calculatePreviewFrame(width, height)
        } else {
            geometry.cameraFrame
        }
        textureView.layout(
            cameraFrame.left.toInt(),
            cameraFrame.top.toInt(),
            cameraFrame.right.toInt(),
            cameraFrame.bottom.toInt(),
        )
        instrumentView.layout(0, 0, width, height)
        settingsView.layout(0, 0, width, height)
        calibrationView.layout(0, 0, width, height)
        // A format change can resize this child while the ViewGroup's own bounds stay the
        // same, so `changed` is not a reliable signal. Publish geometry after every layout.
        post {
            listener?.onPreviewGeometryChanged(textureView.width, textureView.height)
        }
    }

    fun setSurfaceTextureListener(listener: TextureView.SurfaceTextureListener) {
        textureView.surfaceTextureListener = listener
    }

    fun currentSurfaceTexture(): SurfaceTexture? = textureView.surfaceTexture

    fun refresh(frameChanged: Boolean = false) {
        if (frameChanged) requestLayout()
        updateBackground()
        instrumentView.invalidate()
        settingsView.invalidate()
        calibrationView.invalidate()
    }

    fun showSettings() {
        if (isSettingsOpen || isCalibrationOpen) return
        isSettingsOpen = true
        settingsView.animate().cancel()
        settingsView.visibility = View.VISIBLE
        settingsView.bringToFront()
        val start = -height.toFloat().coerceAtLeast(1f)
        settingsView.translationY = start
        settingsView.animate()
            .translationY(0f)
            .setDuration(320L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun closeSettings(): Boolean {
        if (!isSettingsOpen) return false
        isSettingsOpen = false
        settingsView.animate().cancel()
        settingsView.animate()
            .translationY(-height.toFloat().coerceAtLeast(1f))
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!isSettingsOpen) settingsView.visibility = View.GONE
            }
            .start()
        return true
    }

    fun showCalibration() {
        if (isCalibrationOpen) return
        settingsView.animate().cancel()
        isSettingsOpen = false
        settingsView.visibility = View.GONE
        isCalibrationOpen = true
        instrumentView.visibility = View.GONE
        calibrationView.visibility = View.VISIBLE
        calibrationView.bringToFront()
        updateBackground()
        requestLayout()
    }

    fun closeCalibration(): Boolean {
        if (!isCalibrationOpen) return false
        if (calibrationView.isMeasuring) return true
        isCalibrationOpen = false
        calibrationView.visibility = View.GONE
        instrumentView.visibility = View.VISIBLE
        instrumentView.bringToFront()
        updateBackground()
        requestLayout()
        invalidate()
        return true
    }

    private fun updateBackground() {
        setBackgroundColor(
            if (isCalibrationOpen || !state.isDarkMode) Color.WHITE else Color.BLACK,
        )
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        LayoutParams(context, attrs)

    override fun checkLayoutParams(params: LayoutParams?): Boolean = params != null
}
