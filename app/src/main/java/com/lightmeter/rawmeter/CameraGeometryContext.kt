package com.lightmeter.rawmeter

import android.graphics.Rect

/**
 * Geometry of the stream that actually feeds the displayed preview and YUV tracking.
 *
 * This domain must not be overwritten by active physical-camera metadata: a differently mounted
 * physical sensor can report a different orientation or active array while the running logical
 * preview stream keeps its own coordinate system.
 */
data class PreviewStreamGeometry(
    val routeId: String,
    val generation: Long,
    val sensorOrientationDegrees: Int,
    val lensFacing: Int,
    val activeArray: Rect?,
    val previewWidth: Int,
    val previewHeight: Int,
    val sensorViewport: NormalizedSensorViewport,
)

/**
 * Geometry of the RAW sensor used for metering. It is owned by the active physical route and may
 * legitimately differ from [PreviewStreamGeometry].
 */
data class RawSensorGeometry(
    val routeId: String,
    val generation: Long,
    val physicalCameraId: String?,
    val sensorOrientationDegrees: Int,
    val lensFacing: Int,
    val activeArray: Rect?,
    val rawWidth: Int,
    val rawHeight: Int,
)

/**
 * Immutable snapshot of both geometry domains plus a monotonically increasing epoch. Consumers that
 * convert between screen, preview and RAW coordinates must record the epoch they used so a stale
 * tracking result can never be applied after the geometry changed.
 */
data class CameraGeometryContext(
    val epoch: Long,
    val preview: PreviewStreamGeometry?,
    val raw: RawSensorGeometry?,
) {
    /** Returns a new context with the epoch advanced when either domain materially changed. */
    fun withDomains(
        preview: PreviewStreamGeometry?,
        raw: RawSensorGeometry?,
    ): CameraGeometryContext {
        val changed = this.preview != preview || this.raw != raw
        return if (changed) {
            CameraGeometryContext(epoch + 1, preview, raw)
        } else {
            this
        }
    }

    companion object {
        val EMPTY = CameraGeometryContext(epoch = 0, preview = null, raw = null)
    }
}

/**
 * Pure geometry-domain rules. Active physical-camera metadata updates the RAW domain only; the
 * running preview stream keeps its own coordinate system.
 */
object CameraGeometryPolicy {
    fun updateRawDomain(
        current: CameraGeometryContext,
        raw: RawSensorGeometry?,
    ): CameraGeometryContext = current.withDomains(current.preview, raw)

    fun updatePreviewDomain(
        current: CameraGeometryContext,
        preview: PreviewStreamGeometry?,
    ): CameraGeometryContext = current.withDomains(preview, current.raw)
}
