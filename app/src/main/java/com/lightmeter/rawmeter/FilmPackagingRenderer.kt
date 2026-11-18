package com.lightmeter.rawmeter

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import java.io.IOException
import kotlin.math.roundToInt

internal class FilmPackagingRenderer(private val assets: AssetManager) {
    private val files by lazy { assets.list(DIRECTORY).orEmpty().toMutableSet() }
    private val images = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reelArtwork = FilmReelArtwork()
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = InstrumentStyle.labelTypeface
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(canvas: Canvas, bounds: RectF, film: FilmLatitudeProfile, surface: Int, density: Float) {
        val image = thumbnail(film.id)
        if (image == null) {
            drawPlaceholder(canvas, bounds, film, density)
            fadeRight(canvas, bounds, surface)
            return
        }
        val reel = reelArtwork.draw(canvas, image, bounds, film.id)
        if (reel != null) {
            fadeRight(canvas, reel, surface)
            return
        }

        val source = sourceRect(film.id, image.width, image.height, bounds.width() / bounds.height())
        val scale = minOf(bounds.width() / source.width(), bounds.height() / source.height())
        val halfWidth = source.width() * scale / 2f
        val halfHeight = source.height() * scale / 2f
        val destination = RectF(
            bounds.centerX() - halfWidth, bounds.centerY() - halfHeight,
            bounds.centerX() + halfWidth, bounds.centerY() + halfHeight,
        )
        if (film.id == "builtin-113") { // CineStill 50D
            canvas.save()
            canvas.rotate(180f, destination.centerX(), destination.centerY())
            canvas.drawBitmap(image, source, destination, imagePaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(image, source, destination, imagePaint)
        }
        fadeRight(canvas, destination, surface)
    }

    private fun thumbnail(id: String): Bitmap? {
        images.get(id)?.let { return it }
        val file = "$id.jpg"
        if (file !in files) return null
        val image = try {
            assets.open("$DIRECTORY/$file").use { BitmapFactory.decodeStream(it) }
        } catch (_: IOException) {
            null
        }
        if (image == null) files.remove(file) else images.put(id, image)
        return image
    }

    private fun drawPlaceholder(canvas: Canvas, bounds: RectF, film: FilmLatitudeProfile, density: Float) {
        fillPaint.color = Color.BLACK
        canvas.drawRect(bounds, fillPaint)
        val model = film.model.trim().ifBlank { film.displayName }
        val shortModel = if (film.custom) model else {
            genericModelWords.replace(model.substringBefore(" / "), "").trim()
        }
        val title = shortModel.ifBlank { model }
        val padding = 6f * density
        val width = (bounds.width() - 2f * padding).toInt().coerceAtLeast(1)
        labelPaint.textSize = minOf(12f * density, bounds.height() / 5f)
        val longestWord = title.split(' ').maxOf(labelPaint::measureText)
        if (longestWord > width) {
            labelPaint.textSize = (labelPaint.textSize * width / longestWord).coerceAtLeast(8.5f * density)
        }
        labelPaint.strokeWidth = 0.6f * density
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, labelPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(3)
            .build()
        canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(bounds.left + padding, bounds.centerY() - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun fadeRight(canvas: Canvas, bounds: RectF, surface: Int) {
        val start = bounds.right - bounds.width() * 0.14f
        fillPaint.shader = LinearGradient(
            start, 0f, bounds.right, 0f, surface and 0x00ffffff, surface, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(start, bounds.top, bounds.right, bounds.bottom, fillPaint)
        fillPaint.shader = null
    }

    private companion object {
        const val DIRECTORY = "film_packaging"
        val genericModelWords = Regex("""\b(?:professional|color reversal|panchromatic|film)\b *""", RegexOption.IGNORE_CASE)

        // The windows come from the corrected artwork. Wider labels keep their whole face.
        val horizontalNameWindows = mapOf(
            "builtin-10" to (0.42f to 1f),    // E100G
            "builtin-30" to (0f to 0.64f),    // Portra 160
            "builtin-36" to (0f to 0.86f),    // TRI-X 320
            "builtin-67" to (0f to 0.88f),    // Neopan Presto
            "builtin-189" to (0.48f to 1f),   // Reflx Lab 500T
            "builtin-205" to (0f to 0.69f),   // Centuria 400
            "builtin-233" to (0f to 0.62f),   // E100SW
            "builtin-245" to (0f to 0.84f),   // High Definition 400
            "builtin-254" to (1f / 3f to 2f / 3f), // One face of the repeated Gold 100 pack
        )
        val verticalNameWindows = mapOf(
            "builtin-120" to (0.48f to 0.78f),
            "builtin-124" to (0.59f to 0.98f),
            "builtin-125" to (0.56f to 0.98f),
            "builtin-126" to (0.53f to 0.96f),
            "builtin-133" to (0.51f to 0.97f),
            "builtin-134" to (0.24f to 0.67f),
        )

        fun sourceRect(id: String, width: Int, height: Int, artworkAspect: Float): Rect {
            horizontalNameWindows[id]?.let { (start, end) ->
                return Rect((width * start).roundToInt(), 0, (width * end).roundToInt(), height)
            }
            val (start, end) = verticalNameWindows[id] ?: return Rect(0, 0, width, height)
            // Fit the full printed name if it needs more height than a cover crop allows.
            val cropHeight = maxOf(width / artworkAspect, height * (end - start))
                .roundToInt().coerceIn(1, height)
            val center = height * (start + end) / 2f
            val top = (center - cropHeight / 2f).roundToInt().coerceIn(0, height - cropHeight)
            return Rect(0, top, width, top + cropHeight)
        }
    }
}
