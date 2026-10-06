package com.lightmeter.rawmeter

import kotlin.math.min

/** Shared design budgets. Normal-size windows retain the original spacing and proportions. */
internal enum class LayoutProfile { METER, TOOL, EDITOR, SCROLL }

internal object AdaptiveLayout {
    fun density(width: Int, height: Int, deviceDensity: Float, profile: LayoutProfile): Float {
        val base = deviceDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
        if (width <= 0 || height <= 0) return base
        // Called by Canvas dimensions as well as geometry builders; keep this allocation-free.
        val minimumWidth = if (profile == LayoutProfile.METER) {
            if (width > height) 480f else 320f
        } else 240f
        val minimumHeight = when (profile) {
            LayoutProfile.METER -> if (width > height) 320f else 480f
            LayoutProfile.TOOL -> 280f
            LayoutProfile.EDITOR -> 340f
            LayoutProfile.SCROLL -> 160f
        }
        return min(base, min(width / minimumWidth, height / minimumHeight))
    }

    /** Preserve the authored column count unless the cells would become too narrow. */
    fun columns(width: Float, padding: Float, gap: Float, minimumCell: Float, preferred: Int): Int =
        (((width - padding * 2f + gap).coerceAtLeast(0f) / (minimumCell + gap).coerceAtLeast(1f)).toInt())
            .coerceIn(1, preferred.coerceAtLeast(1))

    fun remainingInset(inset: Int, alreadyExcluded: Int): Int = (inset - alreadyExcluded).coerceAtLeast(0)

    data class Drawer(val side: Boolean, val width: Int, val height: Int, val left: Int, val top: Int)

    fun drawer(width: Int, height: Int, density: Float, header: Int, handle: Int,
        content: Int, expanded: Boolean, leftHanded: Boolean): Drawer {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        val remaining = (h - header).coerceAtLeast(0)
        val side = w > h
        val panelWidth = if (side) min((320 * density).toInt(), (w * 0.44f).toInt()).coerceIn(0, w) else w
        val preferredHeight = if (side) remaining else min((420 * density).toInt(), (h * 0.44f).toInt())
        val panelHeight = if (!expanded) handle.coerceIn(0, remaining) else {
            // Enlarged text scrolls inside the budget instead of covering the viewfinder.
            (if (side) preferredHeight else min(preferredHeight, (h * 0.44f).toInt()))
                .coerceAtMost(handle + content).coerceIn(0, remaining)
        }
        return Drawer(side, panelWidth, panelHeight,
            if (side && leftHanded) 0 else w - panelWidth, h - panelHeight)
    }
}
