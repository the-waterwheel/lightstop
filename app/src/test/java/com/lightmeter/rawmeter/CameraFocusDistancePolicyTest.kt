package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFocusDistancePolicyTest {
    @Test
    fun fixedFocusAndExplicitlyUncalibratedMetadataAreRejected() {
        assertFalse(CameraUiInfo(minimumFocusDistanceDiopters = 0f).metricFocusDistanceAvailable)
        assertFalse(
            CameraUiInfo(
                minimumFocusDistanceDiopters = 5f,
                focusDistanceCalibration =
                    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED,
            ).metricFocusDistanceAvailable,
        )
    }

    @Test
    fun approximateOrCalibratedVariableFocusMetadataCanDriveAutoDistance() {
        assertTrue(
            CameraUiInfo(
                minimumFocusDistanceDiopters = 5f,
                focusDistanceCalibration =
                    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE,
            ).metricFocusDistanceAvailable,
        )
        assertTrue(
            CameraUiInfo(
                minimumFocusDistanceDiopters = 5f,
                focusDistanceCalibration =
                    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
            ).metricFocusDistanceAvailable,
        )
    }
}
