package com.lightmeter.rawmeter

/** Normal owns the saved choice; Global's temporary Zone choice must never overwrite it. */
internal class MeteringModeSelection(initialMode: MeteringMode) {
    var normalMode: MeteringMode = initialMode
        private set
    private var zoneActive = false
    private var zoneOverride: MeteringMode? = null

    var current: MeteringMode
        get() = if (zoneActive) zoneOverride ?: MeteringAreaPolicy.zoneMode(normalMode) else normalMode
        set(value) {
            if (zoneActive && normalMode == MeteringMode.AVERAGE) {
                zoneOverride = MeteringAreaPolicy.zoneMode(value)
            } else {
                normalMode = value
                zoneOverride = null
            }
        }

    fun setZoneActive(active: Boolean) {
        if (zoneActive == active) return
        zoneActive = active
        zoneOverride = null
    }
}
