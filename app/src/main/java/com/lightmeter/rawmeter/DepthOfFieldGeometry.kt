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
    val autoDistanceButton: RectF,
    val autoDistanceLabel: RectF,
    val autoDistanceHelp: RectF,
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
        val selectorGap = max(5f * density, controlsHeight * 0.025f)
        val selectorWidth = min(100f * density, w * 0.26f)
        val selectorHeight = min(38f * density, (controlsHeight - selectorGap) / 2f)
            .coerceAtLeast(min(32f * density, controlsHeight / 2f))
        val selectorLeft = (w - selectorWidth) / 2f
        val frame = RectF(selectorLeft, controlsTop, selectorLeft + selectorWidth, controlsTop + selectorHeight)
        val coc = RectF(selectorLeft, frame.bottom + selectorGap, selectorLeft + selectorWidth, frame.bottom + selectorGap + selectorHeight)

        val dialAvailableHeight = (h - controlsTop - pad).coerceAtLeast(1f)
        // Reserve a real row above the focal-length dial. On short portrait tool panels the old
        // dial consumed every remaining pixel, which would put the automatic-distance controls
        // directly on top of the dial instead of above it.
        val automaticDistanceRowReserve = 37f * density
        val dialHeightBudget = (dialAvailableHeight - automaticDistanceRowReserve)
            .coerceAtLeast(1f)
        val dialSize = min(min(w * 0.33f, 132f * density), dialHeightBudget)
            .coerceAtLeast(min(64f * density, dialHeightBudget))
        val dialTop = h - pad - dialSize
        val apertureDial = RectF(pad, dialTop, pad + dialSize, dialTop + dialSize)
        val focalDial = RectF(w - pad - dialSize, dialTop, w - pad, dialTop + dialSize)
        val autoButtonSize = min(30f * density, dialSize * 0.28f).coerceAtLeast(24f * density)
        val autoHelpSize = min(24f * density, autoButtonSize)
        val autoLabelWidth = min(72f * density, w * 0.18f)
        val autoGap = 5f * density
        val autoRowWidth = autoButtonSize + autoGap + autoLabelWidth + autoGap + autoHelpSize
        val autoRowLeft = (focalDial.centerX() - autoRowWidth / 2f)
            .coerceIn(pad, (w - pad - autoRowWidth).coerceAtLeast(pad))
        val autoRowTop = (focalDial.top - autoButtonSize - 7f * density)
            .coerceAtLeast(controlsTop)
        val autoDistanceButton = RectF(
            autoRowLeft,
            autoRowTop,
            autoRowLeft + autoButtonSize,
            autoRowTop + autoButtonSize,
        )
        val autoDistanceLabel = RectF(
            autoDistanceButton.right + autoGap,
            autoRowTop,
            autoDistanceButton.right + autoGap + autoLabelWidth,
            autoRowTop + autoButtonSize,
        )
        val autoDistanceHelp = RectF(
            autoDistanceLabel.right + autoGap,
            autoRowTop + (autoButtonSize - autoHelpSize) / 2f,
            autoDistanceLabel.right + autoGap + autoHelpSize,
            autoRowTop + (autoButtonSize + autoHelpSize) / 2f,
        )

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
            autoDistanceButton = autoDistanceButton,
            autoDistanceLabel = autoDistanceLabel,
            autoDistanceHelp = autoDistanceHelp,
        )
    }
}
