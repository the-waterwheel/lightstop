package com.lightmeter.rawmeter

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build

/** Immutable physical-camera identity associated with one logical-camera route. */
internal data class ActiveCameraContext(
    val logicalCameraId: String,
    val requestedPhysicalCameraId: String?,
    val activePhysicalCameraId: String?,
)

internal data class ActiveCameraUpdate(
    val context: ActiveCameraContext,
    val changed: Boolean,
    val requestedPhysicalResultPresent: Boolean,
)

/** Pure selection rule, separated so the policy remains unit-testable without Camera2 mocks. */
internal object ActivePhysicalCameraSelector {
    fun select(
        requestedPhysicalCameraId: String?,
        reportedActivePhysicalCameraId: String?,
    ): String? = requestedPhysicalCameraId ?: reportedActivePhysicalCameraId
}

/**
 * Camera-thread-confined tracker for the physical camera that produced a logical camera result.
 * API 28 deliberately remains "unknown" for automatic logical routes: guessing a physical id
 * would apply the wrong calibration after a vendor lens switch.
 */
internal class ActivePhysicalCameraTracker {
    private var current = ActiveCameraContext("", null, null)

    fun reset(logicalCameraId: String, requestedPhysicalCameraId: String?): ActiveCameraContext {
        current = ActiveCameraContext(
            logicalCameraId = logicalCameraId,
            requestedPhysicalCameraId = requestedPhysicalCameraId,
            activePhysicalCameraId = requestedPhysicalCameraId,
        )
        return current
    }

    @Suppress("DEPRECATION")
    fun update(result: TotalCaptureResult): ActiveCameraUpdate {
        val requested = current.requestedPhysicalCameraId
        val reported = if (requested == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        } else {
            null
        }
        val next = current.copy(
            activePhysicalCameraId = ActivePhysicalCameraSelector.select(requested, reported),
        )
        val requestedResultPresent = requested?.let(result.physicalCameraResults::containsKey) ?: true
        val changed = next != current
        current = next
        return ActiveCameraUpdate(next, changed, requestedResultPresent)
    }
}
