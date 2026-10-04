package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

internal enum class FilmNegativePickMode { BASE, FRAME }

/** A memory-only original frame. Selection coordinates always refer to the unzoomed bitmap. */
@SuppressLint("ViewConstructor")
internal class FilmNegativePickerView(context: Context, private val english: Boolean,
    frame: FilmNegativeFrozenFrame, val mode: FilmNegativePickMode,
    cancel: () -> Unit, confirm: (FilmNegativeRegion) -> Unit, findBase: () -> Unit) : LinearLayout(context) {
    private val message = TextView(context)
    private val image = RegionImage(context, frame, mode)
    private val confirmButton: Button
    private val autoButton: Button

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(16, 16, 16))
        isClickable = true
        message.setTextColor(Color.WHITE)
        message.textSize = 13f
        val padding = (12 * resources.displayMetrics.density).toInt()
        message.setPadding(padding, padding / 2, padding, padding / 2)
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(context)
        fun action(label: String, callback: () -> Unit) = Button(context).apply {
            text = label; textSize = 12f; isAllCaps = false
            setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(48, 48, 48))
            setOnClickListener { callback() }
        }
        actions.addView(action(word("取消", "Cancel"), cancel), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        autoButton = action(if (mode == FilmNegativePickMode.BASE) word("自动找片基", "Find base")
            else word("自动选区", "Auto region"), findBase)
        actions.addView(autoButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.5f))
        confirmButton = action(word("确认选区", "Use selection")) { confirm(image.region) }
        actions.addView(confirmButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.5f))
        addView(actions)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showMessage(if (mode == FilmNegativePickMode.BASE)
            word("静止原图：单指圈选均匀片边，双指放大 / 移动。", "Frozen original: drag over clear film; pinch to zoom / move.")
        else word("静止原图：框选一张照片内容，系统在选区附近找片基并估计 Dmin / Dmax；双指放大 / 移动。",
            "Frozen original: select one photograph; nearby borders establish film base and Dmin / Dmax. Pinch to zoom / move."))
    }

    fun showMessage(value: String) { message.text = value }
    fun select(region: FilmNegativeRegion, mask: FilmNegativeMask? = null) {
        image.region = region; image.setMask(mask); image.invalidate()
    }
    fun setBusy(busy: Boolean) {
        confirmButton.isEnabled = !busy; autoButton.isEnabled = !busy; image.isEnabled = !busy
    }
    fun dispose() { image.dispose() }
    private fun word(chinese: String, englishText: String) = if (english) englishText else chinese

    @SuppressLint("ViewConstructor")
    private class RegionImage(context: Context, frame: FilmNegativeFrozenFrame,
        private val mode: FilmNegativePickMode) : View(context) {
        private val bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val boundary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 215, 70); style = Paint.Style.STROKE
            strokeWidth = 2 * resources.displayMetrics.density
        }
        private val imageBounds = RectF()
        private val selectionBounds = RectF()
        private var contour: Path? = null
        private val contourTransform = android.graphics.Matrix()
        private val displayContour = Path()
        fun setMask(mask: FilmNegativeMask?) {
            contour = mask?.let { shape -> Path().apply {
                fun edge(x1: Float, y1: Float, x2: Float, y2: Float) {
                    moveTo(x1 / shape.width, y1 / shape.height)
                    lineTo(x2 / shape.width, y2 / shape.height)
                }
                for (y in 0 until shape.height) for (x in 0 until shape.width) {
                    if (!shape.at(x, y)) continue
                    if (!shape.at(x - 1, y)) edge(x.toFloat(), y.toFloat(), x.toFloat(), y + 1f)
                    if (!shape.at(x + 1, y)) edge(x + 1f, y.toFloat(), x + 1f, y + 1f)
                    if (!shape.at(x, y - 1)) edge(x.toFloat(), y.toFloat(), x + 1f, y.toFloat())
                    if (!shape.at(x, y + 1)) edge(x.toFloat(), y + 1f, x + 1f, y + 1f)
                }
            } }
        }
        var region = if (mode == FilmNegativePickMode.BASE) FilmNegativeRegion(.45f, .45f, .55f, .55f)
            else FilmNegativeRegion(.1f, .1f, .9f, .9f)
        private var scale = 1f
        private var originX = 0f
        private var originY = 0f
        private var fitScale = 1f
        private var startX = 0f
        private var startY = 0f
        private var previousX = 0f
        private var previousY = 0f
        private var moving = false
        private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (scale * detector.scaleFactor).coerceIn(fitScale, fitScale * 8)
                val factor = next / scale
                originX = detector.focusX - (detector.focusX - originX) * factor
                originY = detector.focusY - (detector.focusY - originY) * factor
                scale = next
                constrain(); invalidate()
                return true
            }
        })

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            fitScale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height).coerceAtLeast(.001f)
            scale = fitScale
            originX = (w - bitmap.width * scale) / 2
            originY = (h - bitmap.height * scale) / 2
        }

        override fun onDraw(canvas: Canvas) {
            imageBounds.set(originX, originY, originX + bitmap.width * scale, originY + bitmap.height * scale)
            canvas.drawBitmap(bitmap, null, imageBounds, paint)
            selectionBounds.set(originX + region.left * bitmap.width * scale,
                originY + region.top * bitmap.height * scale,
                originX + region.right * bitmap.width * scale, originY + region.bottom * bitmap.height * scale)
            if (mode == FilmNegativePickMode.BASE) canvas.drawOval(selectionBounds, boundary)
            else if (contour != null) {
                contourTransform.apply {
                    setScale(bitmap.width * scale, bitmap.height * scale)
                    postTranslate(originX, originY)
                }
                contour!!.transform(contourTransform, displayContour)
                canvas.drawPath(displayContour, boundary)
            } else canvas.drawRect(selectionBounds, boundary)
        }

        private fun constrain() {
            val w = bitmap.width * scale; val h = bitmap.height * scale
            originX = if (w <= width) (width - w) / 2 else originX.coerceIn(width - w, 0f)
            originY = if (h <= height) (height - h) / 2 else originY.coerceIn(height - h, 0f)
        }
        private fun imageX(x: Float) = ((x - originX) / (bitmap.width * scale)).coerceIn(0f, 1f)
        private fun imageY(y: Float) = ((y - originY) / (bitmap.height * scale)).coerceIn(0f, 1f)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return true
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moving = false
                    startX = imageX(event.x); startY = imageY(event.y)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    moving = true
                    previousX = (event.getX(0) + event.getX(1)) / 2
                    previousY = (event.getY(0) + event.getY(1)) / 2
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    originX += cx - previousX; originY += cy - previousY
                    previousX = cx; previousY = cy
                    constrain(); invalidate()
                } else if (!moving) {
                    contour = null
                    val x = imageX(event.x); val y = imageY(event.y)
                    region = FilmNegativeRegion(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                    invalidate()
                }
                MotionEvent.ACTION_UP -> { performClick() }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        fun dispose() { bitmap.recycle() }
    }
}
