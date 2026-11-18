package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Renders the photographed sticker and its nearby rim against the card's own background. */
internal class FilmReelArtwork {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: Canvas, image: Bitmap, bounds: RectF, id: String): RectF? {
        val face = faces[id] ?: return null
        val centerX = image.width * face.centerX
        val centerY = image.height * face.centerY
        val radiusX = image.width * face.radiusX
        val radiusY = image.height * face.radiusY
        val radians = Math.toRadians(face.clockwiseDegrees.toDouble())
        val rotatedHalfWidth = hypot(radiusX * cos(radians), radiusY * sin(radians)).toFloat()
        val rotatedHalfHeight = hypot(radiusX * sin(radians), radiusY * cos(radians)).toFloat()
        val scale = minOf(bounds.width() / (2f * rotatedHalfWidth), bounds.height() / (2f * rotatedHalfHeight))
        val x = bounds.centerX()
        val y = bounds.centerY()
        val shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply { setTranslate(-centerX, -centerY) })
        paint.shader = shader

        canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.rotate(face.clockwiseDegrees)
        canvas.clipRect(-centerX, -centerY, image.width - centerX, image.height - centerY)
        canvas.drawOval(-radiusX, -radiusY, radiusX, radiusY, paint)
        canvas.restore()
        paint.shader = null

        val halfWidth = rotatedHalfWidth * scale
        val halfHeight = rotatedHalfHeight * scale
        return RectF(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
    }

    private data class Face(
        val centerX: Float,
        val centerY: Float,
        val radiusX: Float,
        val radiusY: Float,
        val clockwiseDegrees: Float = 0f,
    )

    private companion object {
        // The face geometry is measured on the bundled artwork. White printed labels are inside it.
        val faces = mapOf(
            "builtin-1" to Face(0.501f, 0.5f, 0.496f, 0.495f, 5f),      // Double-X
            "builtin-38" to Face(0.502f, 0.5f, 0.315f, 0.311f, 8f),     // Vision2 50D
            "builtin-41" to Face(0.5f, 0.499f, 0.495f, 0.496f),         // Vision2 500T
            "builtin-43" to Face(0.5f, 0.499f, 0.495f, 0.495f, 7f),     // Vision3 200T
            "builtin-44" to Face(0.5f, 0.481f, 0.495f, 0.515f),         // Vision3 250D, top edge trimmed
            "builtin-45" to Face(0.499f, 0.499f, 0.494f, 0.495f, 7f),   // Vision3 500T
            "builtin-104" to Face(0.497f, 0.558f, 0.495f, 0.55f),       // Fomapan Cine 100, bottom edge trimmed
            "builtin-106" to Face(0.494f, 0.389f, 0.348f, 0.317f),      // Foma Cine Ortho 400
            "builtin-107" to Face(0.499f, 0.492f, 0.345f, 0.376f),      // Foma Ortho 400
            "builtin-135" to Face(0.496f, 0.504f, 0.447f, 0.442f),      // ORWO N74 Plus
            "builtin-136" to Face(0.499f, 0.495f, 0.449f, 0.449f),      // ORWO UN54
        )
    }
}
