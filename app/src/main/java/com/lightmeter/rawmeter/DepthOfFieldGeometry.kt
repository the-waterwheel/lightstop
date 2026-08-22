package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Size-derived geometry shared by drawing and hit testing; no device resolution is assumed. */
internal data class DepthOfFieldGeometry(
    val headerBack: RectF,
    val headerClose: RectF,
    val ruler: RectF,
    val rulerTrack: RectF,
    val focusValue: RectF,
    val frameControl: RectF,
    val cocControl: RectF,
    val apertureDial: RectF,
    val focalDial: RectF,
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
        val rulerHeight = (availableHeight * 0.43f).coerceIn(rulerMinHeight, rulerMaxHeight)
        val rulerInset = max(pad * 1.35f, w * 0.025f)
        val ruler = RectF(rulerInset, rulerTop, w - rulerInset, rulerTop + rulerHeight)
        val trackY = ruler.top + 24f * density
        val trackInset = 17f * density
        val rulerTrack = RectF(
            ruler.left + trackInset,
            trackY - 20f * density,
            ruler.right - trackInset,
            trackY + 20f * density,
        )
        val focusValue = RectF(
            ruler.centerX() - ruler.width() * 0.17f,
            ruler.top + ruler.height() * 0.50f,
            ruler.centerX() + ruler.width() * 0.17f,
            ruler.top + ruler.height() * 0.78f,
        )

        val controlsTop = ruler.bottom + max(4f * density, availableHeight * 0.012f)
        val controlsHeight = (h - controlsTop).coerceAtLeast(1f)
        val selectorGap = max(5f * density, w * 0.012f)
        val selectorWidth = min(142f * density, (w - pad * 2f - selectorGap) / 2f)
        val selectorHeight = min(42f * density, controlsHeight * 0.28f).coerceAtLeast(min(32f * density, controlsHeight))
        val selectorsWidth = selectorWidth * 2f + selectorGap
        val selectorLeft = (w - selectorsWidth) / 2f
        val frame = RectF(selectorLeft, controlsTop, selectorLeft + selectorWidth, controlsTop + selectorHeight)
        val coc = RectF(frame.right + selectorGap, controlsTop, frame.right + selectorGap + selectorWidth, controlsTop + selectorHeight)

        val dialAvailableHeight = (h - frame.bottom - pad - 5f * density).coerceAtLeast(1f)
        val dialSize = min(min(w * 0.275f, 112f * density), dialAvailableHeight)
            .coerceAtLeast(min(64f * density, dialAvailableHeight))
        val dialTop = h - pad - dialSize
        val apertureDial = RectF(pad, dialTop, pad + dialSize, dialTop + dialSize)
        val focalDial = RectF(w - pad - dialSize, dialTop, w - pad, dialTop + dialSize)

        return DepthOfFieldGeometry(
            headerBack = headerBack,
            headerClose = headerClose,
            ruler = ruler,
            rulerTrack = rulerTrack,
            focusValue = focusValue,
            frameControl = frame,
            cocControl = coc,
            apertureDial = apertureDial,
            focalDial = focalDial,
        )
    }
}
