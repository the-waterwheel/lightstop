package com.lightmeter.rawmeter

import android.graphics.*

/** One image/overlay contract for confirmation and history. The JPEG itself stays unpainted. */
internal object RecordedPreviewRenderer {
    fun draw(canvas: Canvas, bounds: RectF, bitmap: Bitmap, viewfinder: RecordedViewfinder?,
        points: List<RecordedZonePoint>, density: Float, showFrame: Boolean = true): RectF {
        val scale = minOf(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
        val rect = RectF(bounds.centerX() - bitmap.width * scale / 2, bounds.centerY() - bitmap.height * scale / 2,
            bounds.centerX() + bitmap.width * scale / 2, bounds.centerY() + bitmap.height * scale / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        ViewfinderOverlay.clip(canvas, rect, density)
        canvas.drawBitmap(bitmap, null, rect, paint)
        if (showFrame) viewfinder?.frameCrop?.let { ViewfinderOverlay.framing(canvas, rect, it, density) }
        points.forEach { point ->
            if (point.normalizedX !in 0f..1f || point.normalizedY !in 0f..1f) return@forEach
            val x = rect.left + rect.width() * point.normalizedX
            val y = rect.top + rect.height() * point.normalizedY
            paint.color = 0xCC9E202A.toInt(); paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, 9f * density, paint)
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = 8f * density
            canvas.drawText(point.id.toString(), x, y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2, paint)
        }
        canvas.restore()
        return rect
    }

    fun load(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
