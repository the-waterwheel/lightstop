package com.lightmeter.rawmeter

/** One visible step in the sequential calibration guide. */
internal data class MeteringCalibrationStep(
    val source: MeteringSource,
    val position: Int,
    val total: Int,
)

/** Immutable data handed to persistence only after a calibration run ends. */
internal data class MeteringCalibrationCompletion(
    val referenceEv100: Double,
    val measurements: Map<MeteringSource, Double>,
    val hasFailures: Boolean,
    val terminalError: String?,
)

internal sealed interface MeteringCalibrationTransition {
    data class Next(val step: MeteringCalibrationStep) : MeteringCalibrationTransition
    data class Complete(val result: MeteringCalibrationCompletion) : MeteringCalibrationTransition
    data object LensChanged : MeteringCalibrationTransition
    data object Idle : MeteringCalibrationTransition
}

/**
 * Owns a complete multi-source calibration run, leaving MainActivity responsible only for UI and
 * CameraController responsible only for Camera2 work. All transitions are deterministic and can
 * be exercised without Android or a camera.
 */
internal class MeteringCalibrationCoordinator {
    private var run: MeteringCalibrationRun? = null
    private var expectedCameraIdentity: CalibrationCaptureIdentity? = null

    val isActive: Boolean get() = run != null

    fun start(
        referenceEv100: Double,
        sources: List<MeteringSource>,
        cameraIdentity: CalibrationCaptureIdentity,
    ): MeteringCalibrationTransition.Next? {
        if (run != null || !referenceEv100.isFinite() || sources.isEmpty()) return null
        run = MeteringCalibrationRun(referenceEv100, sources)
        expectedCameraIdentity = cameraIdentity
        return nextStep()?.let(MeteringCalibrationTransition::Next)
    }

    fun onReading(
        reading: MeterReading,
        currentCameraIdentity: CalibrationCaptureIdentity,
    ): MeteringCalibrationTransition {
        val activeRun = run ?: return MeteringCalibrationTransition.Idle
        val expectedIdentity = expectedCameraIdentity
            ?: return MeteringCalibrationTransition.Idle
        if (expectedIdentity.conflictsWith(currentCameraIdentity)) {
            clear()
            return MeteringCalibrationTransition.LensChanged
        }
        // A newly opened logical-camera session initially has no active physical id. Learn the
        // first concrete id it reports, but never treat the temporary null state as a lens switch.
        expectedCameraIdentity = expectedIdentity.withReportedPhysicalFrom(currentCameraIdentity)
        activeRun.accept(reading)
        return nextOrComplete(terminalError = null)
    }

    fun onStageError(message: String): MeteringCalibrationTransition {
        val activeRun = run ?: return MeteringCalibrationTransition.Idle
        activeRun.failActiveStage()
        return nextOrComplete(terminalError = message)
    }

    fun currentStep(): MeteringCalibrationStep? = nextStep()

    fun cancel() = clear()

    private fun nextOrComplete(terminalError: String?): MeteringCalibrationTransition {
        nextStep()?.let(MeteringCalibrationTransition::Next)?.let { return it }
        val activeRun = run ?: return MeteringCalibrationTransition.Idle
        val result = MeteringCalibrationCompletion(
            referenceEv100 = activeRun.referenceEv100,
            measurements = activeRun.measurements,
            hasFailures = activeRun.hasFailures,
            terminalError = terminalError,
        )
        clear()
        return MeteringCalibrationTransition.Complete(result)
    }

    private fun nextStep(): MeteringCalibrationStep? {
        val activeRun = run ?: return null
        val source = activeRun.activeSource ?: return null
        return MeteringCalibrationStep(
            source = source,
            position = activeRun.completedCount + 1,
            total = activeRun.totalCount,
        )
    }

    private fun clear() {
        run = null
        expectedCameraIdentity = null
    }
}
