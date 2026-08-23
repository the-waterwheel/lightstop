package com.lightmeter.rawmeter

interface CameraControllerCallback {
    fun localized(chinese: String, english: String): String
    fun onCameraInfo(info: CameraUiInfo)
    fun onRawUnavailable()
    fun tryReserveZoneTrackingFrame(): Boolean
    fun cancelZoneTrackingFrameReservation()
    fun onZoneTrackingFrame(frame: ZoneTrackingFrame)
    fun onMeteringStarted(source: MeteringSource, frameCount: Int)
    fun onMeterReading(reading: MeterReading)
    fun onMeteringError(message: String)
    fun onExposurePreviewUnavailable() = Unit
    fun onVignettingCalibrationStarted()
    fun onVignettingCalibrationCompleted(info: VignettingCalibrationInfo)
    fun onVignettingCalibrationError(message: String)
}
