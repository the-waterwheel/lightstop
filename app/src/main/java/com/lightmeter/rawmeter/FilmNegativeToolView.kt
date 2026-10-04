package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Full-screen tool chrome over the existing viewfinder; only the parameter drawer is opaque. */
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
    }

    var listener: Listener? = null
    var settings = FilmNegativeSettings()
        private set
    private val density get() = resources.displayMetrics.density
    private val header = LinearLayout(context)
    private val drawer = LinearLayout(context)
    private val drawerHandle = object : TextView(context) {
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleDownY = event.rawY
                    handleDownX = event.rawX
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - handleDownX
                    val dy = event.rawY - handleDownY
                    when {
                        this@FilmNegativeToolView.width > this@FilmNegativeToolView.height && abs(dx) > dp(28) ->
                            setExpanded(if (state.isLeftHanded) dx > 0 else dx < 0)
                        abs(dy) > dp(28) -> setExpanded(dy < 0)
                        else -> performClick()
                    }
                }
            }
            return true
        }
    }
    private val scroll = ScrollView(context)
    private val controls = LinearLayout(context)
    private val adjustments = LinearLayout(context)
    private val advanced = LinearLayout(context)
    private val invert = Button(context)
    private val status = TextView(context)
    private val lock = Switch(context)
    private val monochrome = Switch(context)
    // The renderer draws the sample boundary, sharing the exact readback coordinates.
    private val guide = View(context)
    private var controlledProcessing: Boolean? = null
    private var statusMessage = ""
    private var expanded = true
    private var updating = false
    private var picker: FilmNegativePickerView? = null
    private var pickerSerial = 0
    private var automaticBaseBusy = false
    private var referenceInvalidated = false
    private var handleDownY = 0f
    private var handleDownX = 0f
    private var drawerBounds = AdaptiveLayout.Drawer(false, 0, 0, 0, 0)
    private data class Slider(val bar: SeekBar, val label: TextView, val title: String,
        val minimum: Float, val maximum: Float, val value: (FilmNegativeSettings) -> Float)
    private val sliders = ArrayList<Slider>()

    init {
        isClickable = true
        addView(guide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        header.gravity = Gravity.CENTER_VERTICAL
        header.minimumHeight = dp(52)
        header.setBackgroundColor(Color.argb(218, 18, 18, 18))
        header.addView(button(localized("返回", "Back")) { listener?.onBackToToolsRequested() },
            LinearLayout.LayoutParams(dp(64), LayoutParams.WRAP_CONTENT))
        header.addView(text(localized("负片预览 · 测试", "Negative preview · Beta"), 16f).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("×") { listener?.onCloseRequested() },
            LinearLayout.LayoutParams(dp(52), LayoutParams.WRAP_CONTENT))
        addView(header)
        drawer.orientation = LinearLayout.VERTICAL
        drawer.setBackgroundColor(Color.argb(242, 23, 23, 23))
        drawerHandle.setTextColor(Color.WHITE)
        drawerHandle.textSize = 15f
        drawerHandle.gravity = Gravity.CENTER
        drawerHandle.contentDescription = localized("展开或收起参数面板", "Expand or collapse adjustments")
        drawerHandle.isClickable = true
        drawerHandle.setOnClickListener { setExpanded(!expanded) }
        drawerHandle.minimumHeight = dp(48)
        drawer.addView(drawerHandle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        controls.orientation = LinearLayout.VERTICAL
        controls.setPadding(dp(14), 0, dp(14), dp(12))
        adjustments.orientation = LinearLayout.VERTICAL
        advanced.orientation = LinearLayout.VERTICAL
        advanced.visibility = View.GONE
        invert.textSize = 14f
        invert.isAllCaps = false
        invert.minHeight = dp(48)
        invert.setTextColor(Color.WHITE)
        invert.setBackgroundColor(Color.rgb(135, 35, 39))
        invert.setOnClickListener {
            settings = settings.copy(inverted = !settings.inverted)
            refreshSliders()
            listener?.onSettingsChanged(settings)
        }
        adjustments.addView(invert, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        status.setTextColor(Color.rgb(216, 216, 210))
        status.textSize = 12f
        status.setPadding(0, dp(6), 0, dp(10))
        controls.addView(status)
        lock.text = localized("锁定相机曝光 / 白平衡", "Lock camera exposure / white balance")
        styleSwitch(lock)
        lock.setOnCheckedChangeListener { _, checked ->
            if (!updating) listener?.onCameraLockChanged(checked)
        }
        advanced.addView(lock, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        controls.addView(button(localized("一键识别并校正", "Auto detect and correct")) {
            listener?.onSampleBaseRequested()
        }.apply { setBackgroundColor(Color.rgb(135, 35, 39)) }, 0,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        controls.addView(button(localized("手动选区（备用）", "Manual selection (fallback)")) {
            listener?.onFrameRequested(FilmNegativePickMode.FRAME)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        controls.addView(adjustments)
        addSlider(localized("亮度（EV，向右变亮）", "Brightness (EV, right is brighter)"), -3f, 3f, { it.exposureEv }) { value -> settings.copy(exposureEv = value) }
        addSlider(localized("中间调（向右提亮）", "Midtones (right is brighter)"), 0.3f, 3f, { it.gamma }) { value -> settings.copy(gamma = value) }
        addSlider(localized("饱和度", "Saturation"), 0f, 2f, { it.saturation }) { value -> settings.copy(saturation = value) }
        addSlider(localized("色温（冷 ← → 暖）", "Temperature (cool ← → warm)"), -1f, 1f, { it.warmth }) { settings.copy(warmth = it) }
        addSlider(localized("色调（绿 ← → 洋红）", "Tint (green ← → magenta)"), -1f, 1f, { it.tint }) { settings.copy(tint = it) }
        addSlider(localized("自动校色强度（0 为关闭）", "Auto colour strength (0 is off)"), 0f, 1f, { it.colorStrength }) { settings.copy(colorStrength = it) }
        addSlider(localized("Dmin · 黑点密度", "Dmin · black density"), -0.5f, 2f, { it.blackDensity }) {
            settings.copy(blackDensity = it.coerceAtMost(settings.whiteDensity - .05f)) }
        addSlider(localized("Dmax · 白点密度", "Dmax · white density"), .1f, 4f, { it.whiteDensity }) {
            settings.copy(whiteDensity = it.coerceAtLeast(settings.blackDensity + .05f)) }
        monochrome.text = localized("黑白负片", "Black and white negative")
        styleSwitch(monochrome)
        monochrome.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                // The one-tap action detects the mode itself. This is a post-result override,
                // so keep the measured base, tone and user's other adjustments intact.
                settings = settings.copy(monochrome = checked,
                    colorCorrection = if (checked) null else settings.colorCorrection)
                refreshSliders()
                listener?.onSettingsChanged(settings)
            }
        }
        adjustments.addView(monochrome,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        adjustments.addView(button(localized("高级调整：片基 / 密度", "Advanced: film base / density")) {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            requestLayout()
        })
        adjustments.addView(advanced)
        advanced.addView(button(localized("直接取样片基（备用）", "Sample film base (fallback)")) {
            listener?.onFrameRequested(FilmNegativePickMode.BASE)
        })
        addSlider(localized("片基红通道", "Film base red"), 0.02f, 1f, { it.baseRed }, advanced) { settings.copy(baseRed = it, densityWindow = null, colorCorrection = null) }
        addSlider(localized("片基绿通道", "Film base green"), 0.02f, 1f, { it.baseGreen }, advanced) { settings.copy(baseGreen = it, densityWindow = null, colorCorrection = null) }
        addSlider(localized("片基蓝通道", "Film base blue"), 0.02f, 1f, { it.baseBlue }, advanced) { settings.copy(baseBlue = it, densityWindow = null, colorCorrection = null) }
        addSlider(localized("胶片斜率（调整退出自动校色）", "Film slope (returns to manual model)"), 0.3f, 1.5f, { it.filmGamma }, advanced) {
            settings.copy(filmGamma = it, densityWindow = null, regionTone = false, colorCorrection = null) }
        val actions = LinearLayout(context)
        actions.addView(button(localized("重置参数", "Reset")) {
            settings = settings.withoutReference()
            refreshSliders()
            listener?.onSettingsChanged(settings)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button(localized("重启预览", "Retry preview")) { listener?.onRetryRequested() },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(actions)
        scroll.isFillViewport = false
        scroll.addView(controls)
        drawer.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(drawer)
        refreshSliders()
        setExpanded(true)
        showStatus(defaultStatus())
    }

    fun openPage() {
        discardFrozenFrame()
        referenceInvalidated = false
        controlledProcessing = null
        settings = settings.withoutReference()
        advanced.visibility = View.GONE
        scroll.scrollTo(0, 0)
        refreshSliders()
        setCameraLocked(false)
        setExpanded(true)
        showStatus(defaultStatus())
    }

    fun showStatus(message: String) {
        statusMessage = message
        val processing = when (controlledProcessing) {
            true -> localized("固定 sRGB 输入曲线", "Fixed sRGB input curve")
            false -> localized("相机处理近似模式", "Approximate camera processing")
            null -> localized("正在确认相机处理", "Checking camera processing")
        }
        val reference = when (settings.referenceSource) {
            FilmNegativeReferenceSource.DEFAULT -> localized("片基：默认参考", "Film base: default reference")
            FilmNegativeReferenceSource.FILM_BORDER -> localized("片基：片边参考", "Film base: border reference")
            FilmNegativeReferenceSource.CONTENT_ESTIMATE -> localized("片基：内容估计", "Film base: content estimate")
        }
        status.text = "$message\n$reference · $processing"
    }

    fun setProcessingControlled(controlled: Boolean) {
        controlledProcessing = controlled
        showStatus(statusMessage)
    }

    fun invalidateSampledBase() {
        referenceInvalidated = true
        discardFrozenFrame()
        settings = settings.withoutReference()
        setCameraLocked(false)
        refreshSliders()
        showStatus(localized("相机条件已改变，已恢复原图；请重新自动选择或框选胶片区域。", "Camera conditions changed. Original view restored; select the photograph again."))
    }

    fun showPreviewReady() { if (!automaticBaseBusy && !referenceInvalidated && !settings.inverted) showStatus(defaultStatus()) }

    fun showPreviewError(message: String) {
        setCameraLocked(false)
        showStatus(message)
        setExpanded(true)
    }

    fun setCameraLocked(locked: Boolean) {
        if (!locked) discardFrozenFrame()
        updating = true
        lock.isChecked = locked
        updating = false
    }

    fun setSampledBase(base: FloatArray) {
        settings = settings.copy(baseRed = base[0], baseGreen = base[1], baseBlue = base[2], densityWindow = null)
        refreshSliders()
        listener?.onSettingsChanged(settings)
        showStatus(localized("片基已采样。对准画面后点击「反相预览」", "Base sampled. Frame the image, then tap Invert preview"))
    }

    fun beginAutomaticBaseRecognition(): Int {
        discardFrozenFrame()
        automaticBaseBusy = true
        enableControls(controls, false)
        showStatus(localized("正在锁定相机并截取一帧，随后自动选择照片、片基并校正…", "Locking camera and capturing one frame, then selecting the photograph, film base and correction…"))
        return pickerSerial
    }

    fun isAutomaticBaseRecognitionCurrent(serial: Int) = automaticBaseBusy && serial == pickerSerial

    fun finishAutomaticBaseRecognition(serial: Int, selection: FilmNegativeSelection?, message: String) {
        if (!isAutomaticBaseRecognitionCurrent(serial)) return
        automaticBaseBusy = false
        enableControls(controls, true)
        if (selection != null) {
            referenceInvalidated = false
            settings = selection.settings
            refreshSliders()
            listener?.onSettingsChanged(settings)
            scroll.post { if (picker == null) scroll.scrollTo(0, 0) }
        }
        showStatus(message)
    }

    private fun enableControls(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) enableControls(view.getChildAt(index), enabled)
    }

    private fun defaultStatus() = localized(
        "保留照片附近的片边，点击「一键识别并校正」。自动选照片、识别片基并完成反相，完成后可微调。",
        "Include nearby film borders and tap Auto detect and correct. The photograph, base and inversion are automatic; fine-tune afterwards.")

    fun discardFrozenFrame() {
        pickerSerial++
        if (automaticBaseBusy) {
            automaticBaseBusy = false
            enableControls(controls, true)
        }
        picker?.let { removeView(it); it.dispose() }
        picker = null
        header.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
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
        editor = FilmNegativePickerView(context, state.menuLanguage == MenuLanguage.ENGLISH, frame, mode,
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

    private fun setExpanded(value: Boolean) {
        expanded = value
        scroll.visibility = if (value) View.VISIBLE else View.GONE
        drawerHandle.text = localized(if (value) "收起参数  ▼" else "调整参数  ▲",
            if (value) "Hide adjustments  ▼" else "Adjustments  ▲")
        requestLayout()
    }

    private fun addSlider(title: String, minimum: Float, maximum: Float,
        value: (FilmNegativeSettings) -> Float, parent: LinearLayout = adjustments,
        change: (Float) -> FilmNegativeSettings) {
        val label = text(title, 13f)
        label.setPadding(0, dp(10), 0, 0)
        parent.addView(label)
        val bar = SeekBar(context).apply { max = 1000; contentDescription = title }
        val slider = Slider(bar, label, title, minimum, maximum, value)
        sliders.add(slider)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || updating) return
                val candidate = change(minimum + (maximum - minimum) * progress / 1000f)
                settings = candidate.copy(
                    blackDensity = candidate.blackDensity.coerceAtMost(candidate.whiteDensity - 0.05f))
                refreshSliders()
                listener?.onSettingsChanged(settings)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        parent.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
    }

    private fun refreshSliders() {
        updating = true
        for (slider in sliders) {
            val value = slider.value(settings)
            slider.bar.progress = ((value - slider.minimum) / (slider.maximum - slider.minimum) * 1000f)
                .roundToInt().coerceIn(0, 1000)
            slider.label.text = String.format(Locale.US, "%s  %.2f", slider.title, value)
        }
        monochrome.isChecked = settings.monochrome
        invert.text = if (settings.inverted) localized("正常取景", "Original view") else localized("反相预览", "Invert preview")
        // Original/positive comparison is independent of whether an analysis result exists.
        // Keep the controls available when comparing, until reset or reference invalidation.
        adjustments.visibility = if (settings.inverted ||
            settings.referenceSource != FilmNegativeReferenceSource.DEFAULT) View.VISIBLE else View.GONE
        updating = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        guide.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        header.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val initial = AdaptiveLayout.drawer(width, height, density, header.measuredHeight, dp(48),
            height, expanded, state.isLeftHanded)
        val panelWidth = MeasureSpec.makeMeasureSpec(initial.width, MeasureSpec.EXACTLY)
        drawerHandle.measure(panelWidth, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        controls.measure(panelWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        drawerBounds = AdaptiveLayout.drawer(width, height, density, header.measuredHeight,
            drawerHandle.measuredHeight, controls.measuredHeight, expanded, state.isLeftHanded)
        drawer.measure(panelWidth, MeasureSpec.makeMeasureSpec(drawerBounds.height, MeasureSpec.EXACTLY))
        picker?.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        guide.layout(0, 0, width, height)
        header.layout(0, 0, width, header.measuredHeight)
        drawer.layout(drawerBounds.left, drawerBounds.top,
            drawerBounds.left + drawerBounds.width, drawerBounds.top + drawerBounds.height)
        picker?.layout(0, 0, width, height)
    }

    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false
        minHeight = dp(48)
        setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }
    private fun styleSwitch(view: Switch) {
        view.setTextColor(Color.WHITE); view.textSize = 13f
        view.minHeight = dp(48)
    }
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun localized(chinese: String, english: String) =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese
}
