package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.min

/** Deliberate hold-to-capture control shown below Normal's meter or Zone's mark button. */
@SuppressLint("ViewConstructor")
internal class RecordCaptureSliderView(
    context: Context,
    private val state: MeterState,
) : View(context) {
    var onCaptureRequested: (() -> Unit)? = null

    private val density get() = layoutDensity(LayoutProfile.SCROLL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val red = InstrumentStyle.red
    private var anchor = RectF()
    private var track = RectF()
    private val innerTrack = RectF()
    private var pressing = false
    private var longPressTriggered = false
    private var downX = 0f
    private var downY = 0f
    private var capturePending = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        if (!pressing || capturePending) return@Runnable
        longPressTriggered = true
        capturePending = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onCaptureRequested?.invoke()
        invalidate()
    }

    fun setAnchor(value: RectF) {
        anchor = RectF(value)
        calculateTrack()
        invalidate()
    }

    fun setCapturePending(value: Boolean) {
        capturePending = value
        if (!value) {
            pressing = false
            longPressTriggered = false
        }
        invalidate()
    }

    private fun calculateTrack() {
        if (width <= 0 || height <= 0 || anchor.isEmpty) return
        val margin = 10f * density
        val desiredWidth = anchor.width()
            .coerceAtLeast(104f * density)
            .coerceAtMost(min(132f * density, width - margin * 2f))
        val trackHeight = 38f * density
        val left = (anchor.centerX() - desiredWidth / 2f).coerceIn(margin, (width - margin - desiredWidth).coerceAtLeast(margin))
        // Recording is a secondary action. Keep it on the same, predictable side of the primary
        // meter button in Normal and Zone instead of flipping above the button on roomy layouts.
        val top = (anchor.bottom + 10f * density).coerceAtMost(
            (height - margin - trackHeight).coerceAtLeast(anchor.bottom),
        )
        track = RectF(left, top, left + desiredWidth, top + trackHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = calculateTrack()

    override fun onDraw(canvas: Canvas) {
        if (track.isEmpty) return
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(10, 10, 10)
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * density
        paint.color = Color.BLACK
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint)
        innerTrack.set(track)
        innerTrack.inset(2.2f * density, 2.2f * density)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = if (capturePending) Color.rgb(126, 38, 42) else red
        canvas.drawRoundRect(
            innerTrack,
            innerTrack.height() / 2f,
            innerTrack.height() / 2f,
            paint,
        )

        paint.style = Paint.Style.FILL
        paint.color = if (capturePending) Color.rgb(160, 160, 156) else Color.WHITE
        paint.textSize = 8.5f * density * resources.configuration.fontScale
        paint.textAlign = Paint.Align.CENTER
        val text = when {
            capturePending -> localized("处理中", "Saving")
            pressing -> localized("继续按住", "Keep holding")
            else -> localized("长按参数记录", "Hold to record")
        }
        val metrics = paint.fontMetrics
        canvas.drawText(text, track.centerX(), track.centerY() - (metrics.ascent + metrics.descent) / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (capturePending) return track.contains(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!track.contains(event.x, event.y)) return false
                pressing = true
                longPressTriggered = false
                downX = event.x
                downY = event.y
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (pressing) {
                if (!track.contains(event.x, event.y) ||
                    kotlin.math.abs(event.x - downX) > touchSlop ||
                    kotlin.math.abs(event.y - downY) > touchSlop
                ) {
                    cancelPress()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pressing || longPressTriggered) {
                removeCallbacks(longPress)
                pressing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && !longPressTriggered) performClick()
                invalidate()
                return true
            }
        }
        return false
    }

    private fun cancelPress() {
        removeCallbacks(longPress)
        pressing = false
        longPressTriggered = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
