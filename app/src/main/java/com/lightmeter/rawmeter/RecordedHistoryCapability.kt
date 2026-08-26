package com.lightmeter.rawmeter

/**
 * A saved RAW grid, rather than the optional DNG file alone, is what lets history replay
 * recalculate a Zone point at a new position.
 */
internal object RecordedHistoryCapability {
    fun canRecalculateZone(record: ParameterRecordEntry): Boolean = record.rawGrid != null
}
