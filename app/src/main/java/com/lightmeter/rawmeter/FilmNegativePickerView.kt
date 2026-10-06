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
import kotlin.math.atan2
import kotlin.math.abs

internal enum class FilmNegativePickMode { BASE, FRAME }

/** A memory-only original frame. Selection coordinates always refer to the unzoomed bitmap. */
@SuppressLint("ViewConstructor")
internal class FilmNegativePickerView(context: Context, private val english: Boolean,
    frame: FilmNegativeFrozenFrame, val mode: FilmNegativePickMode,
    cancel: () -> Unit, confirm: (FilmNegativeRegion) -> Unit, findBase: () -> Unit,
    darkMode: Boolean = true) : LinearLayout(context) {
    private val message = TextView(context)
    private val image = RegionImage(context, frame, mode, english)
    private val confirmButton: Button
    private val autoButton: Button

    init {
        orientation = VERTICAL
        val foreground = InstrumentStyle.foreground(darkMode)
        setBackgroundColor(InstrumentStyle.background(darkMode))
        isClickable = true
        message.setTextColor(foreground)
        message.textSize = 13f
        val padding = (12 * resources.displayMetrics.density).toInt()
        message.setPadding(padding, padding / 2, padding, padding / 2)
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(context)
        fun action(label: String, callback: () -> Unit) = Button(context).apply {
            text = label; textSize = 12f; isAllCaps = false
            InstrumentStyle.styleButton(this, darkMode)
            setOnClickListener { callback() }
        }
        actions.addView(action(word("取消", "Cancel"), cancel), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        autoButton = action(if (mode == FilmNegativePickMode.BASE) word("自动找片基", "Find base")
            else word("自动选区", "Auto region"), findBase)
        actions.addView(autoButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.5f))
        confirmButton = action(word("确认选区", "Use selection")) { confirm(image.region) }
        InstrumentStyle.styleButton(confirmButton, darkMode, primary = true)
        actions.addView(confirmButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.5f))
        addView(actions)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showMessage(if (mode == FilmNegativePickMode.BASE)
            word("静止原图：单指圈选均匀片边，双指放大 / 移动。", "Frozen original: drag over clear film; pinch to zoom / move.")
        else "")
    }

    fun showMessage(value: String) {
        val guide = word("拖边调整范围，拖角缩放，框内移动，框外滑动旋转；双指放大 / 移动原图。",
            "Drag edges to crop, corners to resize, inside to move, outside to rotate. Pinch to zoom / move the image.")
        message.text = if (mode == FilmNegativePickMode.BASE) value
            else if (value.isBlank()) guide else "$value\n$guide"
    }
    fun select(region: FilmNegativeRegion) {
        image.region = region; image.invalidate()
    }
    fun setBusy(busy: Boolean) {
        confirmButton.isEnabled = !busy; autoButton.isEnabled = !busy; image.isEnabled = !busy
    }
    fun dispose() { image.dispose() }
    private fun word(chinese: String, englishText: String) = if (english) englishText else chinese

    @SuppressLint("ViewConstructor")
    private class RegionImage(context: Context, frame: FilmNegativeFrozenFrame,
        private val mode: FilmNegativePickMode, english: Boolean) : View(context) {
        private val bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val boundary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 215, 70); style = Paint.Style.STROKE
            strokeWidth = 2 * resources.displayMetrics.density
        }
        private val imageBounds = RectF()
        private val selectionBounds = RectF()
        private var selected = if (mode == FilmNegativePickMode.BASE) FilmNegativeRegion(.45f, .45f, .55f, .55f)
            else FilmNegativeRegion(.1f, .1f, .9f, .9f)
        private var crop = FilmNegativeCrop.from(selected, bitmap.width, bitmap.height)
        var region: FilmNegativeRegion
            get() = selected
            set(value) { selected = value; crop = FilmNegativeCrop.from(value, bitmap.width, bitmap.height); dragHandle = null }
        private var dragCrop = crop
        private var dragHandle: FilmNegativeCropHandle? = null
        private val minimumEdge = max(12f, min(bitmap.width, bitmap.height) * .02f)
        private val shade = Paint().apply { color = 0x70000000 }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x70ffffff; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density
        }
        private val handlePaint = Paint(boundary).apply { style = Paint.Style.FILL }
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

        init {
            contentDescription = if (mode == FilmNegativePickMode.FRAME) {
                if (english) "Crop: drag edges or corners, move inside, rotate outside; pinch to zoom"
                else "胶片选区：拖动边或角点，框内移动，框外旋转，双指缩放原图"
            } else if (english) "Film base selection; drag to select, pinch to zoom"
                else "片基取样：单指圈选，双指缩放原图"
        }

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
            else drawCrop(canvas)
        }

        private fun screen(point: FilmNegativePoint) = FilmNegativePoint(originX + point.x * scale, originY + point.y * scale)
        private fun drawCrop(canvas: Canvas) {
            val points = crop.corners().map(::screen)
            val outline = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (p in points.drop(1)) lineTo(p.x, p.y)
                close()
            }
            val outside = Path(outline).apply {
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                fillType = Path.FillType.EVEN_ODD
            }
            canvas.drawPath(outside, shade)
            for (fraction in listOf(-1f / 6, 1f / 6)) {
                fun line(a: FilmNegativePoint, b: FilmNegativePoint) {
                    val from = screen(a); val to = screen(b)
                    canvas.drawLine(from.x, from.y, to.x, to.y, grid)
                }
                line(crop.point(crop.width * fraction, -crop.height / 2), crop.point(crop.width * fraction, crop.height / 2))
                line(crop.point(-crop.width / 2, crop.height * fraction), crop.point(crop.width / 2, crop.height * fraction))
            }
            canvas.drawPath(outline, boundary)
            val radius = 5 * resources.displayMetrics.density
            for (i in points.indices) {
                val p = points[i]; val next = points[(i + 1) % 4]
                canvas.drawCircle(p.x, p.y, radius, handlePaint)
                val x = (p.x + next.x) / 2; val y = (p.y + next.y) / 2
                canvas.drawCircle(x, y, radius * .7f, handlePaint)
            }
        }

        private fun constrain() {
            val w = bitmap.width * scale; val h = bitmap.height * scale
            originX = if (w <= width) (width - w) / 2 else originX.coerceIn(width - w, 0f)
            originY = if (h <= height) (height - h) / 2 else originY.coerceIn(height - h, 0f)
        }
        private fun imageX(x: Float) = ((x - originX) / (bitmap.width * scale)).coerceIn(0f, 1f)
        private fun imageY(y: Float) = ((y - originY) / (bitmap.height * scale)).coerceIn(0f, 1f)
        private fun pixelX(x: Float) = (x - originX) / scale
        private fun pixelY(y: Float) = (y - originY) / scale

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return true
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moving = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (mode == FilmNegativePickMode.BASE) {
                        startX = imageX(event.x); startY = imageY(event.y)
                    } else {
                        startX = pixelX(event.x); startY = pixelY(event.y)
                        dragCrop = crop
                        dragHandle = crop.hit(startX, startY, 22 * resources.displayMetrics.density / scale)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    moving = true
                    dragHandle = null
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
                    if (mode == FilmNegativePickMode.BASE) {
                        val x = imageX(event.x); val y = imageY(event.y)
                        region = FilmNegativeRegion(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                    } else updateCrop(pixelX(event.x), pixelY(event.y))
                    invalidate()
                }
                // Do not turn the remaining finger of a pinch into a crop/rotation gesture.
                MotionEvent.ACTION_POINTER_UP -> { moving = true; dragHandle = null }
                MotionEvent.ACTION_UP -> { dragHandle = null; parent?.requestDisallowInterceptTouchEvent(false); performClick() }
                MotionEvent.ACTION_CANCEL -> { dragHandle = null; moving = true; parent?.requestDisallowInterceptTouchEvent(false) }
            }
            return true
        }
        private fun updateCrop(x: Float, y: Float) {
            val handle = dragHandle ?: return
            val dx = x - startX; val dy = y - startY
            if (abs(dx) + abs(dy) < .01f) return
            val candidate = when (handle) {
                FilmNegativeCropHandle.MOVE -> dragCrop.move(dx, dy, bitmap.width.toFloat(), bitmap.height.toFloat())
                FilmNegativeCropHandle.ROTATE -> {
                    val from = atan2(startY - dragCrop.centerY, startX - dragCrop.centerX)
                    val to = atan2(y - dragCrop.centerY, x - dragCrop.centerX)
                    dragCrop.copy(angle = dragCrop.angle + FilmNegativeCrop.angleDelta(from, to))
                }
                else -> dragCrop.resize(dx, dy, handle.sideX, handle.sideY, minimumEdge)
            }
            crop = dragCrop.bounded(candidate, bitmap.width.toFloat(), bitmap.height.toFloat(), minimumEdge)
            selected = crop.region(bitmap.width, bitmap.height)
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        fun dispose() { bitmap.recycle() }
    }
}
