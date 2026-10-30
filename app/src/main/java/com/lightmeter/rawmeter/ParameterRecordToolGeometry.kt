package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal data class ParameterRecordToolGeometry(
    val back: RectF,
    val close: RectF,
    val history: RectF,
    val gpsRow: RectF,
    val timeRow: RectF,
    val rawRow: RectF,
    val gpsToggle: RectF,
    val timeToggle: RectF,
    val rawToggle: RectF,
    val rawHelp: RectF,
    val finishCategory: RectF,
    val startStop: RectF,
) {
    companion object {
        val EMPTY = ParameterRecordToolGeometry(
            RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(),
            RectF(), RectF(), RectF(), RectF(),
        )
    }
}

internal object ParameterRecordToolGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float): ParameterRecordToolGeometry {
        if (width <= 0 || height <= 0) return ParameterRecordToolGeometry.EMPTY
        val density = AdaptiveLayout.density(width, height, density, LayoutProfile.TOOL)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = max(8f * density, min(w, h) * 0.018f)
        val gap = max(6f * density, min(w, h) * 0.012f)
        val headerHeight = (h * 0.12f).coerceIn(44f * density, 58f * density)
        val target = min(52f * density, w * 0.16f).coerceAtLeast(44f * density)
        val back = RectF(pad, 0f, pad + target, headerHeight)
        val close = RectF(w - pad - target, 0f, w - pad, headerHeight)
        val contentTop = headerHeight + gap
        val contentHeight = (h - contentTop - pad).coerceAtLeast(1f)
        val historyHeight = (contentHeight * 0.22f).coerceIn(48f * density, 76f * density)
        val history = RectF(pad, contentTop, w - pad, contentTop + historyHeight)
        val rowGap = gap
        val optionsTop = history.bottom + gap
        val actionHeight = min(52f*density,contentHeight*.16f)
        val actionTop = h-pad-actionHeight
        val optionsBottom = actionTop-gap*2
        val rowHeight = min(82f*density,((optionsBottom-optionsTop-rowGap*2)/3f).coerceAtLeast(1f))
        val gps = RectF(pad,optionsTop,w-pad,optionsTop+rowHeight)
        val time = RectF(pad,gps.bottom+rowGap,w-pad,gps.bottom+rowGap+rowHeight)
        val raw = RectF(pad,time.bottom+rowGap,w-pad,time.bottom+rowGap+rowHeight)
        val toggleWidth = min(54f*density,gps.width()*.26f)
        val helpWidth = min(38f*density,raw.width()*.18f)
        fun toggle(row:RectF,reserveHelp:Boolean)=RectF(row.right-toggleWidth-if(reserveHelp)helpWidth else 0f,
            row.top,row.right-if(reserveHelp)helpWidth else 0f,row.bottom)
        val finishWidth=(w-2*pad-gap)*.42f
        val finish=RectF(pad,actionTop,pad+finishWidth,h-pad)
        val start=RectF(finish.right+gap,actionTop,w-pad,h-pad)
        return ParameterRecordToolGeometry(
            back,
            close,
            history,
            gps,
            time,
            raw,
            toggle(gps, false),
            toggle(time, false),
            toggle(raw, true),
            RectF(raw.right - helpWidth, raw.top, raw.right, raw.bottom),
            finish,
            start,
        )
    }
}
