package com.lightmeter.rawmeter

import android.util.TypedValue
import android.view.View

internal fun View.layoutDensity(profile: LayoutProfile): Float = AdaptiveLayout.density(
    width, height, resources.displayMetrics.density, profile)

internal fun View.layoutTextDensity(profile: LayoutProfile): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics) *
        layoutDensity(profile) / resources.displayMetrics.density
