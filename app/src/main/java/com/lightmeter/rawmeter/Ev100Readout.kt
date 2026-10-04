package com.lightmeter.rawmeter

internal data class Ev100Readout(
    val value: Double?,
    val pending: Boolean,
    val sourceLabel: String? = null,
    val lowConfidence: Boolean = false,
)

/** Resolves the compact EV100 badge without making either meter View own duplicated state rules. */
internal object Ev100Readouts {
    fun normal(state: MeterState): Ev100Readout {
        val quality = ReadingQualityPolicy.evaluate(state.lastNormalReading)
        return Ev100Readout(
            value = state.effectiveEv100,
            pending = state.measuring,
            sourceLabel = quality.sourceLabel,
            lowConfidence = quality.lowConfidence,
        )
    }

    fun zone(session: ZoneMeterSession): Ev100Readout {
        if (session.pendingMarkerId != null) return Ev100Readout(value = null, pending = true)
        val measured = session.markers.lastOrNull { it.ev100 != null }
        val sourceLabel = when (measured?.source) {
            MeteringSource.RAW -> "RAW"
            MeteringSource.YUV_PREVIEW -> "YUV"
            MeteringSource.ISP_PREVIEW -> "ISP"
            null -> null
        }
        return Ev100Readout(
            value = session.markers.lastOrNull()?.ev100,
            pending = false,
            sourceLabel = sourceLabel,
            lowConfidence = measured != null && measured.source != MeteringSource.RAW,
        )
    }
}
