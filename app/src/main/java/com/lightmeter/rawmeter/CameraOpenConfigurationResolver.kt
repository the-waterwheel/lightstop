package com.lightmeter.rawmeter

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.util.Range
import android.util.Size
import android.util.SizeF

/** Immutable static metadata needed to configure one selected Camera2 route. */
internal data class CameraOpenConfiguration(
    val descriptor: CameraDescriptor,
    val route: CameraRouteCandidate,
    val logicalCharacteristics: CameraCharacteristics,
    val streamCharacteristics: CameraCharacteristics,
    val rawHardwareAvailable: Boolean,
    val trackingHardwareAvailable: Boolean,
    val manualSensorAvailable: Boolean,
    val absoluteExposureMetadataAvailable: Boolean,
    val previewSize: Size,
    val rawSize: Size?,
    val trackingSize: Size?,
    val matrixCandidates: List<CameraCombinationCandidate>,
    val systemCandidates: List<CameraCombinationCandidate>,
    val hardwareLevel: Int?,
    val physicalSize: SizeF?,
    val focalLengthMm: Float,
    val aperture: Float,
    val maximumDisplayZoom: Float,
    val activeArray: Rect?,
    val minimumFocusDistanceDiopters: Float,
    val focusDistanceCalibration: Int?,
    val focusDistanceResultAvailable: Boolean,
    val isLogicalMultiCamera: Boolean,
    val sensorOrientationDegrees: Int,
) {
    fun cameraInfo(
        activePhysicalCameraId: String?,
        profile: CameraSessionProfile,
        previewFpsRange: Range<Int>?,
        status: String,
    ): CameraUiInfo {
        val fps = previewFpsRange?.upper ?: 30
        return CameraUiInfo(
            cameraId = descriptor.cameraId,
            logicalCameraId = descriptor.logicalCameraId,
            runtimeCameraId = if (route.isLogicalFallback) {
                descriptor.logicalCameraId
            } else {
                descriptor.cameraId
            },
            physicalCameraId = route.physicalCameraId,
            activePhysicalCameraId = activePhysicalCameraId,
            lensFacing = descriptor.lensFacing,
            rawHardwareAvailable = rawHardwareAvailable,
            rawAvailable = rawHardwareAvailable && profile.usesRaw,
            manualSensorAvailable = manualSensorAvailable,
            absoluteExposureMetadataAvailable = absoluteExposureMetadataAvailable,
            focalLengthMm = focalLengthMm,
            aperture = aperture,
            minimumFocusDistanceDiopters = minimumFocusDistanceDiopters,
            focusDistanceCalibration = focusDistanceCalibration,
            focusDistanceResultAvailable = focusDistanceResultAvailable,
            isLogicalMultiCamera = isLogicalMultiCamera,
            sensorWidthMm = physicalSize?.width ?: 0f,
            sensorHeightMm = physicalSize?.height ?: 0f,
            sensorOrientationDegrees = sensorOrientationDegrees,
            maxDisplayZoom = maximumDisplayZoom,
            previewSize = previewSize,
            previewFps = fps,
            previewFpsLower = previewFpsRange?.lower ?: fps,
            previewFpsUpper = fps,
            activeArray = activeArray,
            status = status,
        )
    }
}

/**
 * Resolves vendor Camera2 declarations into an immutable open configuration.
 *
 * No camera device or capture session is opened here. Recovery indices and profile selection stay
 * in CameraController so existing HAL fallback order remains unchanged.
 */
internal class CameraOpenConfigurationResolver(
    private val cameraManager: CameraManager,
    private val cameraCatalog: CameraCatalog,
    private val localized: (zh: String, en: String) -> String,
) {
    fun resolve(
        requestedCameraId: String?,
        routeCandidateIndex: Int,
        meteringPipelineMode: MeteringPipelineMode,
    ): CameraOpenConfiguration? {
        val discovered = cameraCatalog.discover()
        val descriptor = discovered.firstOrNull { it.cameraId == requestedCameraId }
            ?: cameraCatalog.preferredCamera(discovered)
            ?: return null
        val routes = CameraRouteResolver.candidates(descriptor)
        val route = routes.getOrElse(routeCandidateIndex) { routes.last() }
        val logicalCharacteristics = cameraManager.getCameraCharacteristics(route.cameraIdToOpen)
        val streamCharacteristics = route.physicalCameraId?.let {
            cameraManager.getCameraCharacteristics(it)
        } ?: logicalCharacteristics
        val capabilities = streamCharacteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        ) ?: intArrayOf()
        val rawCapability = capabilities.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
        )
        val manualSensorAvailable = capabilities.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        )
        val map = streamCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException(
                localized("相机没有输出配置", "Camera has no output configuration"),
            )
        val hardwareLevel = streamCharacteristics.get(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
        )
        val rawHardwareAvailable = RawSensorFormatPolicy.supportsBayerMetering(
            rawCapabilityAdvertised = rawCapability,
            hasRawSensorOutput = !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty(),
            isLegacyHardware =
                hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            colorFilterArrangement = streamCharacteristics.get(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT,
            ),
        )
        val previewSize = CameraStreamSelector.choosePreviewSize(streamCharacteristics)
            ?: throw IllegalStateException(
                localized("没有合适的预览尺寸", "No suitable preview size is available"),
            )
        val trackingSize = CameraStreamSelector.chooseTrackingSize(map, previewSize)
        val rawSize = if (rawHardwareAvailable) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.minByOrNull { it.width.toLong() * it.height.toLong() }
                ?: throw IllegalStateException(
                    localized(
                        "RAW 能力存在，但没有 RAW_SENSOR 尺寸",
                        "RAW is reported but no RAW_SENSOR size is available",
                    ),
                )
        } else {
            null
        }
        val matrix = CameraCombinationMatrix.inspect(
            characteristics = streamCharacteristics,
            previewSize = previewSize,
            rawSize = rawSize,
            yuvSize = trackingSize,
        )
        val physicalSize = streamCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val activeArray = streamCharacteristics.get(
            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
        ) ?: streamCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        return CameraOpenConfiguration(
            descriptor = descriptor,
            route = route,
            logicalCharacteristics = logicalCharacteristics,
            streamCharacteristics = streamCharacteristics,
            rawHardwareAvailable = rawHardwareAvailable,
            trackingHardwareAvailable = trackingSize != null,
            manualSensorAvailable = manualSensorAvailable,
            absoluteExposureMetadataAvailable = supportsAbsoluteExposureMetadata(
                streamCharacteristics,
                capabilities,
            ),
            previewSize = previewSize,
            rawSize = rawSize,
            trackingSize = trackingSize,
            matrixCandidates = CameraCombinationPolicy.orderedCandidates(
                mode = MeteringPipelineMode.AUTO,
                capabilities = matrix.capabilities,
                mandatoryGuaranteedProfiles = matrix.mandatoryGuaranteedProfiles,
            ),
            systemCandidates = CameraCombinationPolicy.orderedCandidates(
                mode = meteringPipelineMode,
                capabilities = matrix.capabilities,
                mandatoryGuaranteedProfiles = matrix.mandatoryGuaranteedProfiles,
            ),
            hardwareLevel = hardwareLevel,
            physicalSize = physicalSize,
            focalLengthMm = streamCharacteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
            )?.firstOrNull() ?: 0f,
            aperture = streamCharacteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES,
            )?.firstOrNull() ?: 0f,
            maximumDisplayZoom = (streamCharacteristics.get(
                CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM,
            ) ?: 5f).coerceIn(1f, 5f),
            activeArray = activeArray,
            minimumFocusDistanceDiopters = streamCharacteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
            ) ?: 0f,
            focusDistanceCalibration = streamCharacteristics.get(
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION,
            ),
            focusDistanceResultAvailable = runCatching {
                streamCharacteristics.availableCaptureResultKeys
            }.getOrDefault(emptyList()).contains(CaptureResult.LENS_FOCUS_DISTANCE),
            isLogicalMultiCamera = logicalCharacteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            )?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true,
            sensorOrientationDegrees = streamCharacteristics.get(
                CameraCharacteristics.SENSOR_ORIENTATION,
            ) ?: 90,
        )
    }

    private fun supportsAbsoluteExposureMetadata(
        characteristics: CameraCharacteristics,
        capabilities: IntArray,
    ): Boolean {
        val resultKeys = runCatching {
            characteristics.availableCaptureResultKeys
        }.getOrDefault(emptyList())
        return ExposureMetadataPolicy.supportsAbsoluteMetering(
            readSensorSettingsAvailable = capabilities.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS,
            ),
            manualSensorAvailable = capabilities.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
            ),
            exposureTimeResultAvailable = resultKeys.contains(CaptureResult.SENSOR_EXPOSURE_TIME),
            sensitivityResultAvailable = resultKeys.contains(CaptureResult.SENSOR_SENSITIVITY),
            apertureResultAvailable = resultKeys.contains(CaptureResult.LENS_APERTURE),
            staticApertureAvailable = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES,
            )?.isNotEmpty() == true,
        )
    }
}
