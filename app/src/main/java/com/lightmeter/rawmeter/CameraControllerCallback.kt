package com.lightmeter.rawmeter

interface CameraControllerCallback {
    fun localized(chinese: String, english: String): String
    fun onCameraInfo(info: CameraUiInfo)
    fun onDistanceMeasurementState(state: DistanceMeasurementState) = Unit
    fun onCombinationSelectionFallbackToSystem() = Unit
    fun onRawUnavailable()
    fun tryReserveZoneTrackingFrame(): Boolean
    fun cancelZoneTrackingFrameReservation()
    fun onZoneTrackingFrame(frame: ZoneTrackingFrame)
    fun onMeteringStarted(source: MeteringSource, frameCount: Int)
    fun onMeterReading(reading: MeterReading)
    fun onMeteringError(message: String)
    /** Zone RAW can have a usable reading while the mutually-exclusive resident session restores. */
    fun onMeteringRestoreStateChanged(restoring: Boolean) = Unit
    fun onMeteringBaselineRestoring() = Unit
    fun onExposurePreviewUnavailable() = Unit
    fun onVignettingCalibrationStarted()
    fun onVignettingCalibrationCompleted(info: VignettingCalibrationInfo)
    fun onVignettingCalibrationError(message: String)
}
