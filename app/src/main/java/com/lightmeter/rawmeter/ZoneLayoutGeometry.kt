package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.min

/** Immutable hit-test and drawing geometry shared by every Zone-system interaction. */
internal data class ZoneLayoutGeometry(
    val landscape: Boolean,
    val previewPanel: RectF,
    val cameraFrame: RectF,
    val formatButton: RectF,
    val orientationButton: RectF,
    val settingsButton: RectF,
    val toolsButton: RectF,
    val zoomTrack: RectF,
    val normalHandle: RectF,
    val apertureRow: RectF,
    val shutterRow: RectF,
    val lockTrack: RectF,
    val zoneScale: RectF,
    val markerRail: RectF,
    val recordPanel: RectF,
    val recordViewport: RectF,
    val clearTrack: RectF,
    val clearHandle: RectF,
    val ev100Badge: RectF,
    val markButton: RectF,
    val distanceRow: RectF = RectF(),
    val auxiliaryRow: RectF = RectF(),
) {
    fun auxiliaryBadge(index: Int, leftHanded: Boolean): RectF {
        val slot = if (leftHanded) 2 - index else index
        val radius = auxiliaryRow.height() / 2
        val centerX = auxiliaryRow.left + auxiliaryRow.width() * (slot + .5f) / 3
        return RectF(centerX - radius, auxiliaryRow.top, centerX + radius, auxiliaryRow.bottom)
    }
}

/**
 * Calculates Zone layout without owning any View or gesture state.
 *
 * The ratios below are the existing visual design. Isolating them here gives future device
 * adaptation a single boundary: safe-area or screen-class inputs can be added without touching
 * rendering, metering, or gesture code.
 */
internal object ZoneLayoutCalculator {
    fun calculate(
        width: Int,
        height: Int,
        density: Float,
        state: MeterState,
        distanceProgress: Float = state.distanceDisplayProgress,
    ): ZoneLayoutGeometry {
        if (width <= 0 || height <= 0) return ZoneLayoutGeometry(false,
            RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(),
            RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(),
            RectF(), RectF(), RectF())
        val deviceDensity = density
        val density = AdaptiveLayout.density(width, height, density, LayoutProfile.METER)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f * density
        val gap = 6f * density
        val landscape = w > h
        val button = 34f * density
        val previewPanel: RectF
        val cameraFrame: RectF
        val formatButton: RectF
        val orientationButton: RectF
        val settingsButton: RectF
        val toolsButton: RectF
        val zoomTrack: RectF
        val normalHandle: RectF
        val apertureRow: RectF
        val shutterRow: RectF
        val lockTrack: RectF
        val zoneScale: RectF
        val markerRail: RectF
        var recordPanel: RectF
        val recordViewport: RectF
        val clearTrack: RectF
        val clearHandle: RectF
        val distanceRow = RectF()
        val markSize: Float
        val distanceGap = 4f * density * distanceProgress

        if (!landscape) {
            val rowHeight = h * .055f
            val distanceHeight = min(METER_DISTANCE_ROW_DP * density, rowHeight * .8f) * distanceProgress
            previewPanel = RectF(
                pad,
                pad,
                w - pad,
                h * .48f - distanceHeight - distanceGap,
            )
            val frameArea = RectF(
                previewPanel.left + gap,
                previewPanel.top + gap,
                previewPanel.right - 34f * density - gap,
                previewPanel.bottom - gap,
            )
            cameraFrame = RectF(frameArea)
            formatButton = RectF(
                previewPanel.left + gap,
                previewPanel.top + gap,
                previewPanel.left + gap + button * 2.1f,
                previewPanel.top + gap + button,
            )
            orientationButton = RectF(
                previewPanel.right - gap - button,
                previewPanel.top + gap,
                previewPanel.right - gap,
                previewPanel.top + gap + button,
            )
            // Anchored to the viewfinder panel corner like the normal-mode settings button,
            // so it never moves when the selected frame format changes the fitted image.
            val settingsLeft = previewPanel.left + gap
            settingsButton = RectF(
                settingsLeft,
                previewPanel.bottom - gap - button,
                settingsLeft + button,
                previewPanel.bottom - gap,
            )
            toolsButton = RectF(
                settingsButton.right + gap,
                settingsButton.top,
                settingsButton.right + gap + button,
                settingsButton.bottom,
            )
            zoomTrack = RectF(
                previewPanel.right - 30f * density,
                orientationButton.bottom + gap,
                previewPanel.right - gap,
                previewPanel.bottom - gap,
            )
            normalHandle = RectF(
                w/2f - 38f * density,
                previewPanel.bottom - 27f * density,
                w/2f + 38f * density,
                previewPanel.bottom - 7f * density,
            )

            val controlsTop = h * .48f + gap * 1.4f
            apertureRow = RectF(pad, controlsTop, w - pad, controlsTop + rowHeight)
            if (distanceProgress > 0f) {
                val inset = density * distanceProgress
                distanceRow.set(pad, previewPanel.bottom + distanceGap - inset, w - pad, h * .48f - inset)
            }
            shutterRow = RectF(
                pad,
                apertureRow.bottom + gap * 0.55f,
                w - pad,
                apertureRow.bottom + gap * 0.55f + rowHeight,
            )
            val lockWidth = 34f * density
            lockTrack = if (state.isLeftHanded) {
                RectF(apertureRow.left, apertureRow.top, apertureRow.left + lockWidth, shutterRow.bottom)
            } else {
                RectF(apertureRow.right - lockWidth, apertureRow.top, apertureRow.right, shutterRow.bottom)
            }
            zoneScale = RectF(
                pad,
                shutterRow.bottom + gap,
                w - pad,
                shutterRow.bottom + gap + h * 0.038f,
            )
            markerRail = RectF(
                pad,
                zoneScale.bottom + gap * 0.45f,
                w - pad,
                zoneScale.bottom + gap * 0.45f + h * 0.052f,
            )
            recordPanel = RectF(pad, markerRail.bottom + gap, w - pad, h - pad)
            val recordContentRight =
                recordPanel.left + recordPanel.width() * RECORD_CONTENT_FRACTION
            markSize = min(
                84f * density,
                min(recordPanel.width() * 0.24f, recordPanel.height() * 0.40f),
            )
            clearTrack = RectF(
                recordPanel.left + gap,
                recordPanel.top + gap,
                recordContentRight,
                recordPanel.top + 31f * density,
            )
            clearHandle = RectF(
                clearTrack.left,
                clearTrack.top,
                clearTrack.left + min(66f * density, clearTrack.width() * 0.34f),
                clearTrack.bottom,
            )
            recordViewport = RectF(
                recordPanel.left + gap,
                clearTrack.bottom + gap,
                recordContentRight,
                recordPanel.bottom - gap,
            )
        } else {
            val rowHeight = (h - 2 * pad) * .145f
            val distanceHeight = min(METER_DISTANCE_ROW_DP * density, rowHeight * .8f) * distanceProgress
            val besideTools = state.distanceBesideTools
            val extra = if (besideTools) 0f else 12f * density * distanceProgress
            val previewWidth = w * .49f - extra
            val previewBottom = h - pad - if (besideTools) distanceHeight + distanceGap else extra / 2
            previewPanel = if (state.isLeftHanded) {
                RectF(w - previewWidth + pad, pad, w - pad, previewBottom)
            } else {
                RectF(pad, pad, previewWidth, previewBottom)
            }
            val sideGap = 31f * density
            val frameArea = if (state.isLeftHanded) {
                RectF(
                    previewPanel.left + sideGap,
                    previewPanel.top + gap,
                    previewPanel.right - gap,
                    previewPanel.bottom - gap,
                )
            } else {
                RectF(
                    previewPanel.left + gap,
                    previewPanel.top + gap,
                    previewPanel.right - sideGap,
                    previewPanel.bottom - gap,
                )
            }
            cameraFrame = RectF(frameArea)
            formatButton = RectF(
                previewPanel.left + gap,
                previewPanel.top + gap,
                previewPanel.left + gap + button * 2.1f,
                previewPanel.top + gap + button,
            )
            orientationButton = RectF(
                previewPanel.right - gap - button,
                previewPanel.top + gap,
                previewPanel.right - gap,
                previewPanel.top + gap + button,
            )
            // Anchored to the viewfinder panel corner like the normal-mode settings button,
            // so it never moves when the selected frame format changes the fitted image.
            val settingsLeft = previewPanel.left + gap + if (state.isLeftHanded) sideGap else 0f
            settingsButton = RectF(
                settingsLeft,
                previewPanel.bottom - gap - button,
                settingsLeft + button,
                previewPanel.bottom - gap,
            )
            toolsButton = RectF(
                settingsButton.right + gap,
                settingsButton.top,
                settingsButton.right + gap + button,
                settingsButton.bottom,
            )
            zoomTrack = if (state.isLeftHanded) {
                RectF(
                    previewPanel.left + gap,
                    orientationButton.bottom + gap,
                    previewPanel.left + 28f * density,
                    previewPanel.bottom - gap,
                )
            } else {
                RectF(
                    previewPanel.right - 28f * density,
                    orientationButton.bottom + gap,
                    previewPanel.right - gap,
                    previewPanel.bottom - gap,
                )
            }
            val normalWidth = 22f * density
            val normalHeight = min(74f * density, zoomTrack.height() * 0.34f)
            val normalLeft = if (state.isLeftHanded) {
                zoomTrack.right + gap
            } else {
                zoomTrack.left - gap - normalWidth
            }
            normalHandle = RectF(
                normalLeft,
                h/2f - normalHeight / 2f,
                normalLeft + normalWidth,
                h/2f + normalHeight / 2f,
            )

            val scaleWidth = w * 0.045f
            val railWidth = w * 0.05f
            if (state.isLeftHanded) {
                zoneScale = RectF(
                    previewPanel.left - gap - scaleWidth,
                    pad,
                    previewPanel.left - gap,
                    h - pad,
                )
                markerRail = RectF(
                    zoneScale.left - gap - railWidth,
                    pad,
                    zoneScale.left - gap,
                    h - pad,
                )
                recordPanel = RectF(pad, pad, markerRail.left - gap, h - pad)
            } else {
                zoneScale = RectF(
                    previewPanel.right + gap,
                    pad,
                    previewPanel.right + gap + scaleWidth,
                    h - pad,
                )
                markerRail = RectF(
                    zoneScale.right + gap,
                    pad,
                    zoneScale.right + gap + railWidth,
                    h - pad,
                )
                recordPanel = RectF(markerRail.right + gap, pad, w - pad, h - pad)
            }
            val exposureTop = recordPanel.top + if (besideTools) 0f else distanceHeight + distanceGap
            if (distanceProgress > 0f) {
                if (besideTools) {
                    distanceRow.set(cameraFrame.left, previewPanel.bottom + distanceGap, cameraFrame.right, h - pad)
                } else {
                    distanceRow.set(recordPanel.left, recordPanel.top, recordPanel.right, exposureTop - distanceGap)
                }
            }
            apertureRow = RectF(
                recordPanel.left,
                exposureTop,
                recordPanel.right,
                exposureTop + rowHeight,
            )
            shutterRow = RectF(
                recordPanel.left,
                apertureRow.bottom + gap,
                recordPanel.right,
                apertureRow.bottom + gap + rowHeight,
            )
            val lockWidth = 32f * density
            lockTrack = if (state.isLeftHanded) {
                RectF(apertureRow.left, apertureRow.top, apertureRow.left + lockWidth, shutterRow.bottom)
            } else {
                RectF(apertureRow.right - lockWidth, apertureRow.top, apertureRow.right, shutterRow.bottom)
            }
            recordPanel = RectF(
                recordPanel.left,
                shutterRow.bottom + gap,
                recordPanel.right,
                recordPanel.bottom,
            )
            val recordContentRight =
                recordPanel.left + recordPanel.width() * RECORD_CONTENT_FRACTION
            markSize = min(
                80f * density,
                min(recordPanel.width() * 0.22f, recordPanel.height() * 0.46f),
            )
            clearTrack = RectF(
                recordPanel.left + gap,
                recordPanel.bottom - 28f * density,
                recordContentRight,
                recordPanel.bottom - gap,
            )
            clearHandle = RectF(
                clearTrack.left,
                clearTrack.top,
                clearTrack.left + min(66f * density, clearTrack.width() * 0.34f),
                clearTrack.bottom,
            )
            recordViewport = RectF(
                recordPanel.left + gap,
                recordPanel.top + gap,
                recordContentRight,
                clearTrack.top - gap,
            )
        }

        val actionLeft = recordViewport.right + gap
        val actionRight = recordPanel.right
        val badgeWidth = min(44f * density, (actionRight - actionLeft) * .33f)
        val badgeHeight = min(44f * density, recordPanel.height() * .27f)
        val diameter = min(markSize, (actionRight - actionLeft - badgeWidth - gap).coerceAtLeast(1f))
        val controlDensity = AdaptiveLayout.density(width, height, deviceDensity, LayoutProfile.SCROLL)
        val preferredCircleSize = min(34f * controlDensity, (actionRight - actionLeft) / 3.45f)
        val rowGap = 10f * density
        val labelReserve = 10f * density
        val minimumCenter = recordPanel.top + labelReserve + preferredCircleSize + rowGap + diameter / 2
        val maximumCenter = recordPanel.bottom - maxOf(diameter / 2, badgeHeight / 2 + 26f * density)
        val centerY = maxOf(recordPanel.centerY(), minimumCenter)
            .coerceAtMost(maximumCenter)
        val markButton = RectF(actionLeft, centerY - diameter / 2, actionLeft + diameter, centerY + diameter / 2)
        val ev100Badge = RectF(actionRight - badgeWidth, centerY - badgeHeight / 2, actionRight, centerY + badgeHeight / 2)
        val rowBottom = markButton.top - rowGap
        val circleSize = min(preferredCircleSize, (rowBottom - recordPanel.top - labelReserve).coerceAtLeast(0f))
        val auxiliaryWidth = min(actionRight - actionLeft, 3 * (circleSize + 10f * density))
        val auxiliaryRow = RectF(actionLeft, rowBottom - circleSize, actionLeft + auxiliaryWidth, rowBottom)
        fun handed(rect: RectF): RectF = if (state.isLeftHanded) {
            val axis = recordPanel.left + recordPanel.right
            RectF(axis - rect.right, rect.top, axis - rect.left, rect.bottom)
        } else rect

        return ZoneLayoutGeometry(
            landscape = landscape,
            previewPanel = previewPanel,
            cameraFrame = cameraFrame,
            formatButton = formatButton,
            orientationButton = orientationButton,
            zoomTrack = zoomTrack,
            normalHandle = normalHandle,
            apertureRow = apertureRow,
            shutterRow = shutterRow,
            lockTrack = lockTrack,
            zoneScale = zoneScale,
            markerRail = markerRail,
            recordPanel = recordPanel,
            recordViewport = handed(recordViewport),
            clearTrack = handed(clearTrack),
            clearHandle = RectF(handed(clearTrack)).apply { right = left + clearHandle.width() },
            ev100Badge = handed(ev100Badge),
            markButton = handed(markButton),
            settingsButton = settingsButton,
            toolsButton = toolsButton,
            distanceRow = distanceRow,
            auxiliaryRow = handed(auxiliaryRow),
        )
    }

    private const val RECORD_CONTENT_FRACTION = 0.5f
}
