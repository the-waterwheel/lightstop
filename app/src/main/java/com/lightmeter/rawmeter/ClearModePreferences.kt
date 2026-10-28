package com.lightmeter.rawmeter

import android.content.Context

/** Clear-only options; do not rewrite the Normal/Zone metering preferences. */
internal class ClearModePreferences(context: Context) {
    private val preferences=context.getSharedPreferences("clear_meter_options",Context.MODE_PRIVATE)
    fun restore(session: AutoMeterSession) {
        session.simulation=preferences.getBoolean("simulation",true)
        session.continuousMetering=preferences.getBoolean("continuous",true)
        session.sampleIntervalMs=preferences.getLong("interval_ms",350L).takeIf { it in INTERVALS } ?: 350L
        session.showInterfaceText=preferences.getBoolean("show_text",true)
        session.smoothMetering=preferences.getBoolean("smooth",false)
    }
    fun save(session: AutoMeterSession) {
        preferences.edit().putBoolean("simulation",session.simulation)
            .putBoolean("continuous",session.continuousMetering).putLong("interval_ms",session.sampleIntervalMs)
            .putBoolean("show_text",session.showInterfaceText).putBoolean("smooth",session.smoothMetering).apply()
    }
    var suppressSmoothNotice: Boolean
        get()=preferences.getBoolean("suppress_smooth_notice",false)
        set(value) { preferences.edit().putBoolean("suppress_smooth_notice",value).apply() }
    companion object { val INTERVALS=listOf(2000L,1000L,500L,350L,200L) }
}
