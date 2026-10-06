package com.lightmeter.rawmeter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tool chrome and compact controls; sheets stay inside the same bounded parameter drawer. */
@SuppressLint("ViewConstructor", "UseSwitchCompatOrMaterialCode")
internal class FilmNegativeToolView(context: Context, private val state: MeterState) : FrameLayout(context) {
    interface Listener {
        fun onBackToToolsRequested()
        fun onCloseRequested()
        fun onSettingsChanged(settings: FilmNegativeSettings)
        fun onCameraLockChanged(locked: Boolean)
        fun onSampleBaseRequested()
        fun onFrameRequested(mode: FilmNegativePickMode)
        fun onFrozenSettingsAccepted(frame: FilmNegativeFrozenFrame, settings: FilmNegativeSettings, completion: (Boolean) -> Unit)
        fun onRetryRequested()
        fun onCameraSelected(cameraId: String)
    }

    var listener: Listener? = null
    var settings = FilmNegativeSettings()
        private set
    private val density get() = resources.displayMetrics.density
    private val accent = InstrumentStyle.red
    private val foreground get() = InstrumentStyle.foreground(state.isDarkMode)
    private val surface get() = InstrumentStyle.background(state.isDarkMode)
    private val header = LinearLayout(context)
    private val drawer = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val controls = LinearLayout(context)
    private val dashboard = LinearLayout(context)
    private val adjustments = LinearLayout(context)
    private val calibration = LinearLayout(context)
    private val basic = LinearLayout(context)
    private val sheet = LinearLayout(context)
    private val switching = FilmNegativeSwitchingView(context)
    private val themedTexts = ArrayList<TextView>()
    private val themedButtons = ArrayList<Pair<Button, Boolean>>()
    private val curveChannels = ArrayList<Button>()
    private val themedSwitches = ArrayList<Switch>()
    private val sliders = ArrayList<Slider>()
    private val lock = Switch(context)
    private val invert = Switch(context)
    private val monochrome = Switch(context)
    private lateinit var auto: Button
    private lateinit var restore: Button
    private lateinit var camera: Button
    private lateinit var manual: Button
    private lateinit var calibrationToggle: Button
    private lateinit var basicToggle: Button
    private lateinit var retry: Button
    private lateinit var curveView: FilmNegativeCurveView
    private var curveChannel = 0
    private var manualExpanded = false
    private var calibrationExpanded = true
    private var basicExpanded = false
    private var sheetOpen = false
    private var expanded = true
    private var drawerFraction = 1f
    private var drawerAnimator: ValueAnimator? = null
    private var updating = false
    private var picker: FilmNegativePickerView? = null
    private var pickerSerial = 0
    private var automaticBaseBusy = false
    private var cameraSwitchBusy = false
    private var referenceInvalidated = false
    private var currentCameraId = ""
    private var controlledProcessing: Boolean? = null
    private var statusMessage = ""
    private var referenceSettings: FilmNegativeSettings? = null
    private var drawerBounds = AdaptiveLayout.Drawer(false, 0, 0, 0, 0)
    private var handleDownX = 0f
    private var handleDownY = 0f
    private val switchTimeout = Runnable {
        if (cameraSwitchBusy) showPreviewError(localized("摄像头暂未提供画面，请重试或选择其他摄像头。", "No camera frame yet. Retry or choose another camera."))
    }
    private data class Slider(val bar: SeekBar, val label: TextView, val title: String,
        val minimum: Float, val maximum: Float, val value: (FilmNegativeSettings) -> Float)
    private val drawerHandle = object : TextView(context) {
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { handleDownX = event.rawX; handleDownY = event.rawY }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - handleDownX; val dy = event.rawY - handleDownY
                    when {
                        width > height && this@FilmNegativeToolView.width > this@FilmNegativeToolView.height && abs(dx) > dp(28) ->
                            setExpanded(if (state.isLeftHanded) dx > 0 else dx < 0)
                        abs(dy) > dp(28) -> setExpanded(dy < 0)
                        else -> performClick()
                    }
                }
            }
            return true
        }
    }

    init {
        isClickable = true
        addView(switching, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        header.gravity = Gravity.CENTER_VERTICAL
        header.minimumHeight = dp(52)
        header.addView(button(localized("返回", "Back")) {
            if (!handleBack()) listener?.onBackToToolsRequested()
        }.apply { tag = "back" }, LinearLayout.LayoutParams(dp(68), dp(52)))
        addView(header)
        drawer.orientation = LinearLayout.VERTICAL
        drawerHandle.textSize = 12f; drawerHandle.gravity = Gravity.CENTER
        drawerHandle.typeface = InstrumentStyle.labelTypeface
        drawerHandle.minimumHeight = dp(44)
        drawerHandle.contentDescription = localized("展开或收起参数", "Expand or collapse parameters")
        drawerHandle.setOnClickListener { setExpanded(!expanded) }
        drawer.addView(drawerHandle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        controls.orientation = LinearLayout.VERTICAL
        controls.setPadding(dp(12), dp(4), dp(12), dp(12))
        dashboard.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        dashboard.addView(left, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, .40f).apply { marginEnd = dp(10) })
        dashboard.addView(right, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, .60f))
        addAction(left, button(localized("手动选择胶片区域", "Select film region")) { listener?.onFrameRequested(FilmNegativePickMode.FRAME) })
        camera = button(localized("选择摄像头", "Camera")) { showCameraChooser() }
        addAction(left, camera)
        manual = button(localized("手动调整", "Manual adjustments")) {
            manualExpanded = !manualExpanded; refreshSliders(); requestLayout()
        }
        addAction(left, manual, gap = false)
        val automaticRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        auto = button(localized("一键反相", "Auto invert"), primary = true) { listener?.onSampleBaseRequested() }.apply { textSize = 19f }
        automaticRow.addView(auto, LinearLayout.LayoutParams(0, dp(104), 1f))
        val help = button("?") { showHelp() }.apply {
            textSize = 20f; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            contentDescription = localized("一键反相帮助", "Auto inversion help")
        }
        automaticRow.addView(help, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })
        // An outlined circular question mark, independently themed with the other controls.
        help.tag = "question"
        right.addView(automaticRow)
        styleSwitch(lock, localized("锁定曝光 / 白平衡", "Lock exposure / WB")) {
            listener?.onCameraLockChanged(it)
        }
        right.addView(lock, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        restore = button(localized("恢复预览", "Restore preview")) {
            settings = settings.copy(inverted = false); refreshSliders(); listener?.onSettingsChanged(settings)
        }
        right.addView(restore, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        controls.addView(dashboard)
        adjustments.orientation = LinearLayout.VERTICAL
        calibration.orientation = LinearLayout.VERTICAL
        basic.orientation = LinearLayout.VERTICAL
        calibrationToggle = button("") { calibrationExpanded = !calibrationExpanded; refreshSliders(); requestLayout() }
        adjustments.addView(calibrationToggle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        adjustments.addView(calibration)
        addSlider(localized("Dmin · 黑点密度", "Dmin · black density"), -.5f, 2f, { it.blackDensity }, calibration) {
            settings.copy(blackDensity = it.coerceAtMost(settings.whiteDensity - .05f)) }
        addSlider(localized("Dmax · 白点密度", "Dmax · white density"), .1f, 4f, { it.whiteDensity }, calibration) {
            settings.copy(whiteDensity = it.coerceAtLeast(settings.blackDensity + .05f)) }
        addSlider(localized("RGB 对齐 · 红", "RGB alignment · red"), -.5f, .5f, { it.redAlignment }, calibration) { settings.copy(redAlignment = it) }
        addSlider(localized("RGB 对齐 · 绿", "RGB alignment · green"), -.5f, .5f, { it.greenAlignment }, calibration) { settings.copy(greenAlignment = it) }
        addSlider(localized("RGB 对齐 · 蓝", "RGB alignment · blue"), -.5f, .5f, { it.blueAlignment }, calibration) { settings.copy(blueAlignment = it) }
        styleSwitch(invert, localized("反相", "Invert")) {
            settings = settings.copy(inverted = it); refreshSliders(); listener?.onSettingsChanged(settings)
        }
        calibration.addView(invert, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        calibration.addView(text(localized("RGB 曲线", "RGB curves"), 14f))
        val channels = LinearLayout(context)
        for ((index, label) in listOf("RGB", "R", "G", "B").withIndex()) {
            val channel = button(label) { curveChannel = index; refreshCurve(); applyTheme() }
            curveChannels.add(channel)
            channels.addView(channel,
                LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        calibration.addView(channels)
        curveView = FilmNegativeCurveView(context, { state.isDarkMode }) { curve ->
            settings = when (curveChannel) {
                1 -> settings.copy(redCurve = curve)
                2 -> settings.copy(greenCurve = curve)
                3 -> settings.copy(blueCurve = curve)
                else -> settings.copy(redCurve = curve, greenCurve = curve, blueCurve = curve)
            }
            listener?.onSettingsChanged(settings)
        }
        curveView.contentDescription = localized("RGB 曲线：轻点新增控制点，拖动调整，双击复位单点，长按删除内部点", "RGB curve: tap to add, drag to adjust, double tap to reset a point, long press to remove an interior point")
        calibration.addView(curveView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(164)))
        calibration.addView(text(localized("轻点加点 · 拖动调整 · 双击复位 · 长按删除", "Tap to add · Drag to adjust · Double tap to reset · Hold to remove"), 12f))
        calibration.addView(button(localized("重置当前曲线", "Reset curve")) {
            val identity = FilmNegativeCurve()
            settings = when (curveChannel) {
                1 -> settings.copy(redCurve = identity); 2 -> settings.copy(greenCurve = identity)
                3 -> settings.copy(blueCurve = identity)
                else -> settings.copy(redCurve = identity, greenCurve = identity, blueCurve = identity)
            }
            refreshCurve(); listener?.onSettingsChanged(settings)
        })
        styleSwitch(monochrome, localized("黑白负片", "Black and white")) {
            settings = settings.copy(monochrome = it, colorCorrection = if (it) null else settings.colorCorrection)
            refreshSliders(); listener?.onSettingsChanged(settings)
        }
        calibration.addView(monochrome)
        addSlider(localized("自动校色强度", "Auto colour strength"), 0f, 1f, { it.colorStrength }, calibration) { settings.copy(colorStrength = it) }
        calibration.addView(button(localized("取样片基", "Sample film base")) { listener?.onFrameRequested(FilmNegativePickMode.BASE) })
        basicToggle = button("") { basicExpanded = !basicExpanded; refreshSliders(); requestLayout() }
        adjustments.addView(basicToggle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        adjustments.addView(basic)
        addSlider(localized("曝光（EV）", "Exposure (EV)"), -3f, 3f, { it.exposureEv }, basic) { settings.copy(exposureEv = it) }
        addSlider(localized("饱和度", "Saturation"), 0f, 2f, { it.saturation }, basic) { settings.copy(saturation = it) }
        addSlider(localized("对比度", "Contrast"), .5f, 2f, { it.contrast }, basic) { settings.copy(contrast = it) }
        addSlider(localized("白平衡 · 色温", "White balance · temperature"), -1f, 1f, { it.warmth }, basic) { settings.copy(warmth = it) }
        addSlider(localized("白平衡 · 色调", "White balance · tint"), -1f, 1f, { it.tint }, basic) { settings.copy(tint = it) }
        addSlider(localized("中间调", "Midtones"), .3f, 3f, { it.gamma }, basic) { settings.copy(gamma = it) }
        adjustments.addView(button(localized("重置手动调整", "Reset manual adjustments")) {
            settings = referenceSettings ?: FilmNegativeSettings(inverted = settings.inverted)
            refreshSliders(); listener?.onSettingsChanged(settings)
        })
        controls.addView(adjustments)
        sheet.orientation = LinearLayout.VERTICAL; sheet.visibility = GONE
        controls.addView(sheet)
        retry = button(localized("重启预览", "Retry preview")) { listener?.onRetryRequested() }.apply { visibility = GONE }
        controls.addView(retry)
        scroll.addView(controls)
        drawer.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(drawer)
        refreshSliders(); applyTheme(); setExpanded(true, animate = false)
    }

    fun applyTheme() {
        header.setBackgroundColor(surface)
        drawer.background = GradientDrawable().apply {
            setColor(surface)
            cornerRadii = floatArrayOf(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), 0f, 0f, 0f, 0f)
            setStroke(dp(1), InstrumentStyle.border(this@FilmNegativeToolView.state.isDarkMode))
        }
        drawerHandle.setTextColor(InstrumentStyle.secondary(state.isDarkMode))
        for (label in themedTexts) label.setTextColor(foreground)
        for ((view, primary) in themedButtons) {
            InstrumentStyle.styleButton(view, state.isDarkMode, primary, view.tag == "question")
            if (view.tag == "back") {
                view.background = RippleDrawable(ColorStateList.valueOf(0x18a61b24),
                    android.graphics.drawable.ColorDrawable(surface), null)
            }
        }
        for (view in themedSwitches) {
            view.setTextColor(foreground)
            view.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, if (state.isDarkMode) 0xffbdbdbd.toInt() else 0xff808080.toInt()))
            view.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xffd59498.toInt(), if (state.isDarkMode) 0xff505050.toInt() else 0xffdedede.toInt()))
        }
        for (slider in sliders) {
            slider.bar.thumbTintList = ColorStateList.valueOf(accent)
            slider.bar.progressTintList = ColorStateList.valueOf(accent)
            slider.bar.progressBackgroundTintList = ColorStateList.valueOf(InstrumentStyle.border(state.isDarkMode))
        }
        if (::curveView.isInitialized) curveView.invalidate()
    }

    fun openPage() {
        endCameraSwitch(); discardFrozenFrame(); closeSheet()
        referenceInvalidated = false; controlledProcessing = null; referenceSettings = null
        settings = settings.withoutReference(); manualExpanded = false
        calibrationExpanded = true; basicExpanded = false; retry.visibility = GONE
        currentCameraId = state.selectedCameraId
        refreshSliders(); applyTheme(); setCameraLocked(false); setExpanded(true, animate = false)
        scroll.scrollTo(0, 0); showStatus(defaultStatus())
    }

    fun showStatus(message: String) { statusMessage = message }
    fun showTransientStatus(message: String) { showStatus(message); toast(message) }
    fun beginCameraLockChange(): Int { discardFrozenFrame(); return pickerSerial }
    fun isCameraOperationCurrent(serial: Int) = serial == pickerSerial
    fun setProcessingControlled(controlled: Boolean) { controlledProcessing = controlled }
    fun invalidateSampledBase() {
        referenceInvalidated = true; referenceSettings = null
        discardFrozenFrame(); settings = settings.withoutReference(); setCameraLocked(false); refreshSliders()
        showStatus(localized("相机条件已改变，已恢复预览，请重新反相。", "Camera conditions changed. Invert again."))
    }
    fun showPreviewReady() {
        endCameraSwitch(); retry.visibility = GONE
        if (!automaticBaseBusy && !referenceInvalidated && !settings.inverted) showStatus(defaultStatus())
    }
    fun showPreviewError(message: String) {
        endCameraSwitch(); setCameraLocked(false); retry.visibility = VISIBLE
        showStatus(message); toast(message); setExpanded(true)
    }
    fun setCameraLocked(locked: Boolean) {
        if (!locked) discardFrozenFrame()
        updating = true; lock.isChecked = locked; updating = false
    }
    fun setSampledBase(base: FloatArray) {
        settings = settings.copy(baseRed = base[0], baseGreen = base[1], baseBlue = base[2], densityWindow = null)
        referenceSettings = settings; refreshSliders(); listener?.onSettingsChanged(settings)
    }
    fun beginAutomaticBaseRecognition(): Int {
        discardFrozenFrame(); closeSheet(); automaticBaseBusy = true; refreshEnabled()
        auto.text = localized("正在反相…", "Inverting…")
        showStatus(localized("正在锁定相机并截取一帧…", "Locking camera and capturing a frame…"))
        return pickerSerial
    }
    fun isAutomaticBaseRecognitionCurrent(serial: Int) = automaticBaseBusy && serial == pickerSerial
    fun finishAutomaticBaseRecognition(serial: Int, selection: FilmNegativeSelection?, message: String) {
        if (!isAutomaticBaseRecognitionCurrent(serial)) return
        automaticBaseBusy = false
        if (selection != null) {
            referenceInvalidated = false; settings = selection.settings; referenceSettings = settings
            manualExpanded = false; refreshSliders(); listener?.onSettingsChanged(settings)
            scroll.post { if (picker == null) scroll.scrollTo(0, 0) }
        }
        auto.text = localized("一键反相", "Auto invert"); refreshEnabled(); showStatus(message); toast(message)
    }
    fun beginFrozenCapture(): Int {
        discardFrozenFrame(); closeSheet(); automaticBaseBusy = true; refreshEnabled()
        return pickerSerial
    }
    fun finishFrozenCapture(serial: Int, frame: FilmNegativeFrozenFrame?, mode: FilmNegativePickMode) {
        if (!isAutomaticBaseRecognitionCurrent(serial)) return
        automaticBaseBusy = false; refreshEnabled()
        if (frame == null) toast(localized("未取得可靠原图，请重试。", "No reliable original frame. Retry."))
        else showFrozenFrame(frame, mode)
    }
    fun beginCameraSwitch(snapshot: Bitmap?, snapshotBounds: RectF, cameraId: String) {
        closeSheet(); discardFrozenFrame(); settings = settings.withoutReference(); referenceSettings = null
        updating = true; lock.isChecked = false; updating = false
        currentCameraId = cameraId; cameraSwitchBusy = true; referenceInvalidated = true
        switching.contentDescription = localized("正在切换摄像头", "Switching camera")
        switching.start(snapshot, snapshotBounds); refreshSliders(); refreshEnabled()
        removeCallbacks(switchTimeout); postDelayed(switchTimeout, 12_000)
    }
    private fun endCameraSwitch() {
        removeCallbacks(switchTimeout); cameraSwitchBusy = false; switching.finish(); refreshEnabled()
    }
    fun updateCamera(cameraId: String) { currentCameraId = cameraId; refreshSliders() }
    fun handleBack(): Boolean {
        if (picker != null) { discardFrozenFrame(); return true }
        if (sheetOpen) { closeSheet(); return true }
        return false
    }
    fun closePage() { endCameraSwitch(); discardFrozenFrame(); closeSheet(); drawerAnimator?.cancel() }
    private fun refreshEnabled() = enableControls(controls, !automaticBaseBusy && !cameraSwitchBusy)
    private fun enableControls(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) enableControls(view.getChildAt(index), enabled)
    }
    private fun defaultStatus() = localized("保留照片附近未曝光的均匀片基，一键反相后可手动微调。", "Include uniform unexposed film base near the photograph; fine-tune after auto inversion.")
    private fun showHelp() {
        openSheet(localized("一键反相", "Auto inversion"))
        sheet.addView(text(localized("让一张照片和附近未曝光的均匀片基进入画面，避开齿孔、裸灯箱和明显反光。点击一次会自动锁定曝光与白平衡，截取一帧，选择照片和片基并反相。\n\n手机随后可以移动；校正参数会持续使用。更换摄像头、照明或曝光条件后请重新反相。\n\n识别不足时可重试，或使用手动选区；成功后可打开手动调整。", "Include a photograph and nearby uniform unexposed film base. Avoid sprocket holes, bare backlight and strong reflections. One tap locks exposure and white balance, captures a frame, selects the photograph and base, and inverts.\n\nYou can move the phone afterwards; the correction is reused. Invert again after changing camera, lighting or exposure.\n\nRetry or use manual selection if detection is inconclusive. Fine-tune through Manual adjustments."), 14f).apply { setPadding(0, dp(12), 0, dp(16)) })
        if (statusMessage.isNotBlank() && statusMessage != defaultStatus()) sheet.addView(text(statusMessage, 13f))
        applyTheme(); requestLayout()
    }
    private fun showCameraChooser() {
        openSheet(localized("负片预览摄像头", "Negative preview camera"))
        for (option in state.availableCameras) {
            val selected = option.cameraId == currentCameraId
            val label = option.automaticName(state.menuLanguage) + if (option.focalLengthMm > 0) String.format(Locale.US, " · %.1f mm", option.focalLengthMm) else ""
            sheet.addView(button((if (selected) "✓  " else "") + label) {
                if (selected) closeSheet() else listener?.onCameraSelected(option.cameraId)
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        }
        if (state.availableCameras.isEmpty()) sheet.addView(text(localized("暂无可用摄像头", "No available cameras"), 14f))
        applyTheme(); requestLayout()
    }
    private fun openSheet(title: String) {
        clearSheet(); sheetOpen = true; sheet.visibility = VISIBLE
        dashboard.visibility = GONE; adjustments.visibility = GONE
        sheet.addView(button(localized("返回  ·  ", "Back  ·  ") + title) { closeSheet() })
        setExpanded(true); scroll.scrollTo(0, 0)
    }
    private fun closeSheet() {
        clearSheet()
        sheetOpen = false; sheet.visibility = GONE; dashboard.visibility = VISIBLE
        adjustments.visibility = if (manualExpanded) VISIBLE else GONE
        requestLayout()
    }
    private fun clearSheet() {
        fun forget(view: View) {
            if (view is TextView) themedTexts.remove(view)
            if (view is Button) themedButtons.removeAll { it.first === view }
            if (view is ViewGroup) for (i in 0 until view.childCount) forget(view.getChildAt(i))
        }
        for (i in 0 until sheet.childCount) forget(sheet.getChildAt(i))
        sheet.removeAllViews()
    }
    fun discardFrozenFrame() {
        pickerSerial++; automaticBaseBusy = false; refreshEnabled()
        if (::auto.isInitialized) auto.text = localized("一键反相", "Auto invert")
        picker?.let { removeView(it); it.dispose() }; picker = null
        header.visibility = VISIBLE; drawer.visibility = VISIBLE
    }

    fun showFrozenFrame(frame: FilmNegativeFrozenFrame, mode: FilmNegativePickMode) {
        discardFrozenFrame()
        val serial = pickerSerial
        val snapshotSettings = settings
        var automaticSelection: FilmNegativeSelection? = null
        lateinit var editor: FilmNegativePickerView
        fun analyze(task: () -> Any?, result: (Any?) -> Unit) {
            editor.setBusy(true)
            Thread({
                val value = runCatching(task).getOrNull()
                post {
                    if (serial == pickerSerial && picker === editor) {
                        editor.setBusy(false)
                        result(value)
                    }
                }
            }, "film-frame-analysis").start()
        }
        editor = FilmNegativePickerView(context, state.menuLanguage == MenuLanguage.ENGLISH, frame, mode, darkMode = state.isDarkMode,
            cancel = { discardFrozenFrame() },
            confirm = { region ->
                analyze({ if (mode == FilmNegativePickMode.BASE) FilmNegativeFrameAnalysis.base(frame, region)
                    else automaticSelection?.takeIf { it.region == region }
                        ?: FilmNegativeSelectionAnalysis.manual(frame, region, snapshotSettings) }) { value ->
                    val candidate = when (value) {
                        is FloatArray -> snapshotSettings.copy(baseRed = value[0], baseGreen = value[1], baseBlue = value[2],
                            densityWindow = null, colorCorrection = null, regionTone = false,
                            referenceSource = FilmNegativeReferenceSource.FILM_BORDER)
                        is FilmNegativeSelection -> value.settings
                        else -> null
                    }
                    if (candidate == null) editor.showMessage(if (mode == FilmNegativePickMode.BASE)
                        localized("选区过小、不均匀或过曝，请圈选无图像的均匀片边。", "Selection is small, uneven or clipped. Select uniform clear film.")
                    else localized("有效画面不足或层次太少，请重新框选照片内容。", "Too little valid image or tonal range. Select image content again."))
                    else {
                        editor.setBusy(true)
                        listener?.onFrozenSettingsAccepted(frame, candidate) { accepted ->
                            if (serial != pickerSerial || picker !== editor) return@onFrozenSettingsAccepted
                            if (accepted) {
                                referenceInvalidated = false
                                settings = candidate
                                referenceSettings = candidate
                                manualExpanded = false
                                discardFrozenFrame()
                                refreshSliders()
                                listener?.onSettingsChanged(settings)
                                scroll.scrollTo(0, 0)
                                showStatus(if (mode == FilmNegativePickMode.BASE)
                                    localized("片基已取样；对准照片后可反相或自动校色。", "Base sampled. Frame the photo, then invert or use auto colour.")
                                else if (candidate.referenceSource == FilmNegativeReferenceSource.CONTENT_ESTIMATE)
                                    localized("估计模式：附近未找到可靠片基，已按照片内容估计；可调整 Dmin / Dmax 和校色强度。",
                                        "Estimate mode: no reliable nearby film base. Content-based inversion applied; adjust Dmin / Dmax and colour strength.")
                                else if (candidate.monochrome) localized("已按照片范围和片基转换黑白负片，可调整 Dmin / Dmax。",
                                    "Monochrome photograph and film base applied. Adjust Dmin / Dmax.")
                                else if (candidate.colorCorrection == null) localized("已识别附近片基并去色罩；中性样本不足，可手调色温 / 色调。",
                                    "Nearby film base detected; inversion applied. Few neutral samples; adjust temperature / tint.")
                                else localized("已从选区附近识别片基并去色罩、校色；可调整 Dmin / Dmax 和校色强度。",
                                    "Nearby film base detected; inversion and colour applied. Adjust Dmin / Dmax and colour strength."))
                            } else {
                                discardFrozenFrame()
                                showStatus(localized("相机条件已改变，请重新截取原图。", "Camera conditions changed. Capture a new original frame."))
                            }
                        }
                    }
                }
            }, findBase = {
                analyze({ if (mode == FilmNegativePickMode.BASE) FilmNegativeFrameAnalysis.findBase(frame, snapshotSettings.monochrome)
                    else FilmNegativeSelectionAnalysis.automatic(frame, snapshotSettings) }) { value ->
                    val candidate = value as? FilmBaseCandidate
                    val selection = value as? FilmNegativeSelection
                    automaticSelection = selection
                    if (selection != null) {
                        editor.select(selection.region)
                        editor.showMessage(localized("已标出照片区域；请检查范围并确认。", "Photograph marked. Check the region and confirm."))
                    } else if (candidate == null) editor.showMessage(localized("没有找到可靠候选，请手动框选照片内容。", "No reliable candidate. Select image content manually."))
                    else {
                        editor.select(candidate.region)
                        editor.showMessage(localized("已标出片基候选，请检查是否是均匀无图像片边，再确认。", "Candidate marked. Check that it is uniform clear film, then confirm."))
                    }
                }
            })
        picker = editor
        addView(editor)
        header.visibility = View.INVISIBLE
        drawer.visibility = View.INVISIBLE
        requestLayout()
    }


    private fun setExpanded(value: Boolean, animate: Boolean = true) {
        expanded = value; drawerAnimator?.cancel()
        drawerHandle.text = localized(if (value) "收起参数  ▾" else "展开参数  ▴", if (value) "Hide parameters  ▾" else "Show parameters  ▴")
        scroll.visibility = VISIBLE
        val target = if (value) 1f else 0f
        if (!animate || !isLaidOut) {
            drawerFraction = target; scroll.visibility = if (value) VISIBLE else GONE; requestLayout(); return
        }
        drawerAnimator = ValueAnimator.ofFloat(drawerFraction, target).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { drawerFraction = it.animatedValue as Float; requestLayout() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (!expanded && drawerFraction == 0f) scroll.visibility = GONE }
            })
            start()
        }
    }
    private fun addAction(parent: LinearLayout, view: View, gap: Boolean = true) {
        parent.addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = if (gap) dp(8) else 0 })
    }
    private fun addSlider(title: String, minimum: Float, maximum: Float,
        value: (FilmNegativeSettings) -> Float, parent: LinearLayout, change: (Float) -> FilmNegativeSettings) {
        val label = text(title, 13f).apply { setPadding(0, dp(10), 0, 0) }
        parent.addView(label)
        val bar = SeekBar(context).apply { max = 1000; contentDescription = title }
        val slider = Slider(bar, label, title, minimum, maximum, value); sliders.add(slider)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || updating) return
                settings = change(minimum + (maximum - minimum) * progress / 1000f)
                refreshSliders(); listener?.onSettingsChanged(settings)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        parent.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
    }
    private fun refreshCurve() {
        curveChannels.forEachIndexed { index, button -> button.isSelected = index == curveChannel }
        curveView.curve = settings.curve((curveChannel - 1).coerceAtLeast(0))
        curveView.channelColor = when (curveChannel) { 1 -> 0xffb6333c.toInt(); 2 -> 0xff3d8560.toInt(); 3 -> 0xff3f74ac.toInt(); else -> accent }
    }
    private fun refreshSliders() {
        updating = true
        for (slider in sliders) {
            val value = slider.value(settings)
            slider.bar.progress = ((value - slider.minimum) / (slider.maximum - slider.minimum) * 1000).roundToInt().coerceIn(0, 1000)
            slider.label.text = String.format(Locale.US, "%s  %.2f", slider.title, value)
        }
        invert.isChecked = settings.inverted; monochrome.isChecked = settings.monochrome
        restore.visibility = if (settings.inverted) VISIBLE else GONE
        adjustments.visibility = if (manualExpanded && !sheetOpen) VISIBLE else GONE
        calibration.visibility = if (calibrationExpanded) VISIBLE else GONE
        basic.visibility = if (basicExpanded) VISIBLE else GONE
        manual.text = localized(if (manualExpanded) "收起手动调整" else "手动调整", if (manualExpanded) "Hide adjustments" else "Manual adjustments")
        calibrationToggle.text = localized(if (calibrationExpanded) "校色  ▾" else "校色  ▸",
            if (calibrationExpanded) "Colour  ▾" else "Colour  ▸")
        basicToggle.text = localized(if (basicExpanded) "基础  ▾" else "基础  ▸",
            if (basicExpanded) "Basic  ▾" else "Basic  ▸")
        val selected = state.availableCameras.firstOrNull { it.cameraId == currentCameraId }
        camera.text = if (selected == null) localized("摄像头", "Camera") else String.format(Locale.US,
            localized("摄像头 · %s", "Camera · %s"), selected.automaticName(state.menuLanguage))
        camera.contentDescription = localized("选择负片预览摄像头", "Select negative preview camera")
        if (::curveView.isInitialized) refreshCurve()
        updating = false
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val h = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
        header.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY))
        switching.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        val budget = AdaptiveLayout.drawer(w, h, density, header.measuredHeight, dp(44), h, true, state.isLeftHanded)
        val panelWidth = MeasureSpec.makeMeasureSpec(budget.width, MeasureSpec.EXACTLY)
        controls.measure(panelWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val full = AdaptiveLayout.drawer(w, h, density, header.measuredHeight, dp(44), controls.measuredHeight, true, state.isLeftHanded)
        val collapsed = AdaptiveLayout.drawer(w, h, density, header.measuredHeight, dp(44), controls.measuredHeight, false, state.isLeftHanded)
        val panelHeight = (collapsed.height + (full.height - collapsed.height) * drawerFraction).roundToInt()
        drawerBounds = full.copy(height = panelHeight, top = h - panelHeight)
        drawer.measure(panelWidth, MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY))
        picker?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        switching.layout(0, 0, width, height); header.layout(0, 0, width, header.measuredHeight)
        drawer.layout(drawerBounds.left, drawerBounds.top, drawerBounds.left + drawerBounds.width, drawerBounds.top + drawerBounds.height)
        if (drawerBounds.side) {
            switching.setPreviewBounds(if (state.isLeftHanded) drawerBounds.width else 0, header.measuredHeight,
                if (state.isLeftHanded) width else drawerBounds.left, height)
        } else switching.setPreviewBounds(0, header.measuredHeight, width, drawerBounds.top)
        picker?.layout(0, 0, width, height)
    }
    override fun onDetachedFromWindow() { closePage(); super.onDetachedFromWindow() }
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(this@FilmNegativeToolView.foreground); gravity = Gravity.CENTER_VERTICAL
        themedTexts.add(this)
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false
        minHeight = dp(44); minimumHeight = dp(44); minWidth = 0; minimumWidth = 0
        setPadding(dp(8), dp(4), dp(8), dp(4)); maxLines = 2
        themedButtons.add(this to primary); setOnClickListener { action() }
    }
    private fun styleSwitch(view: Switch, title: String, action: (Boolean) -> Unit) {
        view.text = title; view.textSize = 12f; view.minHeight = dp(44); view.maxLines = 2
        view.setPadding(0, 0, 0, 0); view.switchPadding = dp(4); themedSwitches.add(view)
        view.setOnCheckedChangeListener { _, checked -> if (!updating) action(checked) }
    }
    private fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun localized(chinese: String, english: String) = if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese
}
