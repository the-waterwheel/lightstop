package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/** Deliberate slide-to-capture control shown above Normal's meter or Zone's mark button. */
@SuppressLint("ViewConstructor")
internal class RecordCaptureSliderView(
    context: Context,
    private val state: MeterState,
) : View(context) {
    var onCaptureRequested: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(224, 224, 220) else Color.rgb(20, 20, 20)
    private val surface: Int get() = if (state.isDarkMode) Color.rgb(46, 46, 44) else Color.rgb(235, 235, 232)
    private val red = Color.rgb(201, 39, 46)
    private var anchor = RectF()
    private var track = RectF()
    private var fraction = 0f
    private var dragOffset = 0f
    private var dragging = false
    private var capturePending = false
    private var returnAnimator: ValueAnimator? = null

    fun setAnchor(value: RectF) {
        anchor = RectF(value)
        calculateTrack()
        invalidate()
    }

    fun setCapturePending(value: Boolean) {
        capturePending = value
        if (!value) fraction = 0f
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
        var top = anchor.top - trackHeight - 10f * density
        if (top < margin) top = (anchor.bottom + 10f * density).coerceAtMost(height - margin - trackHeight)
        track = RectF(left, top, left + desiredWidth, top + trackHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = calculateTrack()

    override fun onDraw(canvas: Canvas) {
        if (track.isEmpty) return
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = foreground
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint)
        val knobRadius = track.height() * 0.38f
        val start = track.left + track.height() / 2f
        val end = track.right - track.height() / 2f
        val x = start + (end - start) * fraction
        paint.style = Paint.Style.FILL
        paint.color = if (capturePending) Color.rgb(150, 150, 146) else red
        canvas.drawCircle(x, track.centerY(), knobRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        val chevronHalf = 3.2f * density
        canvas.drawLine(x - chevronHalf, track.centerY() - chevronHalf, x + chevronHalf, track.centerY(), paint)
        canvas.drawLine(x + chevronHalf, track.centerY(), x - chevronHalf, track.centerY() + chevronHalf, paint)
        paint.strokeCap = Paint.Cap.BUTT

        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textSize = 8f * density * resources.configuration.fontScale
        paint.textAlign = Paint.Align.CENTER
        val text = if (capturePending) localized("处理中", "Saving") else localized("滑动记录", "Slide")
        val metrics = paint.fontMetrics
        val textStart = start + knobRadius + 3f * density
        canvas.drawText(text, (textStart + end) / 2f, track.centerY() - (metrics.ascent + metrics.descent) / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (capturePending) return track.contains(event.x, event.y)
        val knobRadius = track.height() * 0.55f
        val start = track.left + track.height() / 2f
        val end = track.right - track.height() / 2f
        val knobX = start + (end - start) * fraction
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!track.contains(event.x, event.y) || kotlin.math.abs(event.x - knobX) > knobRadius) return false
                returnAnimator?.cancel()
                dragging = true
                dragOffset = event.x - knobX
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                fraction = ((event.x - dragOffset - start) / (end - start)).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked != MotionEvent.ACTION_CANCEL && fraction >= 0.92f) {
                    fraction = 1f
                    capturePending = true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onCaptureRequested?.invoke()
                    invalidate()
                } else {
                    animateBack()
                }
                performClick()
                return true
            }
        }
        return false
    }

    private fun animateBack() {
        val start = fraction
        returnAnimator?.cancel()
        returnAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
