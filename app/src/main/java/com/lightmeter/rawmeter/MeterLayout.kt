package com.lightmeter.rawmeter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

class MeterLayout @JvmOverloads constructor(
    context: Context,
    val state: MeterState,
    attributeSet: AttributeSet? = null,
    trackerFactory: ZoneMarkerTrackerFactory = OpenCvZoneMarkerTrackerFactory(),
) : ViewGroup(context, attributeSet) {

    private enum class CameraManagementOrigin { SETTINGS, CALIBRATION, VIGNETTING }

    interface Listener {
        fun onMeasureRequested()
        fun onOrientationToggle()
        fun onPreviewGeometryChanged(width: Int, height: Int)
        fun onControlsChanged(frameChanged: Boolean)
        fun onCalibrationOpened()
        fun onCameraSelected(cameraId: String)
        fun onCameraNoteRequested(cameraId: String)
        fun onCameraVisibilityRequested(cameraId: String, hidden: Boolean)
        fun onCalibrationMeasureRequested(referenceEv100: Double)
        fun onCalibrationResetRequested()
        fun onCalibrationHistoryRestoreRequested(updatedAtEpochMs: Long)
        fun onVignettingCalibrationOpened()
        fun onVignettingCalibrationRequested()
        fun onVignettingCalibrationResetRequested()
        fun onVignettingHistoryRestoreRequested(createdAtEpochMs: Long)
        fun onZoneMeasureRequested(marker: ZoneMarker, target: ZoneMeteringTarget?)
        fun onZoneTrackingActiveChanged(active: Boolean)
    }

    val textureView = TextureView(context).apply {
        isOpaque = true
    }
    val instrumentView = InstrumentView(context, state)
    val settingsView = SettingsView(context, state)
    val informationView = InformationView(context, state)
    val cameraManagementView = CameraManagementView(context, state)
    val calibrationView = CalibrationView(context, state)
    val vignettingCalibrationView = VignettingCalibrationView(context, state)
    val zoneView = ZoneSystemView(context, state)
    private val zoneMarkerTracker: ZoneMarkerTracker = DeferredZoneMarkerTracker {
        trackerFactory.create(textureView, state) { id, x, y, trackingState ->
            zoneView.updateMarkerTracking(id, x, y, trackingState)
        }
    }
    var isSettingsOpen: Boolean = false
        private set
    var isInformationOpen: Boolean = false
        private set
    var isCalibrationOpen: Boolean = false
        private set
    var isVignettingCalibrationOpen: Boolean = false
        private set
    var isCameraManagementOpen: Boolean = false
        private set
    var isZoneMode: Boolean = false
        private set
    private var zoneTransitionFraction = 0f
    private var zoneAnimator: ValueAnimator? = null
    private var zoneTransitionPrepared = false
    private var lastLayoutLandscape: Boolean? = null
    private var zoneOrientationRestartPending = false
    private var cameraManagementOrigin = CameraManagementOrigin.SETTINGS
    var listener: Listener? = null
        set(value) {
            field = value
            instrumentView.listener = object : InstrumentView.Listener {
                override fun onMeasureRequested() {
                    value?.onMeasureRequested()
                }

                override fun onOrientationToggle() {
                    handleOrientationToggle()
                }

                override fun onMoreRequested() {
                    showSettings()
                }

                override fun onZoneEntryDrag(progress: Float, released: Boolean) {
                    if (!released) {
                        prepareZoneTransition()
                        applyZoneTransition(progress)
                    } else {
                        animateZoneTransition(if (progress >= 0.45f) 1f else 0f)
                    }
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
                    if (key == SettingKey.THEME) {
                        calibrationView.applyTheme()
                        vignettingCalibrationView.applyTheme()
                    }
                    val frameChanged = key == SettingKey.HANDEDNESS ||
                        key == SettingKey.ZONE_MARKING_METHOD
                    if (frameChanged) requestLayout()
                    instrumentView.invalidate()
                    settingsView.invalidate()
                    value?.onControlsChanged(frameChanged)
                }

                override fun onActionRequested(key: SettingActionKey) {
                    when (key) {
                        SettingActionKey.MANAGE_CAMERAS -> {
                            showCameraManagement(CameraManagementOrigin.SETTINGS)
                        }
                        SettingActionKey.SHOW_ABOUT -> {
                            showInformation(InformationView.Page.ABOUT)
                        }
                        SettingActionKey.START_METERING_CALIBRATION -> {
                            showCalibration()
                            value?.onCalibrationOpened()
                        }
                        SettingActionKey.START_VIGNETTING_CALIBRATION -> {
                            showVignettingCalibration()
                            value?.onVignettingCalibrationOpened()
                        }
                    }
                }
            }
            informationView.listener = object : InformationView.Listener {
                override fun onCloseRequested() {
                    closeInformation()
                }
            }
            calibrationView.listener = object : CalibrationView.Listener {
                override fun onExitRequested() {
                    closeCalibration()
                }

                override fun onCameraRequested() {
                    showCameraManagement(CameraManagementOrigin.CALIBRATION)
                }

                override fun onResetRequested() {
                    value?.onCalibrationResetRequested()
                }

                override fun onHistoryRestoreRequested(updatedAtEpochMs: Long) {
                    value?.onCalibrationHistoryRestoreRequested(updatedAtEpochMs)
                }

                override fun onMeasureRequested(referenceEv100: Double) {
                    value?.onCalibrationMeasureRequested(referenceEv100)
                }
            }
            vignettingCalibrationView.listener = object : VignettingCalibrationView.Listener {
                override fun onExitRequested() {
                    closeVignettingCalibration()
                }

                override fun onCameraRequested() {
                    showCameraManagement(CameraManagementOrigin.VIGNETTING)
                }

                override fun onResetRequested() {
                    value?.onVignettingCalibrationResetRequested()
                }

                override fun onHistoryRestoreRequested(createdAtEpochMs: Long) {
                    value?.onVignettingHistoryRestoreRequested(createdAtEpochMs)
                }

                override fun onCalibrationRequested() {
                    value?.onVignettingCalibrationRequested()
                }
            }
            cameraManagementView.listener = object : CameraManagementView.Listener {
                override fun onCloseRequested() {
                    closeCameraManagement()
                }

                override fun onCameraSelected(cameraId: String) {
                    value?.onCameraSelected(cameraId)
                    cameraManagementView.invalidate()
                    if (cameraManagementOrigin != CameraManagementOrigin.SETTINGS) {
                        closeCameraManagement()
                    }
                }

                override fun onCameraNoteRequested(cameraId: String) {
                    value?.onCameraNoteRequested(cameraId)
                }

                override fun onCameraVisibilityRequested(cameraId: String, hidden: Boolean) {
                    value?.onCameraVisibilityRequested(cameraId, hidden)
                }
            }
            zoneView.listener = object : ZoneSystemView.Listener {
                override fun onExitDrag(progress: Float, released: Boolean) {
                    if (!released) {
                        applyZoneTransition(1f - progress)
                    } else {
                        animateZoneTransition(if (progress >= 0.55f) 0f else 1f)
                    }
                }

                override fun onMarkRequested(marker: ZoneMarker) {
                    zoneMarkerTracker.addMarker(
                        marker.id,
                        marker.normalizedX,
                        marker.normalizedY,
                    )
                    val target = if (state.zoneMarkingMethod == ZoneMarkingMethod.TOUCH) {
                        zoneMeteringTarget(marker)
                    } else {
                        null
                    }
                    zoneMarkerTracker.onMeteringStateChanged(true)
                    value?.onZoneMeasureRequested(marker, target)
                }

                override fun onMarkerRemoved(markerId: Int) {
                    zoneMarkerTracker.removeMarker(markerId)
                }

                override fun onMarkersCleared(markerIds: List<Int>) {
                    zoneMarkerTracker.clearMarkers()
                }

                override fun onOrientationToggle() {
                    handleOrientationToggle()
                }

                override fun onPreviewMappingChanged() {
                    zoneView.session.markers.forEach { marker ->
                        zoneMarkerTracker.resetMarker(
                            marker.id,
                            marker.normalizedX,
                            marker.normalizedY,
                        )
                    }
                }

                override fun onZoomMappingChanged(zoom: Float) {
                    if (isZoneMode) zoneMarkerTracker.setDisplayZoom(zoom)
                }

                override fun onControlsChanged(frameChanged: Boolean) {
                    if (frameChanged) requestLayout()
                    value?.onControlsChanged(frameChanged)
                }
            }
        }

    init {
        updateBackground()
        addView(textureView)
        addView(instrumentView)
        settingsView.visibility = View.GONE
        addView(settingsView)
        informationView.visibility = View.GONE
        addView(informationView)
        cameraManagementView.visibility = View.GONE
        addView(cameraManagementView)
        calibrationView.visibility = View.GONE
        addView(calibrationView)
        vignettingCalibrationView.visibility = View.GONE
        addView(vignettingCalibrationView)
        zoneView.visibility = View.GONE
        addView(zoneView)
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
        informationView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        cameraManagementView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        calibrationView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        vignettingCalibrationView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        zoneView.measure(
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
        val cameraFrame = if (isVignettingCalibrationOpen) {
            vignettingCalibrationView.calculatePreviewFrame(width, height)
        } else if (isCalibrationOpen) {
            calibrationView.calculatePreviewFrame(width, height)
        } else if (zoneTransitionFraction > 0f || isZoneMode) {
            lerpRect(
                geometry.cameraFrame,
                zoneView.calculatePreviewFrame(width, height),
                zoneTransitionFraction,
            )
        } else {
            geometry.cameraFrame
        }
        val textureFrame = previewTextureFrame(cameraFrame, width, height)
        textureView.measure(
            MeasureSpec.makeMeasureSpec(textureFrame.width().toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(textureFrame.height().toInt(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val layoutLandscape = width > height
        val previousLayoutLandscape = lastLayoutLandscape
        val displayOrientationChanged = previousLayoutLandscape?.let {
            it != layoutLandscape
        } == true
        lastLayoutLandscape = layoutLandscape
        if (isZoneMode && zoneOrientationRestartPending && displayOrientationChanged &&
            previousLayoutLandscape != null
        ) {
            // The camera did not move; only Android's app coordinate axes rotated. Counter that
            // one-time display rotation before laying out and restarting the tracker.
            zoneView.remapMarkersForLayoutOrientation(
                fromLandscape = previousLayoutLandscape,
                toLandscape = layoutLandscape,
            )
        }
        val geometry = LayoutGeometry.calculate(
            width,
            height,
            resources.displayMetrics.density,
            state.frameFormat,
            state.frameLandscape,
            state.isLeftHanded,
        )
        val cameraFrame = if (isVignettingCalibrationOpen) {
            vignettingCalibrationView.calculatePreviewFrame(width, height)
        } else if (isCalibrationOpen) {
            calibrationView.calculatePreviewFrame(width, height)
        } else if (zoneTransitionFraction > 0f || isZoneMode) {
            lerpRect(
                geometry.cameraFrame,
                zoneView.calculatePreviewFrame(width, height),
                zoneTransitionFraction,
            )
        } else {
            geometry.cameraFrame
        }
        val textureFrame = previewTextureFrame(cameraFrame, width, height)
        textureView.layout(
            textureFrame.left.toInt(),
            textureFrame.top.toInt(),
            textureFrame.right.toInt(),
            textureFrame.bottom.toInt(),
        )
        zoneMarkerTracker.setVisibleViewport(
            left = (cameraFrame.left - textureFrame.left) / textureFrame.width(),
            top = (cameraFrame.top - textureFrame.top) / textureFrame.height(),
            right = (cameraFrame.right - textureFrame.left) / textureFrame.width(),
            bottom = (cameraFrame.bottom - textureFrame.top) / textureFrame.height(),
        )
        instrumentView.layout(0, 0, width, height)
        settingsView.layout(0, 0, width, height)
        informationView.layout(0, 0, width, height)
        cameraManagementView.layout(0, 0, width, height)
        calibrationView.layout(0, 0, width, height)
        vignettingCalibrationView.layout(0, 0, width, height)
        zoneView.layout(0, 0, width, height)
        // A format change can resize this child while the ViewGroup's own bounds stay the
        // same, so `changed` is not a reliable signal. Publish geometry after every layout.
        post {
            listener?.onPreviewGeometryChanged(textureView.width, textureView.height)
            if (isZoneMode && displayOrientationChanged && zoneOrientationRestartPending) {
                // CameraController applies TextureView's new preview matrix in another UI task.
                // Queue behind that task so tracking never observes new layout dimensions with
                // the old camera matrix. Marker coordinates remain frozen until then.
                post {
                    if (isZoneMode && zoneOrientationRestartPending) {
                        zoneOrientationRestartPending = false
                        zoneMarkerTracker.start(zoneView.session.markers)
                    }
                }
            }
        }
    }

    /**
     * Both Normal and Zone own an orientation button. Route them through one entry point so the
     * Zone-specific transition guard cannot accidentally be applied only to the hidden Normal UI.
     */
    private fun handleOrientationToggle() {
        // Freeze before Android changes Display.rotation. Otherwise an in-flight old frame can be
        // projected with the new layout and publish a false 90-degree camera movement.
        if (isZoneMode) {
            zoneOrientationRestartPending = true
            zoneMarkerTracker.stop()
        }
        listener?.onOrientationToggle()
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
        informationView.invalidate()
        cameraManagementView.invalidate()
        calibrationView.invalidate()
        vignettingCalibrationView.invalidate()
        zoneView.invalidate()
    }

    fun showSettings() {
        if (isSettingsOpen || isCalibrationOpen || isVignettingCalibrationOpen ||
            isCameraManagementOpen ||
            isZoneMode || zoneTransitionFraction > 0f
        ) return
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
        if (isInformationOpen) return closeInformationFromBack()
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

    private fun showInformation(page: InformationView.Page) {
        if (isInformationOpen || !isSettingsOpen) return
        isInformationOpen = true
        settingsView.visibility = View.GONE
        informationView.animate().cancel()
        informationView.show(page)
        informationView.visibility = View.VISIBLE
        informationView.bringToFront()
        informationView.translationX = width.toFloat().coerceAtLeast(1f)
        informationView.animate()
            .translationX(0f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Android Back first leaves a license document, then leaves the information screen. */
    fun closeInformationFromBack(): Boolean {
        if (!isInformationOpen) return false
        if (informationView.navigateBack()) return true
        return closeInformation()
    }

    private fun closeInformation(): Boolean {
        if (!isInformationOpen) return false
        isInformationOpen = false
        informationView.animate().cancel()
        informationView.animate()
            .translationX(width.toFloat().coerceAtLeast(1f))
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!isInformationOpen) {
                    informationView.visibility = View.GONE
                    informationView.clearContentCache()
                }
            }
            .start()
        settingsView.translationY = 0f
        settingsView.visibility = View.VISIBLE
        settingsView.bringToFront()
        informationView.bringToFront()
        return true
    }

    private fun showCameraManagement(origin: CameraManagementOrigin) {
        if (isCameraManagementOpen || isZoneMode || zoneTransitionFraction > 0f) return
        cameraManagementOrigin = origin
        isCameraManagementOpen = true
        when (origin) {
            CameraManagementOrigin.SETTINGS -> settingsView.visibility = View.GONE
            CameraManagementOrigin.CALIBRATION -> calibrationView.visibility = View.GONE
            CameraManagementOrigin.VIGNETTING -> vignettingCalibrationView.visibility = View.GONE
        }
        cameraManagementView.animate().cancel()
        cameraManagementView.visibility = View.VISIBLE
        cameraManagementView.bringToFront()
        cameraManagementView.translationY = -height.toFloat().coerceAtLeast(1f)
        cameraManagementView.animate()
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun closeCameraManagement(): Boolean {
        if (!isCameraManagementOpen) return false
        isCameraManagementOpen = false
        cameraManagementView.animate().cancel()
        cameraManagementView.animate()
            .translationY(-height.toFloat().coerceAtLeast(1f))
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!isCameraManagementOpen) {
                    cameraManagementView.visibility = View.GONE
                    when (cameraManagementOrigin) {
                        CameraManagementOrigin.SETTINGS -> settingsView.bringToFront()
                        CameraManagementOrigin.CALIBRATION -> calibrationView.bringToFront()
                        CameraManagementOrigin.VIGNETTING ->
                            vignettingCalibrationView.bringToFront()
                    }
                }
            }
            .start()
        when (cameraManagementOrigin) {
            CameraManagementOrigin.SETTINGS -> {
                settingsView.visibility = View.VISIBLE
            }
            CameraManagementOrigin.CALIBRATION -> {
                calibrationView.visibility = View.VISIBLE
                calibrationView.invalidate()
            }
            CameraManagementOrigin.VIGNETTING -> {
                vignettingCalibrationView.visibility = View.VISIBLE
                vignettingCalibrationView.invalidate()
            }
        }
        cameraManagementView.bringToFront()
        requestLayout()
        return true
    }

    fun showCalibration() {
        if (isCalibrationOpen || isVignettingCalibrationOpen || isCameraManagementOpen ||
            isZoneMode || zoneTransitionFraction > 0f
        ) return
        settingsView.animate().cancel()
        settingsView.visibility = View.GONE
        isCalibrationOpen = true
        instrumentView.visibility = View.GONE
        calibrationView.applyTheme()
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
        settingsView.translationY = 0f
        settingsView.visibility = View.VISIBLE
        settingsView.bringToFront()
        isSettingsOpen = true
        updateBackground()
        requestLayout()
        invalidate()
        return true
    }

    fun showVignettingCalibration() {
        if (isVignettingCalibrationOpen || isCalibrationOpen || isCameraManagementOpen ||
            isZoneMode || zoneTransitionFraction > 0f
        ) return
        settingsView.animate().cancel()
        settingsView.visibility = View.GONE
        isVignettingCalibrationOpen = true
        instrumentView.visibility = View.GONE
        vignettingCalibrationView.applyTheme()
        vignettingCalibrationView.visibility = View.VISIBLE
        vignettingCalibrationView.bringToFront()
        updateBackground()
        requestLayout()
    }

    fun closeVignettingCalibration(): Boolean {
        if (!isVignettingCalibrationOpen) return false
        if (vignettingCalibrationView.closeEnlargedPreview()) return true
        if (vignettingCalibrationView.isCalibrating) return true
        isVignettingCalibrationOpen = false
        vignettingCalibrationView.visibility = View.GONE
        instrumentView.visibility = View.VISIBLE
        settingsView.translationY = 0f
        settingsView.visibility = View.VISIBLE
        settingsView.bringToFront()
        isSettingsOpen = true
        updateBackground()
        requestLayout()
        invalidate()
        return true
    }

    fun closeZoneMode(): Boolean {
        if (!isZoneMode && zoneTransitionFraction <= 0f) return false
        animateZoneTransition(0f)
        return true
    }

    fun pauseZoneTracking() {
        zoneMarkerTracker.stop()
    }

    fun resumeZoneTracking() {
        if (isZoneMode) zoneMarkerTracker.start(zoneView.session.markers)
    }

    fun offerZoneTrackingFrame(frame: ZoneTrackingFrame) {
        zoneMarkerTracker.offerFrame(frame)
    }

    /** Called on the camera thread before any Y-plane bytes are copied. */
    fun tryReserveZoneTrackingFrame(): Boolean = zoneMarkerTracker.tryReserveFrame()

    fun cancelZoneTrackingFrameReservation() {
        zoneMarkerTracker.cancelFrameReservation()
    }

    fun completeZoneMeasurement(reading: MeterReading): ZoneMarker? {
        zoneMarkerTracker.onMeteringStateChanged(false)
        return zoneView.completeMeasurement(reading)
    }

    fun failZoneMeasurement(): ZoneMarker? {
        zoneMarkerTracker.onMeteringStateChanged(false)
        return zoneView.failMeasurement()?.also {
            zoneMarkerTracker.removeMarker(it.id)
        }
    }

    private fun prepareZoneTransition() {
        if (zoneTransitionPrepared) return
        zoneTransitionPrepared = true
        zoneView.enter()
        zoneView.visibility = View.VISIBLE
        zoneView.alpha = 0f
        zoneView.bringToFront()
        instrumentView.visibility = View.VISIBLE
    }

    private fun applyZoneTransition(fraction: Float) {
        val value = fraction.coerceIn(0f, 1f)
        if (value > 0f) prepareZoneTransition()
        zoneTransitionFraction = value
        if (value < 1f) instrumentView.visibility = View.VISIBLE
        if (value > 0f) zoneView.visibility = View.VISIBLE
        instrumentView.setZoneTransitionFraction(value)
        instrumentView.alpha = 1f - value
        zoneView.alpha = value
        if (width > height) {
            val direction = ModeTransitionDirection.normalEntrySign(state.isLeftHanded)
            zoneView.translationX = direction * (1f - value) * width * 0.08f
            zoneView.translationY = 0f
        } else {
            zoneView.translationY = (1f - value) * height * 0.08f
            zoneView.translationX = 0f
        }
        requestLayout()
    }

    private fun animateZoneTransition(target: Float) {
        prepareZoneTransition()
        zoneAnimator?.cancel()
        val start = zoneTransitionFraction
        if (kotlin.math.abs(start - target) < 0.001f) {
            finishZoneTransition(target)
            return
        }
        zoneAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = (260L + 160L * kotlin.math.abs(target - start)).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyZoneTransition(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (zoneAnimator === this@apply) {
                        zoneAnimator = null
                        finishZoneTransition(target)
                    }
                }
            })
            start()
        }
    }

    private fun finishZoneTransition(target: Float) {
        applyZoneTransition(target)
        if (target >= 1f) {
            isZoneMode = true
            instrumentView.visibility = View.GONE
            zoneView.visibility = View.VISIBLE
            zoneView.alpha = 1f
            zoneView.translationX = 0f
            zoneView.translationY = 0f
            zoneMarkerTracker.start(zoneView.session.markers)
            listener?.onZoneTrackingActiveChanged(true)
        } else {
            isZoneMode = false
            zoneTransitionPrepared = false
            zoneView.visibility = View.GONE
            instrumentView.visibility = View.VISIBLE
            instrumentView.alpha = 1f
            instrumentView.setZoneTransitionFraction(0f)
            zoneMarkerTracker.stop()
            listener?.onZoneTrackingActiveChanged(false)
        }
        requestLayout()
    }

    private fun lerpRect(start: RectF, end: RectF, fraction: Float): RectF = RectF(
        start.left + (end.left - start.left) * fraction,
        start.top + (end.top - start.top) * fraction,
        start.right + (end.right - start.right) * fraction,
        start.bottom + (end.bottom - start.bottom) * fraction,
    )

    private fun previewTextureFrame(cameraFrame: RectF, width: Int, height: Int): RectF {
        val previewSize = state.cameraInfo.previewSize
        val longAspect = if (previewSize != null && previewSize.width > 0 && previewSize.height > 0) {
            max(previewSize.width, previewSize.height).toFloat() /
                min(previewSize.width, previewSize.height).toFloat()
        } else {
            16f / 9f
        }
        val displayedAspect = if (width > height) longAspect else 1f / longAspect
        val frameAspect = cameraFrame.width() / cameraFrame.height().coerceAtLeast(1f)
        return if (frameAspect > displayedAspect) {
            val textureHeight = cameraFrame.width() / displayedAspect
            RectF(
                cameraFrame.left,
                cameraFrame.centerY() - textureHeight / 2f,
                cameraFrame.right,
                cameraFrame.centerY() + textureHeight / 2f,
            )
        } else {
            val textureWidth = cameraFrame.height() * displayedAspect
            RectF(
                cameraFrame.centerX() - textureWidth / 2f,
                cameraFrame.top,
                cameraFrame.centerX() + textureWidth / 2f,
                cameraFrame.bottom,
            )
        }
    }

    private fun zoneMeteringTarget(marker: ZoneMarker): ZoneMeteringTarget {
        val cameraFrame = zoneView.calculatePreviewFrame(width, height)
        val textureFrame = previewTextureFrame(cameraFrame, width, height)
        val screenX = cameraFrame.left + marker.normalizedX * cameraFrame.width()
        val screenY = cameraFrame.top + marker.normalizedY * cameraFrame.height()
        return ZoneMeteringTarget(
            frameX = marker.normalizedX,
            frameY = marker.normalizedY,
            previewX = ((screenX - textureFrame.left) / textureFrame.width())
                .coerceIn(0f, 1f),
            previewY = ((screenY - textureFrame.top) / textureFrame.height())
                .coerceIn(0f, 1f),
            previewFrameWidthFraction =
                (cameraFrame.width() / textureFrame.width()).coerceIn(0f, 1f),
            previewFrameHeightFraction =
                (cameraFrame.height() / textureFrame.height()).coerceIn(0f, 1f),
        )
    }

    private fun updateBackground() {
        setBackgroundColor(
            if (state.isDarkMode) Color.BLACK else Color.WHITE,
        )
    }

    override fun onDetachedFromWindow() {
        zoneAnimator?.cancel()
        zoneMarkerTracker.release()
        super.onDetachedFromWindow()
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        LayoutParams(context, attrs)

    override fun checkLayoutParams(params: LayoutParams?): Boolean = params != null
}
