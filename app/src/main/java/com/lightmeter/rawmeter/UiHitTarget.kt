package com.lightmeter.rawmeter

import android.graphics.RectF

/**
 * Expands only hit testing, never drawing, to the Android 48 dp minimum touch target.
 * This preserves the mechanical visual design while keeping compact controls usable across
 * density buckets and smaller displays.
 */
internal fun RectF.containsAccessibleTarget(
    x: Float,
    y: Float,
    density: Float,
    minimumDp: Float = 48f,
): Boolean {
    val minimumSize = minimumDp * density
    val horizontalExpansion = ((minimumSize - width()) / 2f).coerceAtLeast(0f)
    val verticalExpansion = ((minimumSize - height()) / 2f).coerceAtLeast(0f)
    return x >= left - horizontalExpansion && x <= right + horizontalExpansion &&
        y >= top - verticalExpansion && y <= bottom + verticalExpansion
}
