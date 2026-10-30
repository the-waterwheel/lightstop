package com.lightmeter.rawmeter

/** Deterministic tap hint: hold for half a second, then travel outward and fade. */
internal class ModeGuidePulse {
    data class Frame(val travel:Float,val opacity:Float)
    private var startedAt:Long?=null
    fun show(now:Long) {startedAt=now}
    fun clear() {startedAt=null}
    fun frame(now:Long):Frame? {
        val start=startedAt?:return null
        val elapsed=(now-start).coerceAtLeast(0)
        if(elapsed>=900) {clear();return null}
        val p=((elapsed-500)/400f).coerceIn(0f,1f)
        val eased=1-(1-p)*(1-p)
        return Frame(eased,1-eased)
    }
}
