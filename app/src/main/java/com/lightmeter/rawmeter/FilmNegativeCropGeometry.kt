package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal data class FilmNegativePoint(val x: Float, val y: Float)

/** Pixel-space rectangle: rotation must use the image's physical aspect, not normalized axes. */
internal data class FilmNegativeCrop(val centerX: Float, val centerY: Float,
    val width: Float, val height: Float, val angle: Float = 0f) {
    fun point(x: Float, y: Float): FilmNegativePoint {
        val c = cos(angle); val s = sin(angle)
        return FilmNegativePoint(centerX + c * x - s * y, centerY + s * x + c * y)
    }
    fun local(x: Float, y: Float): FilmNegativePoint {
        val c = cos(angle); val s = sin(angle)
        return FilmNegativePoint(c * (x - centerX) + s * (y - centerY),
            -s * (x - centerX) + c * (y - centerY))
    }
    fun corners() = listOf(point(-width / 2, -height / 2), point(width / 2, -height / 2),
        point(width / 2, height / 2), point(-width / 2, height / 2))
    fun region(imageWidth: Int, imageHeight: Int): FilmNegativeRegion {
        val points = corners().map { FilmNegativePoint((it.x / imageWidth).coerceIn(0f, 1f),
            (it.y / imageHeight).coerceIn(0f, 1f)) }
        return FilmNegativeRegion(points.minOf { it.x }, points.minOf { it.y },
            points.maxOf { it.x }, points.maxOf { it.y }, points)
    }
    fun contains(x: Float, y: Float): Boolean = local(x, y).let {
        abs(it.x) <= width / 2 && abs(it.y) <= height / 2
    }
    fun fits(imageWidth: Float, imageHeight: Float, minimum: Float): Boolean =
        listOf(centerX, centerY, width, height, angle).all { it.isFinite() } &&
            width >= minimum && height >= minimum && corners().all {
                it.x >= -.001f && it.y >= -.001f && it.x <= imageWidth + .001f && it.y <= imageHeight + .001f
            }
    fun move(dx: Float, dy: Float, imageWidth: Float, imageHeight: Float): FilmNegativeCrop {
        val points = corners()
        return copy(centerX = centerX + dx.coerceIn(-points.minOf { it.x }, imageWidth - points.maxOf { it.x }),
            centerY = centerY + dy.coerceIn(-points.minOf { it.y }, imageHeight - points.maxOf { it.y }))
    }
    /** Side signs: -1 left/top, +1 right/bottom, 0 leaves that dimension unchanged. */
    fun resize(dx: Float, dy: Float, sideX: Int, sideY: Int, minimum: Float): FilmNegativeCrop {
        val c = cos(angle); val s = sin(angle)
        val nextWidth = if (sideX == 0) width else max(minimum, width + sideX * (c * dx + s * dy))
        val nextHeight = if (sideY == 0) height else max(minimum, height + sideY * (-s * dx + c * dy))
        val center = point(sideX * (nextWidth - width) / 2, sideY * (nextHeight - height) / 2)
        return copy(centerX = center.x, centerY = center.y, width = nextWidth, height = nextHeight)
    }
    /** Clamp an edit without moving its fixed opposite edge/corner or distorting the rectangle. */
    fun bounded(candidate: FilmNegativeCrop, imageWidth: Float, imageHeight: Float, minimum: Float): FilmNegativeCrop {
        if (candidate.fits(imageWidth, imageHeight, minimum)) return candidate
        var low = 0f; var high = 1f
        repeat(24) {
            val t = (low + high) / 2
            if (interpolate(candidate, t).fits(imageWidth, imageHeight, minimum)) low = t else high = t
        }
        return interpolate(candidate, low)
    }
    private fun interpolate(other: FilmNegativeCrop, t: Float) = FilmNegativeCrop(
        centerX + (other.centerX - centerX) * t, centerY + (other.centerY - centerY) * t,
        width + (other.width - width) * t, height + (other.height - height) * t,
        angle + (other.angle - angle) * t)

    /** Corners win over edges; the remainder of the inside moves, and the outside rotates. */
    fun hit(x: Float, y: Float, radius: Float): FilmNegativeCropHandle {
        val points = corners()
        val targetRadius = min(radius, min(width, height) / 4)
        points.indices.minByOrNull { hypot(x - points[it].x, y - points[it].y) }?.let { i ->
            if (hypot(x - points[i].x, y - points[i].y) <= targetRadius) return FilmNegativeCropHandle.corners[i]
        }
        for (i in points.indices) {
            val a = points[i]; val b = points[(i + 1) % 4]
            val dx = b.x - a.x; val dy = b.y - a.y
            val t = ((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy)
            if (t in 0f..1f && hypot(x - a.x - t * dx, y - a.y - t * dy) <= targetRadius)
                return FilmNegativeCropHandle.edges[i]
        }
        return if (contains(x, y)) FilmNegativeCropHandle.MOVE else FilmNegativeCropHandle.ROTATE
    }
    companion object {
        fun from(region: FilmNegativeRegion, imageWidth: Int, imageHeight: Int): FilmNegativeCrop {
            val points = region.corners
            if (points != null && points.size == 4) {
                val a = points[0]; val b = points[1]; val d = points[3]
                return FilmNegativeCrop(points.map { it.x }.average().toFloat() * imageWidth,
                    points.map { it.y }.average().toFloat() * imageHeight,
                    hypot((b.x - a.x) * imageWidth, (b.y - a.y) * imageHeight),
                    hypot((d.x - a.x) * imageWidth, (d.y - a.y) * imageHeight),
                    atan2((b.y - a.y) * imageHeight, (b.x - a.x) * imageWidth))
            }
            return FilmNegativeCrop((region.left + region.right) * imageWidth / 2,
                (region.top + region.bottom) * imageHeight / 2,
                (region.right - region.left) * imageWidth, (region.bottom - region.top) * imageHeight)
        }
        fun angleDelta(from: Float, to: Float): Float = atan2(sin(to - from), cos(to - from))
    }
}

internal enum class FilmNegativeCropHandle(val sideX: Int = 0, val sideY: Int = 0) {
    TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_RIGHT(1, 1), BOTTOM_LEFT(-1, 1),
    TOP(0, -1), RIGHT(1, 0), BOTTOM(0, 1), LEFT(-1, 0), MOVE, ROTATE;
    companion object {
        val corners = listOf(TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT)
        val edges = listOf(TOP, RIGHT, BOTTOM, LEFT)
    }
}
