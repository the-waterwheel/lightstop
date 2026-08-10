package com.lightmeter.rawmeter

interface CameraControllerCallback {
    fun localized(chinese: String, english: String): String
    fun onCameraInfo(info: CameraUiInfo)
    fun onRawUnavailable()
    fun onZoneTrackingFrame(frame: ZoneTrackingFrame)
    fun onMeteringStarted(source: MeteringSource, frameCount: Int)
    fun onMeterReading(reading: MeterReading)
    fun onMeteringError(message: String)
    fun onVignettingCalibrationStarted()
    fun onVignettingCalibrationCompleted(info: VignettingCalibrationInfo)
    fun onVignettingCalibrationError(message: String)
}
