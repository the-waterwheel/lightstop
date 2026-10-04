package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/** Owns only a small, blurred display snapshot; never freezes or samples camera metadata. */
@SuppressLint("ViewConstructor")
internal class FilmNegativeSwitchingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val spinner = RectF()
    private val preview = RectF()
    private var bitmap: Bitmap? = null
    private var startedAt = 0L
    private val density = resources.displayMetrics.density

    init { visibility = GONE; contentDescription = "Switching camera" }

    fun start(snapshot: Bitmap?, snapshotBounds: RectF) {
        bitmap?.recycle()
        destination.set(snapshotBounds)
        // Downsample before filtering, bounding UI-thread work and retained image memory.
        bitmap = snapshot?.let { source ->
            val scale = min(1f, 96f / maxOf(source.width, source.height))
            val sampled = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1), true)
            val small = checkNotNull(sampled.copy(Bitmap.Config.ARGB_8888, true))
            if (sampled !== source) source.recycle()
            sampled.recycle()
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val blurred = FilmNegativeBlur.blur(pixels, small.width, small.height)
            small.setPixels(blurred, 0, small.width, 0, 0, small.width, small.height)
            small
        }
        startedAt = SystemClock.uptimeMillis(); visibility = VISIBLE; invalidate()
    }

    fun setPreviewBounds(left: Int, top: Int, right: Int, bottom: Int) { preview.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()) }
    fun finish() { visibility = GONE; bitmap?.recycle(); bitmap = null }

    override fun onDraw(canvas: Canvas) {
        if (preview.isEmpty) return
        canvas.save(); canvas.clipRect(preview)
        bitmap?.let { canvas.drawBitmap(it, null, destination, paint) }
            ?: canvas.drawColor(Color.DKGRAY)
        canvas.drawColor(Color.argb(42, 0, 0, 0))
        val cx = preview.centerX(); val cy = preview.centerY()
        val radius = 20 * density
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density
        paint.color = Color.argb(160, 255, 255, 255)
        spinner.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawOval(spinner, paint)
        paint.color = 0xffab2934.toInt()
        canvas.drawArc(spinner, ((SystemClock.uptimeMillis() - startedAt) % 1000) * .36f, 110f, false, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}

/** A small separable box blur, independent of platform blur APIs (Android 9+). */
internal object FilmNegativeBlur {
    fun blur(source: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == source.size.toLong())
        var input = source.copyOf()
        repeat(3) {
            val horizontal = IntArray(source.size)
            val output = IntArray(source.size)
            for (y in 0 until height) for (x in 0 until width) horizontal[y * width + x] =
                average(input[y * width + (x - 1).coerceAtLeast(0)], input[y * width + x], input[y * width + (x + 1).coerceAtMost(width - 1)])
            for (y in 0 until height) for (x in 0 until width) output[y * width + x] =
                average(horizontal[(y - 1).coerceAtLeast(0) * width + x], horizontal[y * width + x], horizontal[(y + 1).coerceAtMost(height - 1) * width + x])
            input = output
        }
        return input
    }
    private fun average(a: Int, b: Int, c: Int): Int =
        (mean(a, b, c, 24) shl 24) or (mean(a, b, c, 16) shl 16) or
            (mean(a, b, c, 8) shl 8) or mean(a, b, c, 0)
    private fun mean(a: Int, b: Int, c: Int, shift: Int) =
        ((a ushr shift and 255) + (b ushr shift and 255) + (c ushr shift and 255)) / 3
}
