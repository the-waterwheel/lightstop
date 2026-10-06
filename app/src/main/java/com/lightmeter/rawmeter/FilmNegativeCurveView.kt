package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Point editor: tap adds, drag moves both axes, double tap resets, long press removes. */
@SuppressLint("ViewConstructor")
internal class FilmNegativeCurveView(context: Context, private val dark: () -> Boolean,
    private val changed: (FilmNegativeCurve) -> Unit) : View(context) {
    private var currentCurve = FilmNegativeCurve()
    var curve: FilmNegativeCurve
        get() = currentCurve
        set(value) {
            // Cancel delayed taps when changing channel, resetting, or applying new calibration.
            cancelInteraction(); currentCurve = value; selected = -1; invalidate()
        }
    var channelColor = 0xffa3262e.toInt()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val graph = RectF()
    private val density = resources.displayMetrics.density
    private var selected = -1
    private var pressed = -1
    private var dragCurve = currentCurve
    private var downX = 0f
    private var downY = 0f
    private var multiplePointers = false
    private lateinit var gestures: GestureDetector

    init {
        isClickable = true
        gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                pressed = hitPoint(e.x, e.y); selected = pressed
                dragCurve = currentCurve; downX = e.x; downY = e.y
                parent?.requestDisallowInterceptTouchEvent(pressed >= 0)
                invalidate()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isEnabled || !isShown || !graph.contains(e.x, e.y)) return false
                val hit = hitPoint(e.x, e.y)
                if (hit >= 0) { selected = hit; invalidate(); return true }
                val input = ((e.x - graph.left) / graph.width()).coerceIn(0f, 1f)
                val output = ((graph.bottom - e.y) / graph.height()).coerceIn(0f, 1f)
                val next = currentCurve.add(input, output)
                if (next != currentCurve) {
                    selected = next.points().indexOfFirst { it.input == input }
                    publish(next)
                }
                performClick()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val hit = hitPoint(e.x, e.y)
                if (isEnabled && hit >= 0) { selected = hit; publish(currentCurve.reset(hit)); performClick() }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                val hit = hitPoint(e.x, e.y)
                if (!isEnabled || hit < 0) return
                val next = currentCurve.remove(hit)
                if (next != currentCurve) { selected = -1; pressed = -1; publish(next) }
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (pressed < 0 || multiplePointers) return false
                val point = dragCurve.points()[pressed]
                publish(dragCurve.move(pressed, point.input + (e2.x - downX) / graph.width(),
                    point.output - (e2.y - downY) / graph.height()))
                return true
            }
        })
    }

    private fun publish(value: FilmNegativeCurve) {
        if (value != currentCurve) { currentCurve = value; changed(value) }
        invalidate()
    }
    private fun hitPoint(x: Float, y: Float): Int {
        val points = currentCurve.points()
        val nearest = points.indices.minByOrNull { i ->
            hypot(x - graph.left - points[i].input * graph.width(), y - graph.bottom + points[i].output * graph.height())
        } ?: return -1
        val p = points[nearest]
        return if (hypot(x - graph.left - p.input * graph.width(), y - graph.bottom + p.output * graph.height()) <= 20 * density)
            nearest else -1
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cancelInteraction()
        graph.set(18 * density, 12 * density, w - 18 * density, h - 18 * density)
    }
    override fun onDraw(canvas: Canvas) {
        if (graph.width() <= 0 || graph.height() <= 0) return
        paint.strokeWidth = density; paint.style = Paint.Style.STROKE
        paint.color = if (dark()) 0xff383838.toInt() else 0xffe0e0e0.toInt()
        for (i in 0..4) {
            val t = i / 4f
            canvas.drawLine(graph.left + t * graph.width(), graph.top, graph.left + t * graph.width(), graph.bottom, paint)
            canvas.drawLine(graph.left, graph.bottom - t * graph.height(), graph.right, graph.bottom - t * graph.height(), paint)
        }
        paint.color = if (dark()) 0xff707070.toInt() else 0xffbcbcbc.toInt()
        canvas.drawLine(graph.left, graph.bottom, graph.right, graph.top, paint)
        path.reset()
        for (i in 0..256) {
            val x = i / 256f
            val y = currentCurve.evaluate(x.toDouble()).toFloat()
            if (i == 0) path.moveTo(graph.left, graph.bottom - y * graph.height())
            else path.lineTo(graph.left + x * graph.width(), graph.bottom - y * graph.height())
        }
        paint.color = channelColor; paint.strokeWidth = 2 * density
        canvas.drawPath(path, paint)
        for ((i, point) in currentCurve.points().withIndex()) {
            val x = graph.left + point.input * graph.width(); val y = graph.bottom - point.output * graph.height()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, (if (i == selected) 6 else 4) * density, paint)
            if (i == selected) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = density
                canvas.drawCircle(x, y, 10 * density, paint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || graph.width() <= 0 || graph.height() <= 0) { cancelInteraction(); return false }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiplePointers = false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            multiplePointers = true; cancelInteraction(); return true
        }
        if (multiplePointers) return true
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressed = -1; parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    private fun cancelInteraction() {
        if (::gestures.isInitialized) {
            val time = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(time, time, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            gestures.onTouchEvent(cancel); cancel.recycle()
        }
        pressed = -1; parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onDetachedFromWindow() { cancelInteraction(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) cancelInteraction()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelInteraction()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
