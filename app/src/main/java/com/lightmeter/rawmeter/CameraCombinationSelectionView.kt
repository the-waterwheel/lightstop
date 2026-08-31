package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** Transparent full-screen human health check; the real camera preview remains underneath. */
internal class CameraCombinationSelectionView(
    context: Context,
    private val state: MeterState,
) : View(context) {
    interface Listener {
        fun onCombinationLooksNormal()
        fun onCombinationLooksAbnormal()
        fun onCombinationSelectionCancelled()
    }

    var listener: Listener? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private var candidate: CameraCombinationCandidate? = null
    private var candidateIndex = 0
    private var candidateCount = 0
    private var readyForDecision = false
    private var statusText = ""
    private var normalRect = RectF()
    private var abnormalRect = RectF()
    private var cancelRect = RectF()

    fun showCandidate(
        value: CameraCombinationCandidate,
        index: Int,
        count: Int,
        ready: Boolean,
        status: String,
    ) {
        candidate = value
        candidateIndex = index
        candidateCount = count
        readyForDecision = ready
        statusText = status
        invalidate()
    }

    fun updateProbeState(ready: Boolean, status: String) {
        readyForDecision = ready
        statusText = status
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dark = state.isDarkMode
        val panelColor = if (dark) Color.argb(220, 10, 10, 10) else Color.argb(220, 245, 245, 242)
        val foreground = if (dark) Color.rgb(235, 235, 230) else Color.rgb(18, 18, 18)
        val accent = Color.rgb(166, 27, 36)
        val horizontal = 14f * density
        val topPanel = RectF(
            horizontal,
            12f * density,
            width - horizontal,
            116f * density,
        )
        paint.color = panelColor
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(topPanel, 8f * density, 8f * density, paint)

        cancelRect = RectF(
            topPanel.right - 66f * density,
            topPanel.top + 10f * density,
            topPanel.right - 10f * density,
            topPanel.top + 38f * density,
        )
        drawButton(
            canvas,
            cancelRect,
            localized("取消", "Cancel"),
            foreground,
            panelColor,
            enabled = true,
        )

        boldPaint.color = foreground
        boldPaint.textSize = 15f * density
        canvas.drawText(
            localized("人工检测相机组合", "Manual camera combination check"),
            topPanel.left + 13f * density,
            topPanel.top + 29f * density,
            boldPaint,
        )
        val current = candidate
        if (current != null) {
            paint.color = foreground
            paint.textSize = 11f * density
            canvas.drawText(
                "${candidateIndex + 1}/$candidateCount · ${planName(current.plan)} · " +
                    matrixName(current.matrixSupport),
                topPanel.left + 13f * density,
                topPanel.top + 57f * density,
                paint,
            )
            paint.textSize = 9.5f * density
            canvas.drawText(
                processName(current.plan),
                topPanel.left + 13f * density,
                topPanel.top + 80f * density,
                paint,
            )
        }
        paint.color = if (readyForDecision) foreground else accent
        paint.textSize = 9.5f * density
        canvas.drawText(
            statusText,
            topPanel.left + 13f * density,
            topPanel.bottom - 12f * density,
            paint,
        )

        val gap = 10f * density
        val margin = 14f * density
        val bottom = height - 18f * density
        val top = bottom - 54f * density
        val buttonWidth = (width - margin * 2f - gap) / 2f
        normalRect = RectF(margin, top, margin + buttonWidth, bottom)
        abnormalRect = RectF(normalRect.right + gap, top, width - margin, bottom)
        drawButton(
            canvas,
            normalRect,
            localized("画面正常，使用此组合", "Normal — use this combination"),
            foreground,
            panelColor,
            readyForDecision,
        )
        drawButton(
            canvas,
            abnormalRect,
            localized("画面不正常，测试下一个", "Abnormal — test next"),
            foreground,
            panelColor,
            readyForDecision,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        when {
            cancelRect.contains(event.x, event.y) -> {
                haptic()
                listener?.onCombinationSelectionCancelled()
            }
            readyForDecision && normalRect.contains(event.x, event.y) -> {
                haptic()
                listener?.onCombinationLooksNormal()
            }
            readyForDecision && abnormalRect.contains(event.x, event.y) -> {
                haptic()
                listener?.onCombinationLooksAbnormal()
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        text: String,
        foreground: Int,
        panel: Int,
        enabled: Boolean,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = if (enabled) foreground else Color.rgb(115, 115, 112)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f * density
        paint.color = Color.rgb(166, 27, 36)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = panel
        paint.textSize = 9f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        while (paint.textSize > 6f * density && paint.measureText(text) > rect.width() - 10f * density) {
            paint.textSize -= 0.5f * density
        }
        val metrics = paint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, rect.centerX() - paint.measureText(text) / 2f, baseline, paint)
    }

    private fun planName(plan: CameraCombinationPlan): String = when (plan.id) {
        CameraCombinationPolicy.rawSplit.id -> localized("RAW 瞬时隔离", "Split RAW isolation")
        CameraCombinationPolicy.rawFullyIsolated.id -> localized("RAW 全隔离", "Fully isolated RAW")
        CameraCombinationPolicy.rawFull.id -> localized("RAW/YUV 三流", "RAW/YUV three-stream")
        CameraCombinationPolicy.stableYuv.id -> localized("YUV 稳定", "Stable YUV")
        else -> localized("ISP 兼容", "ISP compatibility")
    }

    private fun processName(plan: CameraCombinationPlan): String = when (plan.id) {
        CameraCombinationPolicy.rawSplit.id -> localized(
            "常规：预览+RAW；Zone：预览+YUV，测光瞬时 RAW",
            "Normal: preview+RAW; Zone: preview+YUV, transient RAW",
        )
        CameraCombinationPolicy.rawFullyIsolated.id -> localized(
            "常驻预览+YUV；每次测光临时切换为单 RAW",
            "Resident preview+YUV; each reading briefly switches to RAW only",
        )
        CameraCombinationPolicy.rawFull.id -> localized(
            "常驻预览+RAW+YUV",
            "Resident preview+RAW+YUV",
        )
        CameraCombinationPolicy.stableYuv.id -> localized(
            "常驻预览+YUV，使用 YUV 测光",
            "Resident preview+YUV with YUV metering",
        )
        else -> localized("仅显示预览，使用 ISP 画面测光", "Display preview only with ISP metering")
    }

    private fun matrixName(support: CameraCombinationMatrixSupport): String = when (support) {
        CameraCombinationMatrixSupport.GUARANTEED -> localized("矩阵保证", "matrix guaranteed")
        CameraCombinationMatrixSupport.UNKNOWN -> localized("需实测", "runtime test")
        CameraCombinationMatrixSupport.REJECTED -> localized("已拒绝", "rejected")
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
