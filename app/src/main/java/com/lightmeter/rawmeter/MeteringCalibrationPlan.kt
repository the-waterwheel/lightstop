package com.lightmeter.rawmeter

/** Device capabilities that matter when choosing a sequential calibration run. */
data class MeteringCalibrationCapabilities(
    val rawAvailable: Boolean,
    val yuvAvailable: Boolean,
    val ispPreviewAvailable: Boolean = true,
)

/**
 * Generates every hardware-backed source that can be used by any metering pipeline.
 *
 * Calibration remains sequential and opens the smallest session for each source. The currently
 * selected pipeline must not hide a source: a correction recorded in Stable mode is still needed
 * after the user later switches to Auto or Compatibility mode.
 */
object MeteringCalibrationPlan {
    @Suppress("UNUSED_PARAMETER")
    fun create(
        mode: MeteringPipelineMode,
        capabilities: MeteringCalibrationCapabilities,
    ): List<MeteringSource> = buildList {
        if (capabilities.rawAvailable) add(MeteringSource.RAW)
        if (capabilities.yuvAvailable) add(MeteringSource.YUV_PREVIEW)
        if (capabilities.ispPreviewAvailable) add(MeteringSource.ISP_PREVIEW)
    }
}

/** Small, UI-independent state machine for one sequential calibration run. */
class MeteringCalibrationRun(
    val referenceEv100: Double,
    val sources: List<MeteringSource>,
) {
    private var activeIndex = 0
    private val successfulMeasurements = linkedMapOf<MeteringSource, Double>()
    private val failedSources = linkedSetOf<MeteringSource>()
    private val sourceSignatures = linkedMapOf<MeteringSource, CalibrationSignature>()
    private val sourceInputLuma = linkedMapOf<MeteringSource, Double>()
    private val sourceSamples = linkedMapOf<MeteringSource, CalibrationMeasurementSample>()

    val activeSource: MeteringSource? get() = sources.getOrNull(activeIndex)
    val completedCount: Int get() = activeIndex.coerceAtMost(sources.size)
    val totalCount: Int get() = sources.size
    val measurements: Map<MeteringSource, Double> get() = successfulMeasurements.toMap()

    /** Signature captured at the instant each source was measured, never reconstructed later. */
    val signatures: Map<MeteringSource, CalibrationSignature> get() = sourceSignatures.toMap()

    /** Processed-stream input luminance at each measurement, for response calibration. */
    val inputLuma: Map<MeteringSource, Double> get() = sourceInputLuma.toMap()
    val samples: Map<MeteringSource, CalibrationMeasurementSample> get() = sourceSamples.toMap()
    val hasFailures: Boolean get() = failedSources.isNotEmpty()
    val isComplete: Boolean get() = activeSource == null

    /** Consumes the active stage and stores only the source actually reported by Camera2. */
    fun accept(
        reading: MeterReading,
        signature: CalibrationSignature? = null,
    ): MeteringSource? {
        val requested = activeSource ?: return null
        activeIndex += 1
        if (reading.source != requested) failedSources += requested
        if (reading.source in sources) {
            successfulMeasurements[reading.source] = reading.sceneEv100
            reading.calibrationSample
                ?.takeIf { it.source == reading.source && it.isUsableForSave() }
                ?.let { sourceSamples[reading.source] = it }
            signature?.let { sourceSignatures[reading.source] = it }
            if (reading.rawLuma.isFinite() && reading.rawLuma > 0.0) {
                sourceInputLuma[reading.source] = reading.rawLuma
            }
        }
        return advancePastCompleted()
    }

    /** Consumes a failed stage, preserving prior successful source corrections. */
    fun failActiveStage(): MeteringSource? {
        activeSource?.let(failedSources::add) ?: return null
        activeIndex += 1
        return advancePastCompleted()
    }

    private fun advancePastCompleted(): MeteringSource? {
        while (activeIndex < sources.size && sources[activeIndex] in successfulMeasurements) {
            activeIndex += 1
        }
        return activeSource
    }
}
