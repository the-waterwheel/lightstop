package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.min

data class LayoutGeometry(
    val landscape: Boolean,
    val previewPanel: RectF,
    val cameraFrame: RectF,
    val zoomTrack: RectF,
    val formatButton: RectF,
    val orientationButton: RectF,
    val apertureRow: RectF,
    val shutterRow: RectF,
    val exposureLockTrack: RectF,
    val dial: RectF,
    val isoModeButton: RectF,
    val meterButton: RectF,
) {
    companion object {
        fun calculate(
            width: Int,
            height: Int,
            density: Float,
            format: FrameFormat,
            frameLandscape: Boolean,
        ): LayoutGeometry {
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 10f * density
            val gap = 8f * density
            val isLandscape = w > h

            val previewPanel: RectF
            val controlsPanel: RectF
            if (isLandscape) {
                val divider = w * 0.60f
                previewPanel = RectF(pad, pad, divider - gap, h - pad)
                controlsPanel = RectF(divider + gap, pad, w - pad, h - pad)
            } else {
                val previewBottom = h * 0.58f
                previewPanel = RectF(pad, pad, w - pad, previewBottom)
                controlsPanel = RectF(pad, previewBottom + gap, w - pad, h - pad)
            }

            val buttonSize = 38f * density
            val zoomWidth = 34f * density
            // Keep the requested offset physical-size independent across screen densities.
            val meterButtonNudge = 10f * density
            val frameArea = RectF(
                previewPanel.left + gap,
                previewPanel.top + gap,
                previewPanel.right - zoomWidth - gap,
                previewPanel.bottom - gap,
            )
            val screenAspect = if (frameLandscape) {
                format.landscapeAspect
            } else {
                1f / format.landscapeAspect
            }
            val cameraFrame = fitAspect(frameArea, screenAspect)
            val zoomTrack = RectF(
                previewPanel.right - zoomWidth,
                previewPanel.top + buttonSize + gap * 2f,
                previewPanel.right - gap,
                previewPanel.bottom - buttonSize * 0.25f,
            )
            val formatButton = RectF(
                previewPanel.left + gap,
                previewPanel.top + gap,
                previewPanel.left + gap + buttonSize * 2.2f,
                previewPanel.top + gap + buttonSize,
            )
            val orientationButton = RectF(
                previewPanel.right - gap - buttonSize,
                previewPanel.top + gap,
                previewPanel.right - gap,
                previewPanel.top + gap + buttonSize,
            )

            val rowHeight: Float
            val apertureRow: RectF
            val shutterRow: RectF
            val dial: RectF
            val meterButton: RectF
            if (isLandscape) {
                rowHeight = controlsPanel.height() * 0.16f
                apertureRow = RectF(
                    controlsPanel.left,
                    controlsPanel.top + gap,
                    controlsPanel.right,
                    controlsPanel.top + gap + rowHeight,
                )
                shutterRow = RectF(
                    controlsPanel.left,
                    apertureRow.bottom + gap,
                    controlsPanel.right,
                    apertureRow.bottom + gap + rowHeight,
                )
                val lower = RectF(
                    controlsPanel.left,
                    shutterRow.bottom + gap,
                    controlsPanel.right,
                    controlsPanel.bottom,
                )
                val dialSize = min(lower.height(), lower.width() * 0.62f)
                dial = RectF(
                    lower.left,
                    lower.centerY() - dialSize / 2f,
                    lower.left + dialSize,
                    lower.centerY() + dialSize / 2f,
                )
                val meterWidth = lower.width() - dialSize - gap
                val meterSize = min(lower.height() * 0.56f, meterWidth * 0.86f)
                meterButton = RectF(
                    lower.right - meterSize,
                    lower.centerY() - meterSize / 2f - meterButtonNudge,
                    lower.right,
                    lower.centerY() + meterSize / 2f - meterButtonNudge,
                )
            } else {
                rowHeight = controlsPanel.height() * 0.20f
                apertureRow = RectF(
                    controlsPanel.left,
                    controlsPanel.top,
                    controlsPanel.right,
                    controlsPanel.top + rowHeight,
                )
                shutterRow = RectF(
                    controlsPanel.left,
                    apertureRow.bottom + gap * 0.5f,
                    controlsPanel.right,
                    apertureRow.bottom + gap * 0.5f + rowHeight,
                )
                val lower = RectF(
                    controlsPanel.left,
                    shutterRow.bottom + gap,
                    controlsPanel.right,
                    controlsPanel.bottom,
                )
                val dialSize = min(lower.height(), lower.width() * 0.62f)
                dial = RectF(
                    lower.left,
                    lower.centerY() - dialSize / 2f,
                    lower.left + dialSize,
                    lower.centerY() + dialSize / 2f,
                )
                val meterSize = min(lower.height() * 0.56f, lower.width() * 0.28f)
                meterButton = RectF(
                    lower.right - meterSize - meterButtonNudge,
                    lower.centerY() - meterSize / 2f,
                    lower.right - meterButtonNudge,
                    lower.centerY() + meterSize / 2f,
                )
            }

            val lockWidth = 38f * density
            val exposureLockTrack = RectF(
                apertureRow.right - lockWidth,
                apertureRow.top,
                apertureRow.right,
                shutterRow.bottom,
            )
            val isoButtonSize = min(36f * density, dial.width() * 0.22f)
            val isoModeButton = RectF(
                dial.right - isoButtonSize * 0.65f,
                dial.bottom - isoButtonSize * 0.85f,
                dial.right + isoButtonSize * 0.35f,
                dial.bottom + isoButtonSize * 0.15f,
            )

            return LayoutGeometry(
                landscape = isLandscape,
                previewPanel = previewPanel,
                cameraFrame = cameraFrame,
                zoomTrack = zoomTrack,
                formatButton = formatButton,
                orientationButton = orientationButton,
                apertureRow = apertureRow,
                shutterRow = shutterRow,
                exposureLockTrack = exposureLockTrack,
                dial = dial,
                isoModeButton = isoModeButton,
                meterButton = meterButton,
            )
        }

        private fun fitAspect(bounds: RectF, aspect: Float): RectF {
            val boundsAspect = bounds.width() / bounds.height()
            return if (boundsAspect > aspect) {
                val width = bounds.height() * aspect
                RectF(
                    bounds.centerX() - width / 2f,
                    bounds.top,
                    bounds.centerX() + width / 2f,
                    bounds.bottom,
                )
            } else {
                val height = bounds.width() / aspect
                RectF(
                    bounds.left,
                    bounds.centerY() - height / 2f,
                    bounds.right,
                    bounds.centerY() + height / 2f,
                )
            }
        }
    }
}
