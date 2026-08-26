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
    val moreButton: RectF,
    val toolsButton: RectF,
    val zoneEntryHandle: RectF,
    val apertureRow: RectF,
    val shutterRow: RectF,
    val exposureLockTrack: RectF,
    val dial: RectF,
    val isoModeButton: RectF,
    val ev100Badge: RectF,
    val meterButton: RectF,
) {
    companion object {
        fun calculate(
            width: Int,
            height: Int,
            density: Float,
            format: FrameFormat,
            frameLandscape: Boolean,
            leftHanded: Boolean,
        ): LayoutGeometry {
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 10f * density
            val gap = 8f * density
            val isLandscape = w > h

            val previewPanel: RectF
            val controlsPanel: RectF
            if (isLandscape) {
                if (leftHanded) {
                    val divider = w * 0.40f
                    controlsPanel = RectF(pad, pad, divider - gap, h - pad)
                    previewPanel = RectF(divider + gap, pad, w - pad, h - pad)
                } else {
                    val divider = w * 0.60f
                    previewPanel = RectF(pad, pad, divider - gap, h - pad)
                    controlsPanel = RectF(divider + gap, pad, w - pad, h - pad)
                }
            } else {
                val previewBottom = h * 0.58f
                previewPanel = RectF(pad, pad, w - pad, previewBottom)
                controlsPanel = RectF(pad, previewBottom + gap, w - pad, h - pad)
            }

            val buttonSize = 38f * density
            val zoomWidth = 34f * density
            // Keep the requested offset physical-size independent across screen densities.
            val meterButtonNudge = 10f * density
            val frameArea = if (leftHanded) {
                RectF(
                    previewPanel.left + zoomWidth + gap,
                    previewPanel.top + gap,
                    previewPanel.right - gap,
                    previewPanel.bottom - gap,
                )
            } else {
                RectF(
                    previewPanel.left + gap,
                    previewPanel.top + gap,
                    previewPanel.right - zoomWidth - gap,
                    previewPanel.bottom - gap,
                )
            }
            val screenAspect = if (frameLandscape) {
                format.landscapeAspect
            } else {
                1f / format.landscapeAspect
            }
            val cameraFrame = fitAspect(frameArea, screenAspect)
            val zoomTrack = if (leftHanded) {
                RectF(
                    previewPanel.left + gap,
                    previewPanel.top + buttonSize + gap * 2f,
                    previewPanel.left + zoomWidth,
                    previewPanel.bottom - buttonSize * 0.25f,
                )
            } else {
                RectF(
                    previewPanel.right - zoomWidth,
                    previewPanel.top + buttonSize + gap * 2f,
                    previewPanel.right - gap,
                    previewPanel.bottom - buttonSize * 0.25f,
                )
            }
            val formatButton = if (leftHanded) {
                RectF(
                    previewPanel.right - gap - buttonSize * 2.2f,
                    previewPanel.top + gap,
                    previewPanel.right - gap,
                    previewPanel.top + gap + buttonSize,
                )
            } else {
                RectF(
                    previewPanel.left + gap,
                    previewPanel.top + gap,
                    previewPanel.left + gap + buttonSize * 2.2f,
                    previewPanel.top + gap + buttonSize,
                )
            }
            val orientationButton = if (leftHanded) {
                RectF(
                    previewPanel.left + gap,
                    previewPanel.top + gap,
                    previewPanel.left + gap + buttonSize,
                    previewPanel.top + gap + buttonSize,
                )
            } else {
                RectF(
                    previewPanel.right - gap - buttonSize,
                    previewPanel.top + gap,
                    previewPanel.right - gap,
                    previewPanel.top + gap + buttonSize,
                )
            }
            val moreButton = RectF(
                previewPanel.left + gap,
                previewPanel.bottom - gap - buttonSize,
                previewPanel.left + gap + buttonSize,
                previewPanel.bottom - gap,
            )
            val toolsButton = RectF(
                moreButton.right + gap,
                moreButton.top,
                moreButton.right + gap + buttonSize,
                moreButton.bottom,
            )
            val zoneHandleLongSide = 76f * density
            val zoneHandleShortSide = 22f * density
            val zoneEntryHandle = if (isLandscape) {
                val zoneHandleHeight = min(zoneHandleLongSide, zoomTrack.height() * 0.34f)
                val left = if (leftHanded) {
                    zoomTrack.right + gap
                } else {
                    zoomTrack.left - gap - zoneHandleShortSide
                }
                RectF(
                    left,
                    zoomTrack.centerY() - zoneHandleHeight / 2f,
                    left + zoneHandleShortSide,
                    zoomTrack.centerY() + zoneHandleHeight / 2f,
                )
            } else {
                RectF(
                    previewPanel.centerX() - zoneHandleLongSide / 2f,
                    previewPanel.bottom - gap - zoneHandleShortSide,
                    previewPanel.centerX() + zoneHandleLongSide / 2f,
                    previewPanel.bottom - gap,
                )
            }

            val rowHeight: Float
            val apertureRow: RectF
            val shutterRow: RectF
            val dial: RectF
            val meterButton: RectF
            val ev100Badge: RectF
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
                dial = if (leftHanded) {
                    RectF(
                        lower.right - dialSize,
                        lower.centerY() - dialSize / 2f,
                        lower.right,
                        lower.centerY() + dialSize / 2f,
                    )
                } else {
                    RectF(
                        lower.left,
                        lower.centerY() - dialSize / 2f,
                        lower.left + dialSize,
                        lower.centerY() + dialSize / 2f,
                    )
                }
                val actionArea = if (leftHanded) {
                    RectF(lower.left, lower.top, dial.left - gap, lower.bottom)
                } else {
                    RectF(dial.right + gap, lower.top, lower.right, lower.bottom)
                }
                val badgeGap = 5f * density
                val meterSize = min(
                    lower.height() * 0.56f,
                    (actionArea.width() - badgeGap).coerceAtLeast(1f) * 0.62f,
                )
                val badgeSize = min(52f * density, meterSize).coerceAtMost(
                    (actionArea.width() - meterSize - badgeGap).coerceAtLeast(34f * density),
                )
                val groupWidth = badgeSize + badgeGap + meterSize
                val groupLeft = (actionArea.centerX() - groupWidth / 2f)
                    .coerceIn(actionArea.left, (actionArea.right - groupWidth).coerceAtLeast(actionArea.left))
                ev100Badge = RectF(
                    groupLeft,
                    lower.centerY() - badgeSize / 2f - meterButtonNudge,
                    groupLeft + badgeSize,
                    lower.centerY() + badgeSize / 2f - meterButtonNudge,
                )
                meterButton = RectF(
                    ev100Badge.right + badgeGap,
                    lower.centerY() - meterSize / 2f - meterButtonNudge,
                    ev100Badge.right + badgeGap + meterSize,
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
                dial = if (leftHanded) {
                    RectF(
                        lower.right - dialSize,
                        lower.centerY() - dialSize / 2f,
                        lower.right,
                        lower.centerY() + dialSize / 2f,
                    )
                } else {
                    RectF(
                        lower.left,
                        lower.centerY() - dialSize / 2f,
                        lower.left + dialSize,
                        lower.centerY() + dialSize / 2f,
                    )
                }
                val actionArea = if (leftHanded) {
                    RectF(lower.left, lower.top, dial.left - gap, lower.bottom)
                } else {
                    RectF(dial.right + gap, lower.top, lower.right, lower.bottom)
                }
                val badgeGap = 5f * density
                val meterSize = min(
                    lower.height() * 0.56f,
                    (actionArea.width() - badgeGap).coerceAtLeast(1f) * 0.62f,
                )
                val badgeSize = min(52f * density, meterSize).coerceAtMost(
                    (actionArea.width() - meterSize - badgeGap).coerceAtLeast(34f * density),
                )
                val groupWidth = badgeSize + badgeGap + meterSize
                val groupLeft = (actionArea.centerX() - groupWidth / 2f)
                    .coerceIn(actionArea.left, (actionArea.right - groupWidth).coerceAtLeast(actionArea.left))
                ev100Badge = RectF(
                    groupLeft,
                    lower.centerY() - badgeSize / 2f,
                    groupLeft + badgeSize,
                    lower.centerY() + badgeSize / 2f,
                )
                meterButton = RectF(
                    ev100Badge.right + badgeGap,
                    lower.centerY() - meterSize / 2f,
                    ev100Badge.right + badgeGap + meterSize,
                    lower.centerY() + meterSize / 2f,
                )
            }

            val lockWidth = 38f * density
            val exposureLockTrack = if (leftHanded) {
                RectF(
                    apertureRow.left,
                    apertureRow.top,
                    apertureRow.left + lockWidth,
                    shutterRow.bottom,
                )
            } else {
                RectF(
                    apertureRow.right - lockWidth,
                    apertureRow.top,
                    apertureRow.right,
                    shutterRow.bottom,
                )
            }
            val isoButtonSize = min(36f * density, dial.width() * 0.22f)
            val isoModeButton = if (leftHanded) {
                RectF(
                    dial.left - isoButtonSize * 0.35f,
                    dial.bottom - isoButtonSize * 0.85f,
                    dial.left + isoButtonSize * 0.65f,
                    dial.bottom + isoButtonSize * 0.15f,
                )
            } else {
                RectF(
                    dial.right - isoButtonSize * 0.65f,
                    dial.bottom - isoButtonSize * 0.85f,
                    dial.right + isoButtonSize * 0.35f,
                    dial.bottom + isoButtonSize * 0.15f,
                )
            }

            return LayoutGeometry(
                landscape = isLandscape,
                previewPanel = previewPanel,
                cameraFrame = cameraFrame,
                zoomTrack = zoomTrack,
                formatButton = formatButton,
                orientationButton = orientationButton,
                moreButton = moreButton,
                toolsButton = toolsButton,
                zoneEntryHandle = zoneEntryHandle,
                apertureRow = apertureRow,
                shutterRow = shutterRow,
                exposureLockTrack = exposureLockTrack,
                dial = dial,
                isoModeButton = isoModeButton,
                ev100Badge = ev100Badge,
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
