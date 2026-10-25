package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.content.Context

/** Framing belongs to the scene; inspection zoom only projects it onto the display. */
internal object ViewfinderGeometry {
    fun framingCrop(previewAspect: Float, frameAspect: Float, focalZoom: Float): NormalizedSensorViewport {
        val preview = previewAspect.coerceAtLeast(.01f)
        val frame = frameAspect.coerceAtLeast(.01f)
        val zoom = focalZoom.coerceAtLeast(1f)
        val w = minOf(1f, frame / preview) / zoom
        val h = minOf(1f, preview / frame) / zoom
        return NormalizedSensorViewport(.5f - w / 2, .5f - h / 2, .5f + w / 2, .5f + h / 2)
    }

    fun project(crop: NormalizedSensorViewport, zoom: Float): NormalizedSensorViewport =
        NormalizedSensorViewport(.5f + (crop.left - .5f) * zoom, .5f + (crop.top - .5f) * zoom,
            .5f + (crop.right - .5f) * zoom, .5f + (crop.bottom - .5f) * zoom)

    fun compose(outer: NormalizedSensorViewport, inner: NormalizedSensorViewport) = NormalizedSensorViewport(
        outer.left + inner.left * outer.width, outer.top + inner.top * outer.height,
        outer.left + inner.right * outer.width, outer.top + inner.bottom * outer.height)

    fun rect(bounds: RectF, crop: NormalizedSensorViewport) = RectF(
        bounds.left + crop.left * bounds.width(), bounds.top + crop.top * bounds.height(),
        bounds.left + crop.right * bounds.width(), bounds.top + crop.bottom * bounds.height())

    fun sensorCrop(crop: NormalizedSensorViewport, transform: ScreenToSensorCoordinateTransform): NormalizedSensorViewport {
        val corners = listOf(transform.map(crop.left, crop.top), transform.map(crop.right, crop.top),
            transform.map(crop.left, crop.bottom), transform.map(crop.right, crop.bottom))
        return NormalizedSensorViewport(corners.minOf { it.first }, corners.minOf { it.second },
            corners.maxOf { it.first }, corners.maxOf { it.second })
    }
}

/** The opaque outer surface clips both the image and its square-corner framing overlay. */
internal object ViewfinderOverlay {
    fun clip(canvas: Canvas, bounds: RectF, density: Float) {
        canvas.clipPath(Path().apply { addRoundRect(bounds, 12f * density, 12f * density, Path.Direction.CW) })
    }
    fun outside(canvas: Canvas, bounds: RectF, width: Int, height: Int, surface: Int, density: Float) {
        val path = Path().apply { addRoundRect(bounds, 12f * density, 12f * density, Path.Direction.CW) }
        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawColor(surface)
        canvas.restore()
    }

    fun framing(canvas: Canvas, bounds: RectF, crop: NormalizedSensorViewport, density: Float) {
        val path = Path().apply { addRoundRect(bounds, 12f * density, 12f * density, Path.Direction.CW) }
        val frame = ViewfinderGeometry.rect(bounds, crop)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.clipPath(path)
        val mask = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(bounds, Path.Direction.CW)
            addRect(frame, Path.Direction.CW)
        }
        paint.color = 0x66808080
        canvas.drawPath(mask, paint)
        paint.color = 0xBBD0D0D0.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        canvas.drawRect(frame, paint)
        canvas.restore()
    }
}

/** A pinch owns its complete pointer sequence, so its final UP cannot create a Zone point. */
internal class ViewfinderPinch(context: Context, private val changed: (Float) -> Unit) {
    private var active = false
    private var allowed = false
    private var previousSpan = 0f
    private val minimumSpan = 8f * context.resources.displayMetrics.density

    fun consume(event: MotionEvent, preview: RectF, enabled: Boolean): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            active = false
            allowed = enabled && preview.contains(event.x, event.y)
            previousSpan = 0f
        }
        if (allowed && enabled && event.pointerCount == 2) {
            val span = kotlin.math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
                preview.contains(event.getX(1), event.getY(1))) {
                active = true
                previousSpan = span
            } else if (active && event.actionMasked == MotionEvent.ACTION_MOVE) {
                if (span >= minimumSpan && previousSpan >= minimumSpan) changed(span / previousSpan)
                previousSpan = span
            }
        }
        val consumed = active
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            active = false
            allowed = false
            previousSpan = 0f
        }
        return consumed
    }
}
