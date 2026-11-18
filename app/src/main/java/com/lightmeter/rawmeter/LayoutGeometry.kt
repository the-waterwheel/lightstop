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
    val distanceRow: RectF = RectF(),
) {
    companion object {
        fun calculate(
            width: Int,
            height: Int,
            density: Float,
            leftHanded: Boolean,
            distanceProgress: Float = 0f,
            distanceBesideTools: Boolean = false,
        ): LayoutGeometry {
            if (width <= 0 || height <= 0) return LayoutGeometry(false,
                RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(),
                RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF())
            val density = AdaptiveLayout.density(width, height, density, LayoutProfile.METER)
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 10f * density
            val gap = 8f * density
            val isLandscape = w > h

            val previewPanel: RectF
            val distanceRow = RectF()
            val distanceOffset: Float
            val distanceGap = 4f * density * distanceProgress
            val controlsPanel: RectF
            if (isLandscape) {
                distanceOffset = min(METER_DISTANCE_ROW_DP * density, (h - 2 * pad) * .16f * .8f) * distanceProgress + distanceGap
                val extra = if (distanceBesideTools) 0f else 12f * density * distanceProgress
                val previewBottom = h - pad - if (distanceBesideTools) distanceOffset else extra / 2
                if (leftHanded) {
                    val divider = w * 0.40f + extra
                    controlsPanel = RectF(pad, pad, divider - gap, h - pad)
                    previewPanel = RectF(divider + gap, pad, w - pad, previewBottom)
                } else {
                    val divider = w * 0.60f - extra
                    previewPanel = RectF(pad, pad, divider - gap, previewBottom)
                    controlsPanel = RectF(divider + gap, pad, w - pad, h - pad)
                }
            } else {
                val previewBottom = h * 0.58f
                val exposureRowHeight = (h - pad - previewBottom - gap) * .20f
                distanceOffset = min(METER_DISTANCE_ROW_DP * density, exposureRowHeight * .8f) * distanceProgress + distanceGap
                previewPanel = RectF(pad, pad, w - pad, previewBottom - distanceOffset)
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
            val cameraFrame = RectF(frameArea)
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
            val settingsLeft = previewPanel.left + gap + if (leftHanded) zoomWidth else 0f
            val moreButton = RectF(
                settingsLeft,
                previewPanel.bottom - gap - buttonSize,
                settingsLeft + buttonSize,
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
                    h/2f - zoneHandleHeight / 2f,
                    left + zoneHandleShortSide,
                    h/2f + zoneHandleHeight / 2f,
                )
            } else {
                RectF(
                    previewPanel.centerX() - zoneHandleLongSide / 2f,
                    previewPanel.bottom - gap - zoneHandleShortSide,
                    previewPanel.centerX() + zoneHandleLongSide / 2f,
                    previewPanel.bottom - gap,
                )
            }

            val rowHeight = controlsPanel.height() * if (isLandscape) .16f else .20f
            val distanceAboveControls = isLandscape && !distanceBesideTools
            val rowsTop = controlsPanel.top + if (isLandscape) gap + (if (distanceAboveControls) distanceOffset else 0f) else 0f
            val apertureRow = RectF(controlsPanel.left, rowsTop, controlsPanel.right, rowsTop + rowHeight)
            val shutterTop = apertureRow.bottom + if (isLandscape) gap else gap / 2f
            val shutterRow = RectF(controlsPanel.left, shutterTop, controlsPanel.right, shutterTop + rowHeight)
            if (distanceProgress > 0f) {
                if (distanceAboveControls) {
                    distanceRow.set(apertureRow.left, rowsTop - distanceOffset, apertureRow.right, rowsTop - distanceGap)
                } else {
                    // End at the original preview boundary so a stationary tools panel cannot cover the ruler.
                    val inset = if (isLandscape) 0f else density * distanceProgress
                    val bottom = previewPanel.bottom + distanceOffset - inset
                    val left = if (isLandscape) cameraFrame.left else apertureRow.left
                    val right = if (isLandscape) cameraFrame.right else apertureRow.right
                    distanceRow.set(left, previewPanel.bottom + distanceGap - inset, right, bottom)
                }
            }

            val lower = RectF(controlsPanel.left, shutterRow.bottom + gap, controlsPanel.right, controlsPanel.bottom)
            val dialSize = min(lower.height(), lower.width() * .58f)
            val dialLeft = if (leftHanded) lower.right - dialSize else lower.left
            val dial = RectF(dialLeft, lower.centerY() - dialSize / 2, dialLeft + dialSize, lower.centerY() + dialSize / 2)
            val badgeGap = 6f * density
            val badgeWidth = min(44f * density, lower.width() * .19f)
            val meterSize = min(lower.height() * .56f, (lower.width() - dialSize - badgeWidth - 2 * badgeGap).coerceAtLeast(1f))
            val centerY = lower.centerY() - if (isLandscape) meterButtonNudge else 0f
            val badgeHeight = min(46f * density, lower.height() * .36f)
            val badgeLeft = if (leftHanded) lower.left else lower.right - badgeWidth
            val meterLeft = if (leftHanded) dial.left - badgeGap - meterSize else dial.right + badgeGap
            val ev100Badge = RectF(badgeLeft, centerY - badgeHeight / 2, badgeLeft + badgeWidth, centerY + badgeHeight / 2)
            val meterButton = RectF(meterLeft, centerY - meterSize / 2, meterLeft + meterSize, centerY + meterSize / 2)

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
                distanceRow = distanceRow,
            )
        }

    }
}
