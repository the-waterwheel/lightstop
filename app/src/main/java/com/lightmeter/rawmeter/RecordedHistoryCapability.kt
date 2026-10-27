package com.lightmeter.rawmeter

/**
 * A saved RAW grid, rather than the optional DNG file alone, is what lets history replay
 * recalculate a Zone point at a new position.
 */
internal object RecordedHistoryCapability {
    fun canRecalculateZone(record: ParameterRecordEntry): Boolean = record.rawGrid?.let {
        it.width > 0 && it.height > 0 && it.values.size.toLong() == it.width.toLong() * it.height &&
            it.referenceLuma.isFinite() && it.referenceLuma > 0f && it.values.any { value -> value.isFinite() && value > 0f }
    } == true
}
