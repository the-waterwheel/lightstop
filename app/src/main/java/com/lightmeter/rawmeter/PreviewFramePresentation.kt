package com.lightmeter.rawmeter

/**
 * Preserve the latched camera buffer's timestamp across the GL producer boundary. A settings-only
 * redraw still represents that same camera frame. Never substitute a render/system-clock timestamp:
 * displayed-preview metering requires an exact match with SENSOR_TIMESTAMP metadata.
 *
 * Call on the GL thread after drawing. The EGL adapters must throw on failure so an unbound frame
 * cannot be swapped or announced as ready. Inline callbacks avoid per-frame lambda allocations.
 */
internal inline fun presentPreviewFrame(
    cameraTimestampNs: Long,
    setPresentationTime: (Long) -> Unit,
    swapBuffers: () -> Unit,
): Boolean {
    if (cameraTimestampNs <= 0L) return false
    setPresentationTime(cameraTimestampNs)
    swapBuffers()
    return true
}
