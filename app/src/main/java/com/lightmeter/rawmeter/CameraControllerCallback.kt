package com.lightmeter.rawmeter

interface CameraControllerCallback {
    fun onCameraInfo(info: CameraUiInfo)
    fun onRawUnavailable()
    fun onZoneTrackingFrame(frame: ZoneTrackingFrame)
    fun onMeteringStarted(source: MeteringSource)
    fun onMeterReading(reading: MeterReading)
    fun onMeteringError(message: String)
}
