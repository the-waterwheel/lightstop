package com.lightmeter.rawmeter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import kotlin.math.roundToInt

/** Shared presentation tokens. Keep geometry, hit targets and photographic colours separate. */
internal object InstrumentStyle {
    val ink = Color.rgb(20, 20, 20)
    val nightInk = Color.rgb(210, 210, 206)
    val red = Color.rgb(166, 27, 36)
    val blue = Color.rgb(38, 112, 184)
    val labelTypeface: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun background(dark: Boolean) = if (dark) Color.BLACK else Color.WHITE
    fun foreground(dark: Boolean) = if (dark) nightInk else ink
    fun panel(dark: Boolean) = if (dark) Color.rgb(30, 30, 28) else Color.rgb(242, 242, 239)
    fun control(dark: Boolean) = if (dark) Color.rgb(22, 22, 21) else Color.rgb(249, 249, 247)
    fun border(dark: Boolean) = if (dark) Color.rgb(73, 73, 69) else Color.rgb(210, 210, 205)
    fun secondary(dark: Boolean) = if (dark) Color.rgb(160, 160, 154) else Color.rgb(112, 112, 106)
    // Authored black-and-white tool artwork needs a middle-gray backing in either theme.
    fun iconTile(dark: Boolean) = if (dark) Color.rgb(64, 64, 60) else Color.rgb(230, 230, 225)

    fun styleButton(button: Button, dark: Boolean, primary: Boolean = false, circular: Boolean = false) {
        val density = button.resources.displayMetrics.density
        val selected = button.isSelected
        val shape = GradientDrawable().apply {
            if (circular) this.shape = GradientDrawable.OVAL else cornerRadius = 6f * density
            setColor(if (primary) Color.rgb(42, 42, 40) else control(dark))
            setStroke(density.roundToInt().coerceAtLeast(1), if (selected) red else border(dark))
        }
        button.background = RippleDrawable(
            ColorStateList.valueOf(if (primary) 0x30ffffff else 0x18a61b24), shape, null,
        )
        val text = if (primary) Color.WHITE else if (selected) red else foreground(dark)
        button.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(if (primary) 0x99ffffff.toInt() else secondary(dark), text),
        ))
        button.typeface = labelTypeface
        // Platform Button elevation otherwise adds inconsistent gray shadows around custom shapes.
        button.stateListAnimator = null
        button.elevation = 0f
    }
}
