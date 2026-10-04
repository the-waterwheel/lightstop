package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureMetadataPolicyTest {
    @Test
    fun readSensorSettingsAndStaticApertureAreSufficient() {
        assertTrue(
            supports(
                readSensorSettingsAvailable = true,
                staticApertureAvailable = true,
            ),
        )
    }

    @Test
    fun manualSensorAndApertureResultAreSufficient() {
        assertTrue(
            supports(
                manualSensorAvailable = true,
                apertureResultAvailable = true,
            ),
        )
    }

    @Test
    fun explicitSensorResultKeysSupportLimitedCameraProviders() {
        assertTrue(
            supports(
                exposureTimeResultAvailable = true,
                sensitivityResultAvailable = true,
                staticApertureAvailable = true,
            ),
        )
    }

    @Test
    fun oneSensorResultKeyIsNotEnough() {
        assertFalse(
            supports(
                exposureTimeResultAvailable = true,
                staticApertureAvailable = true,
            ),
        )
    }

    @Test
    fun apertureMetadataIsAlwaysRequired() {
        assertFalse(supports(readSensorSettingsAvailable = true))
    }

    @Test
    fun oneCompleteLiveResultCanConfirmAnUndeclaredCamera() {
        assertTrue(
            ExposureMetadataPolicy.hasUsableFrameMetadata(
                exposureTimeNs = 10_000_000L,
                sensitivity = 100,
                resultAperture = null,
                staticApertures = floatArrayOf(1.8f),
            ),
        )
    }

    @Test
    fun oneIncompleteLiveResultDoesNotConfirmSupport() {
        assertFalse(
            ExposureMetadataPolicy.hasUsableFrameMetadata(
                exposureTimeNs = null,
                sensitivity = 100,
                resultAperture = 2f,
                staticApertures = null,
            ),
        )
    }

    private fun supports(
        readSensorSettingsAvailable: Boolean = false,
        manualSensorAvailable: Boolean = false,
        exposureTimeResultAvailable: Boolean = false,
        sensitivityResultAvailable: Boolean = false,
        apertureResultAvailable: Boolean = false,
        staticApertureAvailable: Boolean = false,
    ): Boolean = ExposureMetadataPolicy.supportsAbsoluteMetering(
        readSensorSettingsAvailable = readSensorSettingsAvailable,
        manualSensorAvailable = manualSensorAvailable,
        exposureTimeResultAvailable = exposureTimeResultAvailable,
        sensitivityResultAvailable = sensitivityResultAvailable,
        apertureResultAvailable = apertureResultAvailable,
        staticApertureAvailable = staticApertureAvailable,
    )
}
