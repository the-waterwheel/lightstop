package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Size-derived geometry shared by drawing and hit testing; no device resolution is assumed. */
internal data class DepthOfFieldGeometry(
    val headerBack: RectF,
    val headerClose: RectF,
    val ruler: RectF,
    val frameControl: RectF,
    val cocControl: RectF,
    val apertureDial: RectF,
    val distanceDial: RectF,
) {
    companion object {
        val EMPTY = DepthOfFieldGeometry(
            RectF(),
            RectF(),
            RectF(),
            RectF(),
            RectF(),
            RectF(),
            RectF(),
        )
    }
}

internal object DepthOfFieldGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float): DepthOfFieldGeometry {
        if (width <= 0 || height <= 0) return DepthOfFieldGeometry.EMPTY
        val w = width.toFloat()
        val h = height.toFloat()
        val shortest = min(w, h)
        val pad = max(8f * density, shortest * 0.018f)
        val headerHeight = (h * 0.12f).coerceIn(44f * density, 58f * density)
        val headerTarget = min(52f * density, w * 0.16f).coerceAtLeast(44f * density)
        val headerBack = RectF(pad, 0f, pad + headerTarget, headerHeight)
        val headerClose = RectF(w - pad - headerTarget, 0f, w - pad, headerHeight)

        val availableHeight = (h - headerHeight).coerceAtLeast(1f)
        val rulerTop = headerHeight + max(6f * density, availableHeight * 0.018f)
        val rulerMinHeight = min(96f * density, availableHeight * 0.32f)
        val rulerMaxHeight = max(rulerMinHeight, availableHeight * 0.52f)
        val rulerHeight = (availableHeight * 0.40f).coerceIn(rulerMinHeight, rulerMaxHeight)
        val rulerInset = max(pad * 1.35f, w * 0.025f)
        val ruler = RectF(rulerInset, rulerTop, w - rulerInset, rulerTop + rulerHeight)

        val controlsTop = ruler.bottom + max(4f * density, availableHeight * 0.012f)
        val controlsHeight = (h - controlsTop).coerceAtLeast(1f)
        val centerWidth = (w * 0.29f)
            .coerceAtLeast(min(88f * density, w * 0.36f))
            .coerceAtMost(min(156f * density, w * 0.40f))
        val centerLeft = w / 2f - centerWidth / 2f
        val frameHeight = (controlsHeight * 0.43f)
            .coerceIn(min(46f * density, controlsHeight), min(72f * density, controlsHeight))
        val frame = RectF(centerLeft, controlsTop, centerLeft + centerWidth, controlsTop + frameHeight)
        val controlGap = max(5f * density, controlsHeight * 0.025f)
        val remainingForCoc = (h - frame.bottom - controlGap - pad).coerceAtLeast(1f)
        val cocHeight = min(44f * density, remainingForCoc)
        val coc = RectF(centerLeft, frame.bottom + controlGap, centerLeft + centerWidth, frame.bottom + controlGap + cocHeight)

        return DepthOfFieldGeometry(
            headerBack = headerBack,
            headerClose = headerClose,
            ruler = ruler,
            frameControl = frame,
            cocControl = coc,
            apertureDial = RectF(0f, controlsTop, w * 0.37f, h),
            distanceDial = RectF(w * 0.63f, controlsTop, w, h),
        )
    }
}
