package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

/** Full-screen About and license browser, styled to match the existing settings surface. */
class InformationView(
    context: Context,
    private val state: MeterState,
) : View(context) {

    enum class Page { ABOUT, OPEN_SOURCE_LICENSES }

    interface Listener {
        fun onCloseRequested()
    }

    private data class LicenseEntry(
        val title: LocalizedLabel,
        val license: String,
        val assetPath: String,
    )

    private data class LicenseHitTarget(
        val entry: LicenseEntry,
        val rect: RectF,
    )

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }

    private var page = Page.ABOUT
    private var selectedLicense: LicenseEntry? = null
    private var closeRect = RectF()
    private var backRect = RectF()
    private var openSourceLicensesRect = RectF()
    private var licenseTargets: List<LicenseHitTarget> = emptyList()
    private var scrollOffset = 0f
    private var maxScrollOffset = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val flingScroller = VerticalFlingScroller(context)
    private var cachedAssetPath: String? = null
    private var cachedAssetText = ""
    private var cachedTextLayoutKey: String? = null
    private var cachedTextLayout: StaticLayout? = null

    private val licenseEntries = listOf(
        LicenseEntry(
            LocalizedLabel("Apache 2.0（光档与 OpenCV）", "Apache 2.0 (lightstop and OpenCV)"),
            "Apache License 2.0",
            "licenses/OpenCV-LICENSE-2.0.txt",
        ),
        LicenseEntry(
            LocalizedLabel("光档版权声明", "lightstop notice"),
            "NOTICE",
            "licenses/lightstop-NOTICE.txt",
        ),
        LicenseEntry(
            LocalizedLabel("OpenCV 版权声明", "OpenCV notice"),
            "NOTICE",
            "licenses/OpenCV-NOTICE.txt",
        ),
        LicenseEntry(
            LocalizedLabel("完整第三方组件清单", "Complete third-party component list"),
            "License index and attribution notices",
            "licenses/THIRD_PARTY_NOTICES.md",
        ),
        LicenseEntry(LocalizedLabel("Android NDK C++ 运行库", "Android NDK C++ runtime"), "Apache-2.0 WITH LLVM-exception and third-party terms", "licenses/third-party/android-ndk-r25b-NOTICE.toolchain.txt"),
        LicenseEntry(LocalizedLabel("oneTBB", "oneTBB"), "Apache License 2.0", "licenses/third-party/onetbb-Apache-2.0.txt"),
        LicenseEntry(LocalizedLabel("KleidiCV", "KleidiCV"), "Apache License 2.0", "licenses/third-party/kleidicv-Apache-2.0.txt"),
        LicenseEntry(LocalizedLabel("Android cpu_features", "Android cpu_features"), "Apache License 2.0", "licenses/third-party/android-cpufeatures-Apache-2.0.txt"),
        LicenseEntry(LocalizedLabel("ITT Notify", "ITT Notify"), "BSD 3-Clause", "licenses/third-party/ittnotify-BSD-3-Clause.txt"),
        LicenseEntry(LocalizedLabel("libjpeg-turbo", "libjpeg-turbo"), "BSD-style, IJG and zlib terms", "licenses/third-party/libjpeg-turbo-LICENSE.md"),
        LicenseEntry(LocalizedLabel("Independent JPEG Group", "Independent JPEG Group"), "IJG terms", "licenses/third-party/libjpeg-turbo-README.ijg"),
        LicenseEntry(LocalizedLabel("libwebp", "libwebp"), "BSD 3-Clause", "licenses/third-party/libwebp-BSD-3-Clause.txt"),
        LicenseEntry(LocalizedLabel("libpng", "libpng"), "PNG Reference Library License", "licenses/third-party/libpng-LICENSE.txt"),
        LicenseEntry(LocalizedLabel("libtiff", "libtiff"), "libtiff license", "licenses/third-party/libtiff-COPYRIGHT.txt"),
        LicenseEntry(LocalizedLabel("OpenJPEG", "OpenJPEG"), "BSD 2-Clause", "licenses/third-party/openjpeg-BSD-2-Clause.txt"),
        LicenseEntry(LocalizedLabel("OpenEXR / IlmBase", "OpenEXR / IlmBase"), "BSD 3-Clause", "licenses/third-party/openexr-BSD-3-Clause.txt"),
        LicenseEntry(LocalizedLabel("Intel IPP ICV", "Intel IPP ICV"), "Intel Simplified Software License", "licenses/third-party/intel-ippicv-EULA.txt"),
        LicenseEntry(LocalizedLabel("Intel IPP ICV 第三方声明", "Intel IPP ICV third-party notices"), "Third-party notices", "licenses/third-party/intel-ippicv-third-party-programs.txt"),
        LicenseEntry(LocalizedLabel("Intel IPP IW", "Intel IPP IW"), "Intel Simplified Software License", "licenses/third-party/intel-ippiw-EULA.txt"),
        LicenseEntry(LocalizedLabel("Intel IPP IW 第三方声明", "Intel IPP IW third-party notices"), "Third-party notices", "licenses/third-party/intel-ippiw-third-party-programs.txt"),
        LicenseEntry(LocalizedLabel("ADE", "ADE"), "Apache License 2.0", "licenses/third-party/ade-Apache-2.0.txt"),
        LicenseEntry(LocalizedLabel("FlatBuffers", "FlatBuffers"), "Apache License 2.0", "licenses/third-party/flatbuffers-Apache-2.0.txt"),
        LicenseEntry(LocalizedLabel("Protocol Buffers", "Protocol Buffers"), "BSD 3-Clause", "licenses/third-party/protobuf-BSD-3-Clause.txt"),
        LicenseEntry(LocalizedLabel("Berkeley SoftFloat", "Berkeley SoftFloat"), "BSD-style license", "licenses/third-party/softfloat-LICENSE.txt"),
        LicenseEntry(LocalizedLabel("MSCR 卡方表数据", "MSCR chi-square table data"), "Attribution notice", "licenses/third-party/mscr-chi-table-LICENSE.txt"),
    )

    fun show(page: Page) {
        flingScroller.cancel()
        this.page = page
        selectedLicense = null
        scrollOffset = 0f
        maxScrollOffset = 0f
        clearLicenseTextCache()
        clearTextLayoutCache()
        invalidate()
    }

    /** Handles the nested license-detail level before the owner closes this whole screen. */
    fun navigateBack(): Boolean {
        when {
            selectedLicense != null -> selectedLicense = null
            page == Page.OPEN_SOURCE_LICENSES -> page = Page.ABOUT
            else -> return false
        }
        scrollOffset = 0f
        maxScrollOffset = 0f
        clearLicenseTextCache()
        clearTextLayoutCache()
        invalidate()
        return true
    }

    /** Releases potentially large license text/layout objects after this screen is dismissed. */
    fun clearContentCache() {
        clearLicenseTextCache()
        clearTextLayoutCache()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val background = if (state.isDarkMode) Color.BLACK else Color.WHITE
        val foreground = if (state.isDarkMode) Color.rgb(210, 210, 206) else Color.rgb(20, 20, 20)
        val navGray = if (state.isDarkMode) Color.rgb(102, 102, 100) else Color.rgb(226, 226, 223)
        val divider = if (state.isDarkMode) Color.rgb(72, 72, 70) else Color.rgb(205, 205, 201)
        val red = Color.rgb(166, 27, 36)
        val headerHeight = minOf(height * 0.15f, 76f * density).coerceAtLeast(58f * density)

        canvas.drawColor(background)
        paint.style = Paint.Style.FILL
        paint.color = navGray
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paint)

        drawHeader(canvas, headerHeight, foreground, red)
        when {
            selectedLicense != null -> drawLicenseText(canvas, headerHeight, foreground)
            page == Page.ABOUT -> drawAbout(canvas, headerHeight, foreground)
            else -> drawLicenseIndex(canvas, headerHeight, foreground, divider, red)
        }
    }

    private fun drawHeader(canvas: Canvas, headerHeight: Float, foreground: Int, red: Int) {
        closeRect = RectF(width - 52f * density, 0f, width.toFloat(), headerHeight)
        backRect = if (selectedLicense != null || page == Page.OPEN_SOURCE_LICENSES) {
            RectF(0f, 0f, 52f * density, headerHeight)
        } else {
            RectF()
        }

        titlePaint.color = foreground
        titlePaint.textSize = 14f * density
        val title = selectedLicense?.title?.resolve(state.menuLanguage) ?: when (page) {
            Page.ABOUT -> localized("关于", "About")
            Page.OPEN_SOURCE_LICENSES -> localized("开源许可证", "Open-source licenses")
        }
        drawCenteredText(canvas, title, width / 2f, headerHeight / 2f, titlePaint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = foreground
        val closeInset = 17f * density
        canvas.drawLine(
            closeRect.left + closeInset,
            closeRect.centerY() - closeInset * 0.52f,
            closeRect.right - closeInset,
            closeRect.centerY() + closeInset * 0.52f,
            paint,
        )
        canvas.drawLine(
            closeRect.left + closeInset,
            closeRect.centerY() + closeInset * 0.52f,
            closeRect.right - closeInset,
            closeRect.centerY() - closeInset * 0.52f,
            paint,
        )
        if (!backRect.isEmpty) {
            val centerX = backRect.centerX()
            val centerY = backRect.centerY()
            canvas.drawLine(centerX + 6f * density, centerY - 10f * density, centerX - 5f * density, centerY, paint)
            canvas.drawLine(centerX - 5f * density, centerY, centerX + 6f * density, centerY + 10f * density, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawRect(12f * density, headerHeight - 2.2f * density, width - 12f * density, headerHeight, paint)
    }

    private fun drawAbout(canvas: Canvas, headerHeight: Float, foreground: Int) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "0.2.2" }
        val text = if (state.menuLanguage == MenuLanguage.ENGLISH) {
            """
            lightstop
            Version $version

            ABOUT
            lightstop is a free and open-source photographic light meter. The project was created through vibe coding with AI-assisted development and is released under the Apache License, Version 2.0.

            OPENCV
            This application includes a slim, locally built OpenCV 4.12.0 runtime. OpenCV provides sparse pyramidal Lucas-Kanade optical-flow tracking for points marked in Zone mode. OpenCV is licensed under the Apache License, Version 2.0. OpenCV and other third-party components remain the property of their respective copyright holders. Their names identify software origin only and do not imply endorsement.

            METERING RESULT NOTICE
            lightstop is a photographic exposure-assistance tool and is not a certified or professionally calibrated measuring instrument. Results may vary with device hardware, system implementation, camera parameters, user calibration, and shooting conditions, and should be treated as estimates for reference only.

            For important, non-repeatable, commercial, or other work requiring reliable exposure, verify readings with a calibrated professional light meter, test exposures, exposure bracketing, or another independent method. Do not rely on this application as the sole measurement source.

            To the maximum extent permitted by applicable law, the application and its developers make no guarantee of absolute measurement accuracy and accept no liability for film, lost shooting opportunities, production costs, or other indirect or consequential losses resulting from reliance on its readings. Nothing in this notice excludes or limits rights or liabilities that cannot legally be excluded or limited.

            SOURCE CODE
            https://github.com/the-waterwheel/lightstop
            """.trimIndent()
        } else {
            """
            光档（lightstop）
            版本 $version

            关于本应用
            光档是一款免费、开源的摄影测光工具。本项目采用 Vibe Coding，在 AI 辅助下完成，并以 Apache License 2.0 开源发布。

            OPENCV
            本应用包含自行精简编译的 OpenCV 4.12.0 运行库，使用其稀疏金字塔 Lucas-Kanade 光流为 Zone 模式下的标点提供跟踪。OpenCV 使用 Apache License 2.0。OpenCV 及其他第三方组件的权利归各自权利人所有；名称仅用于说明软件来源，不代表相关项目对本应用的认可或背书。

            测光结果说明
            光档是一款摄影曝光辅助工具，并非经过计量认证或专业校准的测量仪器。测光结果可能因设备硬件、系统实现、相机参数、用户校准和拍摄环境而产生偏差，仅供曝光估算与参考。

            对于不可重复的重要拍摄、商业制作或其他对曝光准确性要求较高的场景，请使用经过校准的专业测光表，并通过试拍、包围曝光或其他独立方式复核结果。请勿将本应用作为唯一测量依据。

            在适用法律允许的最大范围内，本应用及开发者不对测光结果的绝对准确性作出保证，也不对因依赖测光结果而产生的胶片损失、拍摄机会损失、制作成本或其他间接、后果性损失承担责任。本声明不排除或限制适用法律规定不能排除或限制的权利与责任。

            源代码
            https://github.com/the-waterwheel/lightstop
            """.trimIndent()
        }
        drawAboutContent(canvas, headerHeight, foreground, text)
    }

    /** Places the license browser after all About copy, making it a true child of About. */
    private fun drawAboutContent(
        canvas: Canvas,
        headerHeight: Float,
        foreground: Int,
        text: String,
    ) {
        val horizontalPadding = 18f * density
        val verticalPadding = 18f * density
        val buttonGap = 18f * density
        val buttonHeight = if (width > height) 46f * density else 52f * density
        val bottomPadding = 18f * density
        val availableWidth = (width - horizontalPadding * 2f).toInt().coerceAtLeast(1)
        bodyPaint.color = foreground
        bodyPaint.textSize = (if (width > height) 11.5f else 13f) * density
        val cacheKey = "about|${availableWidth}|${bodyPaint.textSize}|${text.hashCode()}|${text.length}"
        val layout = textLayout(text, availableWidth, 3f * density, cacheKey)
        val viewportHeight = (height - headerHeight).coerceAtLeast(0f)
        val contentHeight = verticalPadding + layout.height + buttonGap + buttonHeight + bottomPadding
        maxScrollOffset = max(0f, contentHeight - viewportHeight)
        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)
        licenseTargets = emptyList()

        val buttonTop = headerHeight + verticalPadding + layout.height + buttonGap - scrollOffset
        openSourceLicensesRect = RectF(
            horizontalPadding,
            buttonTop,
            width - horizontalPadding,
            buttonTop + buttonHeight,
        )
        canvas.save()
        canvas.clipRect(0f, headerHeight, width.toFloat(), height.toFloat())
        canvas.translate(horizontalPadding, headerHeight + verticalPadding - scrollOffset)
        layout.draw(canvas)
        canvas.restore()

        val divider = if (state.isDarkMode) Color.rgb(72, 72, 70) else Color.rgb(205, 205, 201)
        canvas.save()
        canvas.clipRect(0f, headerHeight, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = if (state.isDarkMode) Color.rgb(18, 18, 18) else Color.WHITE
        canvas.drawRect(openSourceLicensesRect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = divider
        canvas.drawRect(openSourceLicensesRect, paint)
        titlePaint.color = foreground
        titlePaint.textSize = 11f * density
        canvas.drawText(
            localized("开源许可证", "Open-source licenses"),
            openSourceLicensesRect.left + 13f * density,
            openSourceLicensesRect.centerY() -
                (titlePaint.fontMetrics.ascent + titlePaint.fontMetrics.descent) / 2f,
            titlePaint,
        )
        paint.color = Color.rgb(166, 27, 36)
        paint.strokeWidth = 1.4f * density
        val arrowX = openSourceLicensesRect.right - 17f * density
        val arrowY = openSourceLicensesRect.centerY()
        canvas.drawLine(arrowX - 3f * density, arrowY - 6f * density, arrowX + 3f * density, arrowY, paint)
        canvas.drawLine(arrowX + 3f * density, arrowY, arrowX - 3f * density, arrowY + 6f * density, paint)
        canvas.restore()
    }

    private fun drawLicenseText(canvas: Canvas, headerHeight: Float, foreground: Int) {
        val entry = selectedLicense ?: return
        if (cachedAssetPath != entry.assetPath) {
            cachedAssetText = runCatching {
                context.assets.open(entry.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse {
                localized(
                    "无法读取此许可证文本。完整文件仍保留在 APK 的 ${entry.assetPath}。",
                    "Unable to read this license text. The complete file remains bundled at ${entry.assetPath}.",
                )
            }
            cachedAssetPath = entry.assetPath
        }
        drawScrollableText(canvas, headerHeight, foreground, cachedAssetText)
    }

    private fun drawScrollableText(
        canvas: Canvas,
        headerHeight: Float,
        foreground: Int,
        text: String,
    ) {
        val horizontalPadding = 18f * density
        val verticalPadding = 18f * density
        val availableWidth = (width - horizontalPadding * 2f).toInt().coerceAtLeast(1)
        bodyPaint.color = foreground
        bodyPaint.textSize = (if (width > height) 11.5f else 13f) * density
        val cacheKey = "document|${availableWidth}|${bodyPaint.textSize}|${text.hashCode()}|${text.length}"
        val layout = textLayout(text, availableWidth, 3f * density, cacheKey)
        val viewportHeight = (height - headerHeight).coerceAtLeast(0f)
        val contentHeight = verticalPadding * 2f + layout.height
        maxScrollOffset = max(0f, contentHeight - viewportHeight)
        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)
        licenseTargets = emptyList()

        canvas.save()
        canvas.clipRect(0f, headerHeight, width.toFloat(), height.toFloat())
        canvas.translate(horizontalPadding, headerHeight + verticalPadding - scrollOffset)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawLicenseIndex(
        canvas: Canvas,
        headerHeight: Float,
        foreground: Int,
        divider: Int,
        red: Int,
    ) {
        val padding = 12f * density
        val rowGap = 8f * density
        val rowHeight = if (width > height) 58f * density else 66f * density
        val intro = localized(
            "光档以 Apache License 2.0 开源。本应用使用 OpenCV 4.12.0 及以下第三方组件。点击项目可查看随 APK 分发的原始许可证或版权声明；许可证正文保持原文，避免翻译改变其含义。",
            "lightstop is open source under Apache License 2.0. This application uses OpenCV 4.12.0 and the components below. Tap an item to read the original license or notice distributed in the APK. Legal texts remain in their original language to preserve their meaning.",
        )
        bodyPaint.color = foreground
        bodyPaint.textSize = (if (width > height) 10.5f else 12f) * density
        val introWidth = (width - padding * 2f).toInt().coerceAtLeast(1)
        val introLayout = textLayout(
            intro,
            introWidth,
            2f * density,
            "license-index|$introWidth|${bodyPaint.textSize}|${intro.hashCode()}",
        )
        val contentHeight = padding + introLayout.height + padding +
            licenseEntries.size * rowHeight + max(0, licenseEntries.size - 1) * rowGap + padding
        maxScrollOffset = max(0f, contentHeight - (height - headerHeight))
        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)

        canvas.save()
        canvas.clipRect(0f, headerHeight, width.toFloat(), height.toFloat())
        canvas.translate(0f, -scrollOffset)
        canvas.save()
        canvas.translate(padding, headerHeight + padding)
        introLayout.draw(canvas)
        canvas.restore()

        var y = headerHeight + padding + introLayout.height + padding
        val targets = mutableListOf<LicenseHitTarget>()
        licenseEntries.forEach { entry ->
            val row = RectF(padding, y, width - padding, y + rowHeight)
            val screenTop = row.top - scrollOffset
            val screenBottom = row.bottom - scrollOffset
            if (screenBottom < headerHeight || screenTop > height) {
                y += rowHeight + rowGap
                return@forEach
            }
            paint.style = Paint.Style.FILL
            paint.color = if (state.isDarkMode) Color.rgb(18, 18, 18) else Color.WHITE
            canvas.drawRect(row, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = divider
            canvas.drawRect(row, paint)

            titlePaint.color = foreground
            titlePaint.textSize = 11f * density
            canvas.drawText(
                entry.title.resolve(state.menuLanguage),
                row.left + 12f * density,
                row.top + 24f * density,
                titlePaint,
            )
            paint.style = Paint.Style.FILL
            paint.color = foreground
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 8.5f * density
            canvas.drawText(
                entry.license,
                row.left + 12f * density,
                row.bottom - 13f * density,
                paint,
            )
            paint.color = red
            paint.strokeWidth = 1.4f * density
            val arrowX = row.right - 17f * density
            val arrowY = row.centerY()
            canvas.drawLine(arrowX - 3f * density, arrowY - 6f * density, arrowX + 3f * density, arrowY, paint)
            canvas.drawLine(arrowX + 3f * density, arrowY, arrowX - 3f * density, arrowY + 6f * density, paint)
            targets += LicenseHitTarget(
                entry,
                RectF(row.left, row.top - scrollOffset, row.right, row.bottom - scrollOffset),
            )
            y += rowHeight + rowGap
        }
        licenseTargets = targets
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                flingScroller.begin(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > touchSlop) dragging = true
                if (dragging && maxScrollOffset > 0f) {
                    scrollOffset = flingScroller.drag(event, scrollOffset, maxScrollOffset)
                    postInvalidateOnAnimation()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && abs(event.x - downX) <= touchSlop * 2f) {
                    handleTap(event.x, event.y)
                    flingScroller.cancel()
                } else if (dragging && flingScroller.finish(event, scrollOffset, maxScrollOffset)) {
                    postInvalidateOnAnimation()
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                flingScroller.cancel()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        flingScroller.compute(maxScrollOffset)?.let {
            scrollOffset = it
            postInvalidateOnAnimation()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        flingScroller.cancel()
        super.onDetachedFromWindow()
    }

    private fun handleTap(x: Float, y: Float) {
        if (closeRect.contains(x, y)) {
            haptic()
            listener?.onCloseRequested()
            return
        }
        if (!backRect.isEmpty && backRect.contains(x, y)) {
            haptic()
            navigateBack()
            return
        }
        if (y < closeRect.bottom) return
        if (page == Page.ABOUT && openSourceLicensesRect.contains(x, y)) {
            page = Page.OPEN_SOURCE_LICENSES
            scrollOffset = 0f
            maxScrollOffset = 0f
            openSourceLicensesRect = RectF()
            clearTextLayoutCache()
            haptic()
            invalidate()
            return
        }
        val target = licenseTargets.firstOrNull { it.rect.contains(x, y) } ?: return
        selectedLicense = target.entry
        scrollOffset = 0f
        maxScrollOffset = 0f
        clearTextLayoutCache()
        haptic()
        invalidate()
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun clearTextLayoutCache() {
        cachedTextLayoutKey = null
        cachedTextLayout = null
    }

    private fun clearLicenseTextCache() {
        cachedAssetPath = null
        cachedAssetText = ""
    }

    private fun textLayout(
        text: String,
        availableWidth: Int,
        lineSpacing: Float,
        cacheKey: String,
    ): StaticLayout {
        if (cachedTextLayoutKey == cacheKey) return requireNotNull(cachedTextLayout)
        return StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(lineSpacing, 1f)
            .build()
            .also {
                cachedTextLayoutKey = cacheKey
                cachedTextLayout = it
            }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        textPaint: Paint,
    ) {
        val metrics = textPaint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, centerX - textPaint.measureText(text) / 2f, baseline, textPaint)
    }
}
