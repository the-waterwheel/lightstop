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
    val markButton: RectF,
)

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
    ): ZoneLayoutGeometry {
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
        val markButton: RectF
        val aspect = if (state.frameLandscape) {
            state.frameFormat.landscapeAspect
        } else {
            1f / state.frameFormat.landscapeAspect
        }

        if (!landscape) {
            previewPanel = RectF(
                pad,
                pad,
                w - pad,
                h * 0.42f,
            )
            val frameArea = RectF(
                previewPanel.left + gap,
                previewPanel.top + gap,
                previewPanel.right - 34f * density - gap,
                previewPanel.bottom - gap,
            )
            cameraFrame = fitAspect(frameArea, aspect)
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
            settingsButton = RectF(
                previewPanel.left + gap,
                previewPanel.bottom - gap - button,
                previewPanel.left + gap + button,
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
                cameraFrame.centerX() - 38f * density,
                previewPanel.bottom - 27f * density,
                cameraFrame.centerX() + 38f * density,
                previewPanel.bottom - 7f * density,
            )

            val controlsTop = previewPanel.bottom + gap * 1.4f
            val rowHeight = h * 0.055f
            apertureRow = RectF(pad, controlsTop, w - pad, controlsTop + rowHeight)
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
            val markSize = min(
                72f * density,
                min(recordPanel.width() * 0.20f, recordPanel.height() * 0.34f),
            )
            val markLeft = centeredActionLeft(recordPanel, recordContentRight, markSize, gap)
            markButton = RectF(
                markLeft,
                recordPanel.centerY() - markSize / 2f,
                markLeft + markSize,
                recordPanel.centerY() + markSize / 2f,
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
            val previewWidth = w * 0.43f
            previewPanel = if (state.isLeftHanded) {
                RectF(w - previewWidth + pad, pad, w - pad, h - pad)
            } else {
                RectF(pad, pad, previewWidth, h - pad)
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
            cameraFrame = fitAspect(frameArea, aspect)
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
            settingsButton = RectF(
                previewPanel.left + gap,
                previewPanel.bottom - gap - button,
                previewPanel.left + gap + button,
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
                zoomTrack.centerY() - normalHeight / 2f,
                normalLeft + normalWidth,
                zoomTrack.centerY() + normalHeight / 2f,
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
            val rowHeight = recordPanel.height() * 0.145f
            apertureRow = RectF(
                recordPanel.left,
                recordPanel.top,
                recordPanel.right,
                recordPanel.top + rowHeight,
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
            val markSize = min(
                68f * density,
                min(recordPanel.width() * 0.18f, recordPanel.height() * 0.42f),
            )
            val markLeft = centeredActionLeft(recordPanel, recordContentRight, markSize, gap)
            markButton = RectF(
                markLeft,
                recordPanel.centerY() - markSize / 2f,
                markLeft + markSize,
                recordPanel.centerY() + markSize / 2f,
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
            recordViewport = recordViewport,
            clearTrack = clearTrack,
            clearHandle = clearHandle,
            markButton = markButton,
            settingsButton = settingsButton,
            toolsButton = toolsButton,
        )
    }

    private fun fitAspect(bounds: RectF, aspect: Float): RectF {
        val boundsAspect = bounds.width() / bounds.height()
        return if (boundsAspect > aspect) {
            val fittedWidth = bounds.height() * aspect
            RectF(
                bounds.centerX() - fittedWidth / 2f,
                bounds.top,
                bounds.centerX() + fittedWidth / 2f,
                bounds.bottom,
            )
        } else {
            val fittedHeight = bounds.width() / aspect
            RectF(
                bounds.left,
                bounds.centerY() - fittedHeight / 2f,
                bounds.right,
                bounds.centerY() + fittedHeight / 2f,
            )
        }
    }

    /** Centers the mark action in the space beside the details list on every screen width. */
    private fun centeredActionLeft(
        panel: RectF,
        contentRight: Float,
        actionSize: Float,
        gap: Float,
    ): Float {
        val availableLeft = contentRight + gap
        val availableRight = panel.right - gap
        val centered = (availableLeft + availableRight - actionSize) / 2f
        return centered.coerceIn(availableLeft, (availableRight - actionSize).coerceAtLeast(availableLeft))
    }

    private const val RECORD_CONTENT_FRACTION = 0.5f
}
