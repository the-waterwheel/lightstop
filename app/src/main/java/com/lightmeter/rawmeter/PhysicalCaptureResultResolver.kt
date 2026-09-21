package com.lightmeter.rawmeter

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build

/** Resolved exposure metadata for one camera frame, or a rejection that must not be measured. */
internal data class CaptureMetadataResolution(
    val selection: CaptureMetadataSelection,
    val effectiveResult: CaptureResult?,
    val pairedTimestampNs: Long?,
    val metadataCameraId: String?,
    val routeKind: CameraRouteKind,
) {
    val usable: Boolean
        get() = effectiveResult != null

    val failure: CaptureMetadataFailure?
        get() = (selection as? CaptureMetadataSelection.Rejected)?.failure
}

/**
 * Adapts a Camera2 [TotalCaptureResult] into the route-aware result that legally carries a frame's
 * exposure metadata.
 *
 * The resolver never holds an [android.media.Image] and never retries; callers own recovery. A
 * fixed physical output whose result map entry is missing is rejected instead of silently falling
 * back to the logical result, because equal timestamps do not prove equal exposure metadata.
 */
internal class PhysicalCaptureResultResolver {
    fun resolve(
        total: TotalCaptureResult,
        routeKind: CameraRouteKind,
        requestedPhysicalCameraId: String?,
        requiredFields: (CaptureResult) -> Boolean = { true },
    ): CaptureMetadataResolution {
        val physical = requestedPhysicalCameraId?.let { physicalResults(total)[it] }
        val candidate: CaptureResult? = if (requestedPhysicalCameraId == null) total else physical
        val logicalTimestamp = total.get(CaptureResult.SENSOR_TIMESTAMP)
        val physicalTimestamp = physical?.get(CaptureResult.SENSOR_TIMESTAMP)
        val input = CaptureMetadataInput(
            routeKind = routeKind,
            requestedPhysicalCameraId = requestedPhysicalCameraId,
            selectedResultPresent = candidate != null,
            physicalTimestampNs = physicalTimestamp,
            logicalTimestampNs = logicalTimestamp,
            requiredFieldsPresent = candidate != null && requiredFields(candidate),
        )
        val selection = CaptureMetadataSelectionPolicy.select(input)
        return when (selection) {
            is CaptureMetadataSelection.Selected -> CaptureMetadataResolution(
                selection = selection,
                effectiveResult = when (selection.source) {
                    CaptureMetadataSource.PHYSICAL_RESULT -> physical
                    CaptureMetadataSource.LOGICAL_RESULT -> total
                },
                pairedTimestampNs = selection.pairedTimestampNs,
                metadataCameraId = if (selection.source == CaptureMetadataSource.PHYSICAL_RESULT) {
                    requestedPhysicalCameraId
                } else {
                    null
                },
                routeKind = routeKind,
            )

            is CaptureMetadataSelection.Rejected -> CaptureMetadataResolution(
                selection = selection,
                effectiveResult = null,
                pairedTimestampNs = physicalTimestamp ?: logicalTimestamp,
                metadataCameraId = null,
                routeKind = routeKind,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun physicalResults(total: TotalCaptureResult): Map<String, CaptureResult> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            total.physicalCameraTotalResults
        } else {
            total.physicalCameraResults
        }
}
