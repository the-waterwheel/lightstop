package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Drag three fixed-input anchors vertically; output ordering is enforced by the curve model. */
@SuppressLint("ViewConstructor")
internal class FilmNegativeCurveView(context: Context, private val dark: () -> Boolean,
    private val changed: (FilmNegativeCurve) -> Unit) : View(context) {
    var curve = FilmNegativeCurve()
        set(value) { field = value; invalidate() }
    var channelColor = 0xffa3262e.toInt()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val graph = RectF()
    private val density = resources.displayMetrics.density
    private var dragging = 0

    init {
        contentDescription = "RGB curve: drag shadow, midtone or highlight points"
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        graph.set(18 * density, 12 * density, width - 18 * density, height - 18 * density)
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
        val points = curve.points()
        for (i in 0..4) {
            val x = graph.left + i * graph.width() / 4
            val y = graph.bottom - points[i] * graph.height()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.color = channelColor; paint.strokeWidth = 2 * density
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        for (i in 1..3) canvas.drawCircle(graph.left + i * graph.width() / 4,
            graph.bottom - points[i] * graph.height(), 5 * density, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || graph.width() <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = (1..3).minBy { abs(event.x - graph.left - it * graph.width() / 4) }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging != 0) {
                curve = curve.move(dragging, (graph.bottom - event.y) / graph.height())
                changed(curve)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0; parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
