package com.lightmeter.rawmeter

/** Keep the tap hint still for 200 ms, then move it outward while fading. */
internal class ModeGuidePulse {
    data class Frame(val travel: Float, val opacity: Float)
    private var startedAt: Long? = null

    fun show(now: Long) { startedAt = now }
    fun clear() { startedAt = null }

    fun frame(now: Long): Frame? {
        val start = startedAt ?: return null
        val elapsed = (now - start).coerceAtLeast(0)
        if (elapsed >= 600L) {
            clear()
            return null
        }
        val progress = ((elapsed - 200L) / 400f).coerceIn(0f, 1f)
        val eased = 1 - (1 - progress) * (1 - progress)
        return Frame(eased, 1 - eased)
    }
}
