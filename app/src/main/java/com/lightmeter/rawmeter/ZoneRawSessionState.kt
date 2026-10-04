package com.lightmeter.rawmeter

internal enum class ZoneRawSessionPhase { SWITCHING_TO_RAW, METERING, RESTORING, COMPLETE }

/** Small guardrail around the only legal Zone RAW session transition order. */
internal class ZoneRawSessionState {
    var phase: ZoneRawSessionPhase = ZoneRawSessionPhase.SWITCHING_TO_RAW
        private set

    fun markRawSessionConfigured(): Boolean = move(
        expected = ZoneRawSessionPhase.SWITCHING_TO_RAW,
        next = ZoneRawSessionPhase.METERING,
    )

    fun beginRestore(): Boolean = when (phase) {
        ZoneRawSessionPhase.SWITCHING_TO_RAW,
        ZoneRawSessionPhase.METERING,
        -> {
            phase = ZoneRawSessionPhase.RESTORING
            true
        }
        ZoneRawSessionPhase.RESTORING,
        ZoneRawSessionPhase.COMPLETE,
        -> false
    }

    fun markResidentSessionConfigured(): Boolean = move(
        expected = ZoneRawSessionPhase.RESTORING,
        next = ZoneRawSessionPhase.COMPLETE,
    )

    private fun move(
        expected: ZoneRawSessionPhase,
        next: ZoneRawSessionPhase,
    ): Boolean {
        if (phase != expected) return false
        phase = next
        return true
    }
}
