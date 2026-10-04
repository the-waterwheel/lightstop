package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics

/** Immutable measurement inputs shared by ordinary and transient camera sessions. */
internal data class MeteringPlan(
    val frameFormat: FrameFormat,
    val displayZoom: Float,
    val meteringMode: MeteringMode,
    val target: ZoneMeteringTarget?,
    val meteringAngleDegrees: Int,
    val requestedSource: MeteringSource?,
    val screenAspect: Float,
    val sensorOrientation: Int,
    val sensorFrameAspect: Float,
    val meteringRoiFraction: Float?,
    val displayedPreviewReference: PreviewLumaReference?,
    val zoneBatchTargets: List<ZoneRawBatchTarget> = emptyList(),
    /** Monotonic request time used only for phase-by-phase latency diagnostics. */
    val requestedAtNs: Long = System.nanoTime(),
)

/** A Zone point and its preview feature patch, all frozen before the RAW session switch. */
internal data class ZoneRawBatchTarget(
    val markerId: Int,
    val target: ZoneMeteringTarget,
    val previewReference: PreviewLumaReference?,
)

internal data class ZoneRawFailure(
    val message: String,
    val meteringMode: MeteringMode,
    val target: ZoneMeteringTarget?,
    val meteringRoiFraction: Float?,
)

/** State retained while Zone metering temporarily replaces the resident preview with RAW. */
internal data class ZoneRawTransaction(
    val plan: MeteringPlan,
    val expectedPhysicalCameraId: String?,
    val residentCameraInfo: CameraUiInfo,
    val residentCharacteristics: CameraCharacteristics?,
    val sessionState: ZoneRawSessionState = ZoneRawSessionState(),
    var reading: MeterReading? = null,
    var batchResults: List<ZoneMeteringResult>? = null,
    var failure: ZoneRawFailure? = null,
    var physicalCameraChanged: Boolean = false,
    var resultDelivered: Boolean = false,
    var rawSessionConfiguredAtNs: Long? = null,
    var resultReadyAtNs: Long? = null,
    var restoreStartedAtNs: Long? = null,
    var restoreCompletedAtNs: Long? = null,
)

/** Owns transaction identity so a late callback cannot clear a newer Zone operation. */
internal class ZoneRawTransactionCoordinator {
    var active: ZoneRawTransaction? = null
        private set

    fun begin(
        plan: MeteringPlan,
        expectedPhysicalCameraId: String?,
        residentCameraInfo: CameraUiInfo,
        residentCharacteristics: CameraCharacteristics?,
    ): ZoneRawTransaction = ZoneRawTransaction(
        plan = plan,
        expectedPhysicalCameraId = expectedPhysicalCameraId,
        residentCameraInfo = residentCameraInfo,
        residentCharacteristics = residentCharacteristics,
    ).also { active = it }

    fun isActive(transaction: ZoneRawTransaction): Boolean = active === transaction

    fun clear(transaction: ZoneRawTransaction): Boolean {
        if (!isActive(transaction)) return false
        active = null
        return true
    }

    fun reset() {
        active = null
    }
}
