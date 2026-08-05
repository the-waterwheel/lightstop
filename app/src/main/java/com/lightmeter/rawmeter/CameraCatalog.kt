package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A user-selectable lens, backed by either a logical camera or one of its physical lenses. */
data class CameraDescriptor(
    val cameraId: String,
    val logicalCameraId: String = cameraId,
    val physicalCameraId: String? = null,
    val lensRole: CameraLensRole = CameraLensRole.OTHER,
    val lensFacing: Int,
    val rawAvailable: Boolean,
    val manualSensorAvailable: Boolean,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val sensorOrientationDegrees: Int,
    val maxDigitalZoom: Float,
) {
    val focalLengthMm: Float get() = focalLengthsMm.firstOrNull() ?: 0f

    fun automaticName(language: MenuLanguage): String {
        val roleName = when (lensRole) {
            CameraLensRole.MAIN -> if (language == MenuLanguage.ENGLISH) "Main camera" else "主摄"
            CameraLensRole.ULTRA_WIDE ->
                if (language == MenuLanguage.ENGLISH) "Ultra-wide" else "超广角"
            CameraLensRole.TELEPHOTO ->
                if (language == MenuLanguage.ENGLISH) "Telephoto" else "长焦"
            CameraLensRole.FRONT ->
                if (language == MenuLanguage.ENGLISH) "Front camera" else "前置摄像头"
            CameraLensRole.EXTERNAL ->
                if (language == MenuLanguage.ENGLISH) "External camera" else "外接摄像头"
            CameraLensRole.OTHER -> ""
        }
        if (roleName.isNotEmpty()) return roleName
        val facing = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT ->
                if (language == MenuLanguage.ENGLISH) "Front camera" else "前置摄像头"
            CameraCharacteristics.LENS_FACING_EXTERNAL ->
                if (language == MenuLanguage.ENGLISH) "External camera" else "外接摄像头"
            else -> if (language == MenuLanguage.ENGLISH) "Rear camera" else "后置摄像头"
        }
        return "$facing $cameraId"
    }

    fun technicalSummary(): String = buildString {
        if (focalLengthsMm.isNotEmpty()) {
            append(focalLengthsMm.joinToString(" / ") { "%.1f mm".format(it) })
        }
        if (apertures.isNotEmpty()) {
            if (isNotEmpty()) append("  ·  ")
            append(apertures.joinToString(" / ") { "f/%.1f".format(it) })
        }
        if (isNotEmpty()) append("  ·  ")
        append(if (rawAvailable) "RAW" else "ISP")
    }
}

enum class CameraLensRole {
    MAIN,
    ULTRA_WIDE,
    TELEPHOTO,
    FRONT,
    EXTERNAL,
    OTHER,
}

/** Reads the camera list without mixing discovery policy into CameraController. */
class CameraCatalog(private val cameraManager: CameraManager) {
    fun discover(): List<CameraDescriptor> {
        val cameraIds = runCatching { cameraManager.cameraIdList }
            .getOrElse { return emptyList() }
        val publicIds = cameraIds.toSet()
        val publicCharacteristics = cameraIds.mapNotNull { cameraId ->
            runCatching { cameraId to cameraManager.getCameraCharacteristics(cameraId) }
                .getOrNull()
        }.toMap()
        val orderedCameraIds = cameraIds.sortedByDescending { cameraId ->
            val capabilities = publicCharacteristics[cameraId]?.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            ) ?: intArrayOf()
            capabilities.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
            )
        }
        val consumedPhysicalIds = mutableSetOf<String>()
        val seenPhysicalIds = mutableSetOf<String>()
        val cameras = buildList {
            orderedCameraIds.forEach { logicalId ->
                if (logicalId in consumedPhysicalIds) return@forEach
                val logicalChars = publicCharacteristics[logicalId] ?: return@forEach
                val capabilities = logicalChars.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                ) ?: intArrayOf()
                val isLogicalMultiCamera = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
                    )
                val physicalIds = if (isLogicalMultiCamera) {
                    logicalChars.physicalCameraIds
                } else {
                    emptySet()
                }
                val physicalCandidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    physicalIds.mapNotNull { physicalId ->
                        val physicalChars = runCatching {
                            cameraManager.getCameraCharacteristics(physicalId)
                        }.getOrNull() ?: return@mapNotNull null
                        physicalId to physicalChars
                    }
                } else {
                    // Android 9 cannot query characteristics for a hidden physical id. Public
                    // auxiliary ids still appear independently later in cameraIdList.
                    emptyList()
                }
                val usablePhysical = physicalCandidates.filter { (_, chars) ->
                    hasPreviewOutput(chars)
                }
                if (usablePhysical.size >= 2) {
                    val logicalFocal = equivalentFocalLength(logicalChars)
                    val mainPhysicalId = usablePhysical.minByOrNull { (_, chars) ->
                        val focal = equivalentFocalLength(chars)
                        when {
                            logicalFocal > 0f && focal > 0f -> abs(focal - logicalFocal)
                            else -> Float.MAX_VALUE
                        }
                    }?.first ?: usablePhysical.first().first
                    val mainFocal = usablePhysical.firstOrNull { it.first == mainPhysicalId }
                        ?.second
                        ?.let(::equivalentFocalLength)
                        ?: logicalFocal
                    usablePhysical.forEach physicalLoop@{ (physicalId, physicalChars) ->
                        if (!seenPhysicalIds.add(physicalId)) return@physicalLoop
                        val role = physicalLensRole(
                            physicalId = physicalId,
                            mainPhysicalId = mainPhysicalId,
                            focalLength = equivalentFocalLength(physicalChars),
                            mainFocalLength = mainFocal,
                        )
                        descriptor(
                            selectionId = if (physicalId == mainPhysicalId) {
                                logicalId
                            } else {
                                "$logicalId@$physicalId"
                            },
                            logicalId = logicalId,
                            physicalId = physicalId,
                            characteristics = physicalChars,
                            lensRole = role,
                        )?.let(::add)
                    }
                    consumedPhysicalIds += physicalIds.intersect(publicIds)
                } else {
                    descriptor(
                        selectionId = logicalId,
                        logicalId = logicalId,
                        physicalId = null,
                        characteristics = logicalChars,
                        lensRole = defaultLensRole(logicalChars),
                    )?.let(::add)
                }
            }
        }
        return cameras.sortedWith(
            compareBy<CameraDescriptor> {
                when (it.lensFacing) {
                    CameraCharacteristics.LENS_FACING_BACK -> 0
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> 1
                    else -> 2
                }
            }.thenBy {
                when (it.lensRole) {
                    CameraLensRole.ULTRA_WIDE -> 0
                    CameraLensRole.MAIN -> 1
                    CameraLensRole.TELEPHOTO -> 2
                    else -> 3
                }
            }.thenByDescending { it.rawAvailable }
                .thenBy { it.focalLengthMm }
                .thenBy { it.cameraId },
        )
    }

    private fun descriptor(
        selectionId: String,
        logicalId: String,
        physicalId: String?,
        characteristics: CameraCharacteristics,
        lensRole: CameraLensRole,
    ): CameraDescriptor? {
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        ) ?: return null
        if (streamMap.getOutputSizes(SurfaceTexture::class.java).isNullOrEmpty()) return null
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        ) ?: intArrayOf()
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return CameraDescriptor(
            cameraId = selectionId,
            logicalCameraId = logicalId,
            physicalCameraId = physicalId,
            lensRole = lensRole,
            lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_EXTERNAL,
            rawAvailable = capabilities.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
            ) && !streamMap.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty(),
            manualSensorAvailable = capabilities.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
            ),
            focalLengthsMm = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList()
                .orEmpty(),
            apertures = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.toList()
                .orEmpty(),
            sensorWidthMm = physicalSize?.width ?: 0f,
            sensorHeightMm = physicalSize?.height ?: 0f,
            sensorOrientationDegrees =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            maxDigitalZoom = max(
                1f,
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?: 1f,
            ),
        )
    }

    private fun hasPreviewOutput(characteristics: CameraCharacteristics): Boolean =
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.isNotEmpty() == true

    private fun firstFocalLength(characteristics: CameraCharacteristics): Float =
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 0f

    private fun equivalentFocalLength(characteristics: CameraCharacteristics): Float {
        val focalLength = firstFocalLength(characteristics)
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return focalLength
        val diagonal = hypot(sensorSize.width.toDouble(), sensorSize.height.toDouble())
        if (focalLength <= 0f || diagonal <= 0.0) return focalLength
        return (focalLength * FULL_FRAME_DIAGONAL_MM / diagonal).toFloat()
    }

    private fun physicalLensRole(
        physicalId: String,
        mainPhysicalId: String,
        focalLength: Float,
        mainFocalLength: Float,
    ): CameraLensRole = when {
        physicalId == mainPhysicalId -> CameraLensRole.MAIN
        mainFocalLength <= 0f || focalLength <= 0f -> CameraLensRole.OTHER
        focalLength < mainFocalLength * 0.8f -> CameraLensRole.ULTRA_WIDE
        focalLength > mainFocalLength * 1.2f -> CameraLensRole.TELEPHOTO
        else -> CameraLensRole.OTHER
    }

    private fun defaultLensRole(characteristics: CameraCharacteristics): CameraLensRole =
        when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> when {
                equivalentFocalLength(characteristics) < ULTRA_WIDE_EQUIVALENT_MM ->
                    CameraLensRole.ULTRA_WIDE
                equivalentFocalLength(characteristics) > TELEPHOTO_EQUIVALENT_MM ->
                    CameraLensRole.TELEPHOTO
                else -> CameraLensRole.MAIN
            }
            CameraCharacteristics.LENS_FACING_FRONT -> CameraLensRole.FRONT
            CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraLensRole.EXTERNAL
            else -> CameraLensRole.OTHER
        }

    private companion object {
        private const val FULL_FRAME_DIAGONAL_MM = 43.266f
        private const val ULTRA_WIDE_EQUIVALENT_MM = 22f
        private const val TELEPHOTO_EQUIVALENT_MM = 40f
    }

    fun preferredCamera(cameras: List<CameraDescriptor>): CameraDescriptor? =
        cameras.firstOrNull {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                it.lensRole == CameraLensRole.MAIN &&
                it.rawAvailable
        } ?: cameras.firstOrNull {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                it.lensRole == CameraLensRole.MAIN
        } ?: cameras.firstOrNull {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK && it.rawAvailable
        } ?: cameras.firstOrNull {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameras.firstOrNull()
}

/** Persists user-facing camera choices separately from metering state and calibration data. */
class CameraSelectionStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("raw_light_meter_cameras", Context.MODE_PRIVATE)

    var selectedCameraId: String?
        get() = preferences.getString(KEY_SELECTED_CAMERA, null)
        set(value) {
            preferences.edit().putString(KEY_SELECTED_CAMERA, value).apply()
        }

    var showHiddenCameras: Boolean
        get() = preferences.getBoolean(KEY_SHOW_HIDDEN, false)
        set(value) {
            preferences.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
        }

    fun note(cameraId: String): String =
        preferences.getString(noteKey(cameraId), "").orEmpty()

    fun setNote(cameraId: String, value: String) {
        val trimmed = value.trim().take(MAX_NOTE_LENGTH)
        val editor = preferences.edit()
        if (trimmed.isEmpty()) editor.remove(noteKey(cameraId))
        else editor.putString(noteKey(cameraId), trimmed)
        editor.apply()
    }

    fun isHidden(cameraId: String): Boolean =
        preferences.getBoolean(hiddenKey(cameraId), false)

    fun setHidden(cameraId: String, hidden: Boolean) {
        preferences.edit().putBoolean(hiddenKey(cameraId), hidden).apply()
    }

    private fun noteKey(cameraId: String) = "note_$cameraId"
    private fun hiddenKey(cameraId: String) = "hidden_$cameraId"

    private companion object {
        private const val KEY_SELECTED_CAMERA = "selected_camera_id"
        private const val KEY_SHOW_HIDDEN = "show_hidden_cameras"
        private const val MAX_NOTE_LENGTH = 40
    }
}
