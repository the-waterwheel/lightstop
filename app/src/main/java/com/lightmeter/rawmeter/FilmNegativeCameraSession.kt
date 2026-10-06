package com.lightmeter.rawmeter

/** The preview tool owns a temporary route. The caller's metering preference is never mutated. */
internal class FilmNegativeCameraSession {
    var cameraId: String? = null
        private set
    val active get() = cameraId != null
    fun open(meterCameraId: String) { cameraId = meterCameraId }
    fun select(id: String, available: Collection<String>): Boolean {
        if (!active || id.isBlank() || id !in available || id == cameraId) return false
        cameraId = id
        return true
    }
    fun expected(meterCameraId: String) = cameraId ?: meterCameraId
    fun reconcile(meterCameraId: String, available: Collection<String>): String {
        if (active && cameraId !in available) cameraId = meterCameraId
        return expected(meterCameraId)
    }
    fun close() { cameraId = null }
}
