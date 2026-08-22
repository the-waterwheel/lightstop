package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image

/** Camera2 adapter around the device-independent color-temperature math. */
internal object RawColorTemperatureAnalysis {
    fun supportsCalibration(characteristics: CameraCharacteristics): Boolean =
        calibration(characteristics) != null

    fun analyze(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        cameraInfo: CameraUiInfo,
        frameAspect: Float,
        zoom: Float,
    ): ColorTemperatureReading? {
        val sample = MeteringAnalysis.analyzeRawColorSample(
            image = image,
            result = result,
            characteristics = characteristics,
            activeArray = cameraInfo.activeArray,
            frameAspect = frameAspect,
            zoom = zoom,
        ) ?: return null
        return ColorTemperatureMath.estimate(sample, calibration(characteristics) ?: return null)
    }

    private fun calibration(characteristics: CameraCharacteristics): SensorColorCalibration? {
        val color1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            ?: return null
        val device1 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
            ?: return null
        val illuminant1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
            ?: return null
        val matrix1 = device1.toMatrix3() * color1.toMatrix3()

        val color2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        val device2 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)
        val illuminant2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
            ?.toInt()?.and(0xff)
        val secondComplete = color2 != null && device2 != null && illuminant2 != null
        return SensorColorCalibration(
            firstMatrix = matrix1,
            firstIlluminantKelvin = referenceIlluminantKelvin(illuminant1),
            secondMatrix = if (secondComplete) device2!!.toMatrix3() * color2!!.toMatrix3() else null,
            secondIlluminantKelvin = if (secondComplete) {
                referenceIlluminantKelvin(illuminant2!!)
            } else {
                null
            },
        )
    }

    private fun ColorSpaceTransform.toMatrix3(): Matrix3 = Matrix3(
        DoubleArray(9) { index ->
            val row = index / 3
            val column = index % 3
            getElement(column, row).toDouble()
        },
    )

    private fun referenceIlluminantKelvin(value: Int): Int = when (value) {
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2_850
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A -> 2_856
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> 3_200
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT -> 3_450
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> 4_200
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B -> 4_874
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> 5_000
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 -> 5_003
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLASH,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55,
        -> 5_500
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> 6_400
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65,
        -> 6_500
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C -> 6_774
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75,
        -> 7_500
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT -> 4_000
        else -> 5_500
    }
}
