package com.lightmeter.rawmeter

/** One unavailable notice per explicit Auto selection; background sampling never arms a notice. */
internal class AutomaticDistanceFeedback {
    private var pending = false

    fun selected(automatic: Boolean) { pending = automatic }

    fun consumeUnavailable(state: DistanceMeasurementState): Boolean {
        if (!pending) return false
        return when (state.status) {
            DistanceMeasurementStatus.UNSUPPORTED -> { pending = false; true }
            DistanceMeasurementStatus.AVAILABLE -> { pending = false; false }
            else -> false
        }
    }
}
