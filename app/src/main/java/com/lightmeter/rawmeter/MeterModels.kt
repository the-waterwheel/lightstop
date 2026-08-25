package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Rect
import android.util.Size
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FrameFormat(
    val id: String,
    val label: String,
    val widthMm: Double,
    val heightMm: Double,
    val englishLabel: String = label,
) {
    val landscapeAspect: Float
        get() = (maxOf(widthMm, heightMm) / minOf(widthMm, heightMm)).toFloat()

    /** Representative exposed-image diagonal used for cross-format focal-length equivalence. */
    val diagonalMm: Double
        get() = sqrt(widthMm * widthMm + heightMm * heightMm)

    /** Converts a 135-equivalent field of view into a lens focal length for this format. */
    fun focalLengthForFullFrameEquivalent(fullFrameEquivalentMm: Double): Int =
        (fullFrameEquivalentMm * diagonalMm / FULL_FRAME_DIAGONAL_MM).roundToInt()

    fun displayLabel(language: MenuLanguage): String =
        if (language == MenuLanguage.ENGLISH) englishLabel else label

    companion object {
        val ALL = listOf(
            FrameFormat("135", "135 · 3:2", 36.0, 24.0),
            FrameFormat("half", "半格 · 4:3", 24.0, 18.0, "Half-frame · 4:3"),
            FrameFormat("645", "6×4.5 · 4:3", 56.0, 42.0),
            FrameFormat("66", "6×6 · 1:1", 56.0, 56.0),
            FrameFormat("67", "6×7 · 5:4", 70.0, 56.0),
            FrameFormat("69", "6×9 · 3:2", 84.0, 56.0),
            FrameFormat("65x24", "65:24", 65.0, 24.0),
            // Append new formats so persisted indices from earlier versions keep their meaning.
            FrameFormat("612", "6×12 · 2:1", 112.0, 56.0),
            FrameFormat("617", "6×17 · 3:1", 168.0, 56.0),
            FrameFormat("45", "4×5 · 5:4", 120.0, 96.0),
            FrameFormat("57", "5×7 · 7:5", 168.0, 120.0),
            FrameFormat("810", "8×10 · 5:4", 240.0, 192.0),
        )

        private const val FULL_FRAME_DIAGONAL_MM = 43.266615
    }
}

data class CameraUiInfo(
    val cameraId: String = "",
    val logicalCameraId: String = cameraId,
    val physicalCameraId: String? = null,
    val rawAvailable: Boolean = false,
    val manualSensorAvailable: Boolean = false,
    val focalLengthMm: Float = 0f,
    val aperture: Float = 0f,
    val sensorWidthMm: Float = 0f,
    val sensorHeightMm: Float = 0f,
    val sensorOrientationDegrees: Int = 90,
    val maxDisplayZoom: Float = 5f,
    val previewSize: Size? = null,
    val previewFps: Int = 0,
    val activeArray: Rect? = null,
    val status: String = "",
)

data class MeterReading(
    val sceneEv100: Double,
    val rawLuma: Double,
    val clippedFraction: Double,
    val frameCount: Int,
    val captureIso: Int,
    val exposureTimeNs: Long,
    val aperture: Float,
    val source: MeteringSource = MeteringSource.RAW,
)

/** A Zone touch expressed in both the film-frame viewport and the full preview TextureView. */
data class ZoneMeteringTarget(
    val frameX: Float,
    val frameY: Float,
    val previewX: Float,
    val previewY: Float,
    val previewFrameWidthFraction: Float,
    val previewFrameHeightFraction: Float,
)

internal data class PreviewLumaReference(
    val samples: FloatArray,
    val gridSize: Int,
    val halfSpan: Float,
)

internal data class RawMeterPoint(
    val sensorX: Float,
    val sensorY: Float,
    val matchScore: Double,
)

enum class MeteringSource {
    RAW,
    YUV_PREVIEW,
    ISP_PREVIEW,
}

enum class ExposureLockMode {
    APERTURE,
    SHUTTER,
}

enum class ExposureStep(val denominator: Int) {
    FULL(1),
    HALF(2),
    THIRD(3),
}

enum class MeteringMode {
    CENTER_WEIGHTED,
    SPOT,
    ANGLE,
}

enum class MeteringPipelineMode {
    /** Uses RAW when possible and automatically falls back when a device rejects it. */
    AUTO,

    /** Stable path: retains RAW while keeping it isolated from processed YUV requests. */
    ISOLATED,

    /** Compatibility path: uses one ISP-processed sample and never opens a RAW output. */
    FAST,

    ;

    companion object {
        fun fromStored(value: String?): MeteringPipelineMode = when (value) {
            // COMPATIBLE was the old name of the no-RAW processed pipeline.
            "COMPATIBLE" -> FAST
            else -> entries.firstOrNull { it.name == value } ?: AUTO
        }
    }
}

/** User calibration exposes RAW only when both the selected mode and camera can provide it. */
internal object CalibrationStreamPolicy {
    fun includesRaw(mode: MeteringPipelineMode, rawSupported: Boolean): Boolean =
        mode != MeteringPipelineMode.FAST && rawSupported
}

enum class MenuLanguage {
    CHINESE,
    ENGLISH,
}

enum class AppTheme {
    LIGHT,
    DARK,
}

enum class Handedness {
    RIGHT,
    LEFT,
}

/**
 * Zone point placement methods. Constant names are persisted in preferences and must not be
 * renamed. BUTTON is "button only" (仅按键): the mark button places points at the frame center.
 * TOUCH is "button and touch" (按键与触屏): the mark button and direct preview taps both place
 * points; touch points are refined on the RAW stream by template matching.
 */
enum class ZoneMarkingMethod {
    BUTTON,
    TOUCH,
}

class MeterState(context: Context) {
    private val preferences =
        context.getSharedPreferences("raw_light_meter_state", Context.MODE_PRIVATE)
    private val cameraSelectionStore = CameraSelectionStore(context)
    private val cameraCalibrationStore = CameraCalibrationStore(context)
    private val vignettingCalibrationStore = VignettingCalibrationStore(context)

    val isoValues = intArrayOf(
        6, 8, 10, 12, 16, 20, 25, 32, 40, 50, 64, 80,
        100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000,
        6400, 8000, 10000, 12800,
    )

    var isoIndex: Int = preferences.getInt("iso_index", isoValues.indexOf(100))
        set(value) {
            field = value.coerceIn(0, isoValues.lastIndex)
            persist()
        }
    val iso: Int get() = isoValues[isoIndex]

    private val storedCompensationDivisor =
        preferences.getInt("exposure_comp_divisor", 3)
    private val storedCompensationSteps =
        preferences.getInt("exposure_comp_steps", 0)

    var exposureCompensationStep: ExposureCompensationStep = preferences.enumValue(
        "exposure_compensation_step",
        ExposureCompensationStep.SIXTH,
    )
        private set

    var exposureCompSteps: Int =
        ExposureCompensationDial.snap(if (storedCompensationDivisor == 6) {
            storedCompensationSteps.coerceIn(-30, 30)
        } else {
            (storedCompensationSteps * 2).coerceIn(-30, 30)
        }, exposureCompensationStep)
        set(value) {
            field = ExposureCompensationDial.snap(value, exposureCompensationStep)
            persist()
        }
    val exposureCompEv: Double get() = exposureCompSteps / 6.0

    var zoom: Float = preferences.getFloat("zoom", 1f)
        set(value) {
            field = value.coerceAtLeast(1f)
            persist()
        }

    var frameIndex: Int = preferences.getInt("frame_index", 0)
        set(value) {
            field = value.mod(FrameFormat.ALL.size)
            persist()
        }
    val frameFormat: FrameFormat get() = FrameFormat.ALL[frameIndex]

    var landscape: Boolean = preferences.getBoolean("landscape", false)
        set(value) {
            field = value
            persist()
        }

    var frameLandscape: Boolean = true
        private set

    var exposureLockMode: ExposureLockMode =
        if (preferences.getInt("exposure_lock_mode", 0) == 1) {
            ExposureLockMode.SHUTTER
        } else {
            ExposureLockMode.APERTURE
        }
        private set

    var lockedApertureStop: Double =
        preferences.getFloat("locked_aperture_stop", ExposureMath.apertureStop(5.6).toFloat())
            .toDouble()
        private set

    var lockedShutterLogSeconds: Double =
        preferences.getFloat(
            "locked_shutter_log_seconds",
            ExposureMath.log2(1.0 / 125.0).toFloat(),
        ).toDouble()
        private set

    var isoAdjustMode: Boolean = false

    var apertureStep: ExposureStep = preferences.enumValue(
        "aperture_step",
        ExposureStep.THIRD,
    )
        set(value) {
            field = value
            if (exposureLockMode == ExposureLockMode.APERTURE) {
                lockedApertureStop =
                    ExposureMath.nearestApertureStop(lockedApertureStop, value)
            }
            persist()
        }

    var shutterStep: ExposureStep = preferences.enumValue(
        "shutter_step",
        ExposureStep.THIRD,
    )
        set(value) {
            field = value
            if (exposureLockMode == ExposureLockMode.SHUTTER) {
                lockedShutterLogSeconds =
                    ExposureMath.nearestShutterLogSeconds(lockedShutterLogSeconds, value)
            }
            persist()
        }

    var meteringMode: MeteringMode = preferences.enumValue(
        "metering_mode",
        MeteringMode.SPOT,
    )

    var angleMeteringDegrees: Int = AngleMeteringMath.nearestSelectableDegrees(
        preferences.getInt(
            angleMeteringKey(cameraSelectionStore.selectedCameraId.orEmpty()),
            AngleMeteringMath.DEFAULT_DEGREES,
        ),
    )
        private set

    // Preserve the old no-RAW behavior instead of silently moving existing users to isolation.
    var meteringPipelineMode: MeteringPipelineMode = MeteringPipelineMode.fromStored(
        preferences.getString("metering_pipeline_mode", null),
    )

    var exposurePreviewMode: ExposurePreviewMode = preferences.enumValue(
        "exposure_preview_mode",
        ExposurePreviewMode.OFF,
    )

    var zoneMarkingMethod: ZoneMarkingMethod = preferences.enumValue(
        "zone_marking_method",
        ZoneMarkingMethod.BUTTON,
    )

    @Volatile
    var menuLanguage: MenuLanguage = preferences.enumValue(
        "menu_language",
        MenuLanguage.CHINESE,
    )

    var appTheme: AppTheme = preferences.enumValue(
        "app_theme",
        AppTheme.LIGHT,
    )

    var handedness: Handedness = preferences.enumValue(
        "handedness",
        Handedness.RIGHT,
    )

    val isDarkMode: Boolean get() = appTheme == AppTheme.DARK
    val isLeftHanded: Boolean get() = handedness == Handedness.LEFT

    var sceneEv100: Double? = null
    var lastReading: MeterReading? = null
    var lastNormalReading: MeterReading? = null
    var measuring: Boolean = false
    var cameraInfo: CameraUiInfo = CameraUiInfo(
        status = if (menuLanguage == MenuLanguage.ENGLISH) {
            "Preparing camera"
        } else {
            "正在准备相机"
        },
    )
    var availableCameras: List<CameraDescriptor> = emptyList()
        private set
    var selectedCameraId: String = cameraSelectionStore.selectedCameraId.orEmpty()
        private set
    var transientMessage: String? = null

    val effectiveEv100: Double?
        get() = sceneEv100?.minus(exposureCompEv)

    init {
        // Older builds stored coordinates derived from rounded display labels (for example
        // f/5.6 instead of the exact five-stop coordinate). Migrate the active lock onto the
        // selected, evenly-spaced EV grid so its red index line and tick agree immediately.
        if (exposureLockMode == ExposureLockMode.APERTURE) {
            lockedApertureStop =
                ExposureMath.nearestApertureStop(lockedApertureStop, apertureStep)
        } else {
            lockedShutterLogSeconds =
                ExposureMath.nearestShutterLogSeconds(lockedShutterLogSeconds, shutterStep)
        }
        meteringMode = MeteringAreaPolicy.resolveForPipeline(
            meteringMode,
            meteringPipelineMode,
        )
        persist()
    }

    fun selectFrame(index: Int) {
        frameIndex = index.coerceIn(0, FrameFormat.ALL.lastIndex)
        // Film-frame long edges always remain horizontal on screen. Phone orientation only
        // changes which physical sensor axis supplies that horizontal field of view.
        frameLandscape = true
        persist()
    }

    fun setExposureLockMode(mode: ExposureLockMode, evAtIso: Double) {
        if (mode == exposureLockMode) return
        if (mode == ExposureLockMode.APERTURE) {
            lockedApertureStop = ExposureMath.nearestApertureStop(
                lockedShutterLogSeconds + evAtIso,
                apertureStep,
            )
        } else {
            lockedShutterLogSeconds = ExposureMath.nearestShutterLogSeconds(
                lockedApertureStop - evAtIso,
                shutterStep,
            )
        }
        exposureLockMode = mode
        clampExposureLocks()
        persist()
    }

    fun setExposureLockModeFromCoordinates(
        mode: ExposureLockMode,
        apertureCoordinate: Double,
        shutterCoordinate: Double,
    ) {
        exposureLockMode = mode
        if (mode == ExposureLockMode.APERTURE) {
            lockedApertureStop = ExposureMath.nearestApertureStop(
                apertureCoordinate,
                apertureStep,
            )
        } else {
            lockedShutterLogSeconds = ExposureMath.nearestShutterLogSeconds(
                shutterCoordinate,
                shutterStep,
            )
        }
        clampExposureLocks()
        persist()
    }

    fun setLockedExposureCoordinate(coordinate: Double) {
        if (exposureLockMode == ExposureLockMode.APERTURE) {
            lockedApertureStop = ExposureMath.nearestApertureStop(
                coordinate.coerceIn(
                    ExposureMath.minApertureStop,
                    ExposureMath.maxApertureStop,
                ),
                apertureStep,
            )
        } else {
            lockedShutterLogSeconds = ExposureMath.nearestShutterLogSeconds(
                coordinate.coerceIn(
                    ExposureMath.minShutterLogSeconds,
                    ExposureMath.maxShutterLogSeconds,
                ),
                shutterStep,
            )
        }
    }

    fun snapLockedExposure() {
        if (exposureLockMode == ExposureLockMode.APERTURE) {
            lockedApertureStop =
                ExposureMath.nearestApertureStop(lockedApertureStop, apertureStep)
        } else {
            lockedShutterLogSeconds =
                ExposureMath.nearestShutterLogSeconds(lockedShutterLogSeconds, shutterStep)
        }
        persist()
    }

    /** Returns false when a requested combination is deliberately unavailable. */
    fun updateSetting(key: SettingKey, value: String): Boolean {
        when (key) {
            SettingKey.APERTURE_STEP -> apertureStep = enumValue(value, apertureStep)
            SettingKey.SHUTTER_STEP -> shutterStep = enumValue(value, shutterStep)
            SettingKey.EXPOSURE_COMPENSATION_STEP -> {
                exposureCompensationStep = enumValue(value, exposureCompensationStep)
                exposureCompSteps = ExposureCompensationDial.snap(
                    exposureCompSteps,
                    exposureCompensationStep,
                )
            }
            SettingKey.METERING_MODE -> {
                val requested = enumValue(value, meteringMode)
                if (!MeteringAreaPolicy.canSelect(requested, meteringPipelineMode)) return false
                meteringMode = requested
            }
            SettingKey.METERING_PIPELINE -> {
                meteringPipelineMode = enumValue(value, meteringPipelineMode)
                meteringMode = MeteringAreaPolicy.resolveForPipeline(
                    meteringMode,
                    meteringPipelineMode,
                )
            }
            SettingKey.EXPOSURE_PREVIEW ->
                exposurePreviewMode = enumValue(value, exposurePreviewMode)
            SettingKey.ZONE_MARKING_METHOD ->
                zoneMarkingMethod = enumValue(value, zoneMarkingMethod)
            SettingKey.LANGUAGE -> menuLanguage = enumValue(value, menuLanguage)
            SettingKey.THEME -> appTheme = enumValue(value, appTheme)
            SettingKey.HANDEDNESS -> handedness = enumValue(value, handedness)
        }
        persist()
        return true
    }

    fun selectAngleMeteringDegrees(value: Int): Boolean {
        val next = AngleMeteringMath.nearestSelectableDegrees(value)
        if (next == angleMeteringDegrees) return false
        angleMeteringDegrees = next
        preferences.edit()
            .putInt(angleMeteringKey(selectedCameraId), angleMeteringDegrees)
            .apply()
        return true
    }

    fun updateCameraCatalog(cameras: List<CameraDescriptor>): String? {
        availableCameras = cameras
        if (cameras.isEmpty()) {
            selectedCameraId = ""
            return null
        }
        val stored = selectedCameraId.ifBlank {
            cameraSelectionStore.selectedCameraId.orEmpty()
        }
        val selected = cameras.firstOrNull {
            it.cameraId == stored && !isCameraHidden(it.cameraId)
        }
            ?: preferredCamera(cameras.filterNot { isCameraHidden(it.cameraId) })
            ?: preferredCamera(cameras)
        selectedCameraId = selected?.cameraId.orEmpty()
        cameraSelectionStore.selectedCameraId = selectedCameraId
        zoom = preferences.getFloat(cameraZoomKey(selectedCameraId), 1f).coerceAtLeast(1f)
        angleMeteringDegrees = AngleMeteringMath.nearestSelectableDegrees(
            preferences.getInt(
                angleMeteringKey(selectedCameraId),
                AngleMeteringMath.DEFAULT_DEGREES,
            ),
        )
        return selectedCameraId.ifBlank { null }
    }

    fun selectCamera(cameraId: String): Boolean {
        if (cameraId == selectedCameraId) return false
        if (availableCameras.none { it.cameraId == cameraId }) return false
        if (selectedCameraId.isNotBlank()) {
            preferences.edit().putFloat(cameraZoomKey(selectedCameraId), zoom).apply()
        }
        selectedCameraId = cameraId
        cameraSelectionStore.selectedCameraId = cameraId
        zoom = preferences.getFloat(cameraZoomKey(cameraId), 1f).coerceAtLeast(1f)
        angleMeteringDegrees = AngleMeteringMath.nearestSelectableDegrees(
            preferences.getInt(angleMeteringKey(cameraId), AngleMeteringMath.DEFAULT_DEGREES),
        )
        sceneEv100 = null
        lastReading = null
        lastNormalReading = null
        return true
    }

    fun currentCamera(): CameraDescriptor? =
        availableCameras.firstOrNull { it.cameraId == selectedCameraId }

    fun cameraName(camera: CameraDescriptor): String =
        cameraNote(camera.cameraId).ifBlank { camera.automaticName(menuLanguage) }

    fun cameraNote(cameraId: String): String = cameraSelectionStore.note(cameraId)

    fun cameraCalibrationRecord(cameraId: String): CameraCalibrationRecord? =
        cameraCalibrationStore.record(cameraId)

    fun previewCalibratedSceneEv100(sceneEv100: Double, source: MeteringSource): Double {
        val cameraId = cameraInfo.cameraId.ifBlank { selectedCameraId }.ifBlank { "0" }
        return ExposurePreviewMath.previewCalibratedSceneEv100(
            measuredSceneEv100 = sceneEv100,
            sourceCorrectionEv = cameraCalibrationStore.totalCorrection(cameraId, source),
            previewCorrectionEv = cameraCalibrationStore.totalCorrection(
                cameraId,
                MeteringSource.YUV_PREVIEW,
            ),
        )
    }

    fun previewCameraCorrectionEv(): Double {
        val cameraId = cameraInfo.cameraId.ifBlank { selectedCameraId }.ifBlank { "0" }
        return cameraCalibrationStore.totalCorrection(cameraId, MeteringSource.YUV_PREVIEW)
    }

    fun cameraCalibrationHistory(cameraId: String): List<CameraCalibrationRecord> =
        cameraCalibrationStore.history(cameraId)

    fun vignettingCalibrationInfo(cameraId: String): VignettingCalibrationInfo? =
        vignettingCalibrationStore.info(cameraId)

    fun vignettingCalibrationHistory(cameraId: String): List<VignettingCalibrationInfo> =
        vignettingCalibrationStore.history(cameraId)

    fun refreshVignettingCalibration(cameraId: String) {
        vignettingCalibrationStore.invalidate(cameraId)
    }

    fun hasCalibrationArtifacts(): Boolean =
        cameraCalibrationStore.hasCalibrationArtifacts() ||
            vignettingCalibrationStore.hasCalibrationArtifacts()

    fun setCameraNote(cameraId: String, note: String) {
        cameraSelectionStore.setNote(cameraId, note)
    }

    fun isCameraHidden(cameraId: String): Boolean =
        cameraSelectionStore.isHidden(cameraId)

    /** Returns false when hiding would leave no visible camera. */
    fun setCameraHidden(cameraId: String, hidden: Boolean): Boolean {
        if (hidden) {
            val visibleCount = availableCameras.count { !isCameraHidden(it.cameraId) }
            if (visibleCount <= 1 && !isCameraHidden(cameraId)) return false
        }
        cameraSelectionStore.setHidden(cameraId, hidden)
        return true
    }

    var showHiddenCameras: Boolean
        get() = cameraSelectionStore.showHiddenCameras
        set(value) {
            cameraSelectionStore.showHiddenCameras = value
        }

    fun visibleCameras(): List<CameraDescriptor> = availableCameras.filter {
        showHiddenCameras || !isCameraHidden(it.cameraId)
    }

    private fun preferredCamera(cameras: List<CameraDescriptor>): CameraDescriptor? =
        cameras.firstOrNull {
            it.lensRole == CameraLensRole.AUTOMATIC && it.rawAvailable
        } ?: cameras.firstOrNull {
            it.lensRole == CameraLensRole.MAIN && it.rawAvailable
        } ?: cameras.firstOrNull {
            it.rawAvailable
        } ?: cameras.firstOrNull {
            it.lensRole == CameraLensRole.AUTOMATIC
        } ?: cameras.firstOrNull {
            it.lensRole == CameraLensRole.MAIN
        } ?: cameras.firstOrNull()

    private fun cameraZoomKey(cameraId: String) = "camera_zoom_$cameraId"

    private fun angleMeteringKey(cameraId: String) = "angle_metering_degrees_$cameraId"

    fun settingValue(key: SettingKey): String = when (key) {
        SettingKey.APERTURE_STEP -> apertureStep.name
        SettingKey.SHUTTER_STEP -> shutterStep.name
        SettingKey.EXPOSURE_COMPENSATION_STEP -> exposureCompensationStep.name
        SettingKey.METERING_MODE -> meteringMode.name
        SettingKey.METERING_PIPELINE -> meteringPipelineMode.name
        SettingKey.EXPOSURE_PREVIEW -> exposurePreviewMode.name
        SettingKey.ZONE_MARKING_METHOD -> zoneMarkingMethod.name
        SettingKey.LANGUAGE -> menuLanguage.name
        SettingKey.THEME -> appTheme.name
        SettingKey.HANDEDNESS -> handedness.name
    }

    private fun effectiveFullFrameEquivalentMm(): Double? {
        val info = cameraInfo
        if (info.focalLengthMm <= 0f || info.sensorWidthMm <= 0f || info.sensorHeightMm <= 0f) {
            return null
        }
        val sensorAspect = info.sensorWidthMm / info.sensorHeightMm
        val frameAspect = currentSensorFrameAspect().toFloat()
        val effectiveWidth: Double
        val effectiveHeight: Double
        if (sensorAspect > frameAspect) {
            effectiveHeight = info.sensorHeightMm.toDouble()
            effectiveWidth = effectiveHeight * frameAspect
        } else {
            effectiveWidth = info.sensorWidthMm.toDouble()
            effectiveHeight = effectiveWidth / frameAspect
        }
        val effectiveDiagonal = sqrt(
            effectiveWidth * effectiveWidth + effectiveHeight * effectiveHeight,
        )
        if (effectiveDiagonal <= 0.0) return null
        return info.focalLengthMm * 43.266615 / effectiveDiagonal * zoom
    }

    /** Conventional 135-equivalent focal length retained for compatibility and diagnostics. */
    fun equivalent35mm(): Int? = effectiveFullFrameEquivalentMm()?.toInt()

    /** Lens focal length on the selected film format that gives the current preview field of view. */
    fun equivalentFrameFocalMm(): Int? = effectiveFullFrameEquivalentMm()
        ?.let(frameFormat::focalLengthForFullFrameEquivalent)

    fun maximumAngleMeteringDegrees(): Double? {
        val info = cameraInfo
        if (selectedCameraId.isNotBlank() && info.cameraId != selectedCameraId) return null
        return AngleMeteringMath.maximumSupportedDegrees(
            focalLengthMm = info.focalLengthMm.toDouble(),
            sensorWidthMm = info.sensorWidthMm.toDouble(),
            sensorHeightMm = info.sensorHeightMm.toDouble(),
            sensorFrameAspect = currentSensorFrameAspect(),
            zoom = zoom.toDouble(),
        )
    }

    fun angleMeteringRoiFraction(): Float? {
        val info = cameraInfo
        if (selectedCameraId.isNotBlank() && info.cameraId != selectedCameraId) return null
        return AngleMeteringMath.roiFraction(
            angleDegrees = angleMeteringDegrees,
            focalLengthMm = info.focalLengthMm.toDouble(),
            sensorWidthMm = info.sensorWidthMm.toDouble(),
            sensorHeightMm = info.sensorHeightMm.toDouble(),
            sensorFrameAspect = currentSensorFrameAspect(),
            zoom = zoom.toDouble(),
        )
    }

    private fun currentSensorFrameAspect(): Double {
        val info = cameraInfo
        val screenAspect = if (frameLandscape) {
            frameFormat.landscapeAspect
        } else {
            1f / frameFormat.landscapeAspect
        }
        val assumedDisplayDegrees = if (landscape) 90 else 0
        val relativeRotation =
            (info.sensorOrientationDegrees - assumedDisplayDegrees + 360) % 360
        return if (relativeRotation == 90 || relativeRotation == 270) {
            1.0 / screenAspect
        } else {
            screenAspect.toDouble()
        }
    }

    private fun persist() {
        preferences.edit()
            .putInt("iso_index", isoIndex)
            .putInt("exposure_comp_steps", exposureCompSteps)
            .putInt("exposure_comp_divisor", 6)
            .putFloat("zoom", zoom)
            .putFloat(cameraZoomKey(selectedCameraId), zoom)
            .putInt("frame_index", frameIndex)
            .putBoolean("frame_landscape", frameLandscape)
            .putBoolean("landscape", landscape)
            .putInt(
                "exposure_lock_mode",
                if (exposureLockMode == ExposureLockMode.SHUTTER) 1 else 0,
            )
            .putFloat("locked_aperture_stop", lockedApertureStop.toFloat())
            .putFloat("locked_shutter_log_seconds", lockedShutterLogSeconds.toFloat())
            .putString("aperture_step", apertureStep.name)
            .putString("shutter_step", shutterStep.name)
            .putString("exposure_compensation_step", exposureCompensationStep.name)
            .putString("metering_mode", meteringMode.name)
            .putString("metering_pipeline_mode", meteringPipelineMode.name)
            .putString("exposure_preview_mode", exposurePreviewMode.name)
            .putInt(angleMeteringKey(selectedCameraId), angleMeteringDegrees)
            .putString("zone_marking_method", zoneMarkingMethod.name)
            .putString("menu_language", menuLanguage.name)
            .putString("app_theme", appTheme.name)
            .putString("handedness", handedness.name)
            .apply()
    }

    private fun clampExposureLocks() {
        lockedApertureStop = lockedApertureStop.coerceIn(
            ExposureMath.minApertureStop,
            ExposureMath.maxApertureStop,
        )
        lockedShutterLogSeconds = lockedShutterLogSeconds.coerceIn(
            ExposureMath.minShutterLogSeconds,
            ExposureMath.maxShutterLogSeconds,
        )
    }

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enumValue(
        key: String,
        fallback: T,
    ): T = enumValue(getString(key, null), fallback)

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

data class ExposurePair(
    val apertureIndex: Int,
    val shutterIndex: Int,
) {
    val aperture: Double get() = ExposureMath.apertures[apertureIndex]
    val shutterSeconds: Double get() = ExposureMath.shutters[shutterIndex]
}

data class ExposureScaleTick(
    val coordinate: Double,
    val nominalValue: Double,
)

object ExposureMath {
    val apertures = doubleArrayOf(
        1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8,
        3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0,
        10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0,
        29.0, 32.0,
    )

    val shutters = doubleArrayOf(
        30.0, 25.0, 20.0, 15.0, 13.0, 10.0, 8.0, 6.0, 5.0, 4.0,
        3.2, 2.5, 2.0, 1.6, 1.3, 1.0, 0.8, 0.6, 0.5, 0.4,
        1.0 / 3.0, 0.25, 0.2, 1.0 / 6.0, 0.125, 0.1, 1.0 / 13.0,
        1.0 / 15.0, 1.0 / 20.0, 1.0 / 25.0, 1.0 / 30.0,
        1.0 / 40.0, 1.0 / 50.0, 1.0 / 60.0, 1.0 / 80.0,
        1.0 / 100.0, 1.0 / 125.0, 1.0 / 160.0, 1.0 / 200.0,
        1.0 / 250.0, 1.0 / 320.0, 1.0 / 400.0, 1.0 / 500.0,
        1.0 / 640.0, 1.0 / 800.0, 1.0 / 1000.0, 1.0 / 1250.0,
        1.0 / 1600.0, 1.0 / 2000.0, 1.0 / 2500.0, 1.0 / 3200.0,
        1.0 / 4000.0, 1.0 / 5000.0, 1.0 / 6400.0, 1.0 / 8000.0,
    )

    private val halfStopApertures = doubleArrayOf(
        1.0, 1.2, 1.4, 1.7, 2.0, 2.4, 2.8, 3.4, 4.0, 4.8,
        5.6, 6.7, 8.0, 9.5, 11.0, 13.0, 16.0, 19.0, 22.0, 27.0, 32.0,
    )
    private val halfStopShutters = doubleArrayOf(
        30.0, 20.0, 15.0, 10.0, 8.0, 6.0, 4.0, 3.0, 2.0, 1.5,
        1.0, 0.7, 0.5, 1.0 / 3.0, 0.25, 1.0 / 6.0, 0.125, 0.1,
        1.0 / 15.0, 1.0 / 20.0, 1.0 / 30.0, 1.0 / 45.0, 1.0 / 60.0,
        1.0 / 90.0, 1.0 / 125.0, 1.0 / 180.0, 1.0 / 250.0,
        1.0 / 350.0, 1.0 / 500.0, 1.0 / 750.0, 1.0 / 1000.0,
        1.0 / 1500.0, 1.0 / 2000.0, 1.0 / 3000.0, 1.0 / 4000.0,
        1.0 / 6000.0, 1.0 / 8000.0,
    )
    private val fullStopApertureTicks = buildApertureTicks(
        apertures.filterIndexed { index, _ -> index % 3 == 0 }.toDoubleArray(),
        ExposureStep.FULL.denominator,
    )
    private val halfStopApertureTicks = buildApertureTicks(
        halfStopApertures,
        ExposureStep.HALF.denominator,
    )
    private val thirdStopApertureTicks = buildApertureTicks(
        apertures,
        ExposureStep.THIRD.denominator,
    )
    private val fullStopShutterTicks = buildShutterTicks(
        shutters.filterIndexed { index, _ -> index % 3 == 0 }.toDoubleArray(),
        ExposureStep.FULL.denominator,
    )
    private val halfStopShutterTicks = buildShutterTicks(
        halfStopShutters,
        ExposureStep.HALF.denominator,
    )
    private val thirdStopShutterTicks = buildShutterTicks(
        shutters,
        ExposureStep.THIRD.denominator,
    )
    val apertureStops: DoubleArray =
        thirdStopApertureTicks.map(ExposureScaleTick::coordinate).toDoubleArray()
    val shutterLogSeconds: DoubleArray =
        thirdStopShutterTicks.map(ExposureScaleTick::coordinate).toDoubleArray()
    val minApertureStop: Double get() = apertureStops.first()
    val maxApertureStop: Double get() = apertureStops.last()
    val minShutterLogSeconds: Double get() = shutterLogSeconds.last()
    /** Last coordinate with a printed scale tick (30 seconds). */
    val maxMarkedShutterLogSeconds: Double get() = shutterLogSeconds.first()

    /**
     * Long-exposure tail. It deliberately has no printed ticks after 30 seconds, but the
     * centered value remains available and snaps at the configured stop interval.
     */
    val maxShutterLogSeconds: Double get() = maxMarkedShutterLogSeconds + 7.0

    fun primaryPair(
        ev100: Double?,
        iso: Int,
        lockedApertureIndex: Int?,
        lockedShutterIndex: Int?,
    ): ExposurePair {
        if (ev100 == null) {
            return ExposurePair(nearestIndex(apertures, 5.6), shutters.indexOfFirst { it <= 1.0 / 125.0 })
        }
        val exposureValueAtIso = ev100 + log2(iso / 100.0)
        lockedApertureIndex?.let { apertureIndex ->
            val aperture = apertures[apertureIndex]
            val desiredTime = aperture * aperture / 2.0.pow(exposureValueAtIso)
            return ExposurePair(apertureIndex, nearestIndex(shutters, desiredTime))
        }
        lockedShutterIndex?.let { shutterIndex ->
            val desiredAperture = sqrt(shutters[shutterIndex] * 2.0.pow(exposureValueAtIso))
            return ExposurePair(nearestIndex(apertures, desiredAperture), shutterIndex)
        }

        var apertureIndex = nearestIndex(apertures, 5.6)
        var desiredTime =
            apertures[apertureIndex].pow(2.0) / 2.0.pow(exposureValueAtIso)
        if (desiredTime > shutters.first()) {
            apertureIndex = apertures.lastIndex
            desiredTime = apertures[apertureIndex].pow(2.0) / 2.0.pow(exposureValueAtIso)
        } else if (desiredTime < shutters.last()) {
            apertureIndex = 0
            desiredTime = apertures[apertureIndex].pow(2.0) / 2.0.pow(exposureValueAtIso)
        }
        return ExposurePair(apertureIndex, nearestIndex(shutters, desiredTime))
    }

    fun pairForAperture(ev100: Double?, iso: Int, apertureIndex: Int): ExposurePair {
        if (ev100 == null) return ExposurePair(apertureIndex, shutters.indexOfFirst { it <= 1.0 / 125.0 })
        val evAtIso = ev100 + log2(iso / 100.0)
        val aperture = apertures[apertureIndex]
        val shutter = aperture * aperture / 2.0.pow(evAtIso)
        return ExposurePair(apertureIndex, nearestIndex(shutters, shutter))
    }

    fun formatAperture(value: Double): String =
        if (value >= 10.0 || abs(value - value.toInt()) < 0.01) {
            "f/${value.toInt()}"
        } else {
            "f/${"%.1f".format(value)}"
        }

    fun formatShutter(seconds: Double): String = when {
        seconds >= 1.0 -> if (abs(seconds - seconds.toInt()) < 0.02) {
            "${seconds.toInt()}″"
        } else {
            "${"%.1f".format(seconds)}″"
        }
        seconds >= 0.3 -> "${"%.1f".format(seconds)}″"
        else -> "1/${(1.0 / seconds).roundToInt()}"
    }

    fun log2(value: Double): Double = ln(value) / ln(2.0)

    fun apertureStop(value: Double): Double = 2.0 * log2(value)

    fun apertureFromStop(stop: Double): Double = 2.0.pow(stop / 2.0)

    fun apertureTicks(step: ExposureStep): List<ExposureScaleTick> = when (step) {
        ExposureStep.FULL -> fullStopApertureTicks
        ExposureStep.HALF -> halfStopApertureTicks
        ExposureStep.THIRD -> thirdStopApertureTicks
    }

    fun shutterTicks(step: ExposureStep): List<ExposureScaleTick> = when (step) {
        ExposureStep.FULL -> fullStopShutterTicks
        ExposureStep.HALF -> halfStopShutterTicks
        ExposureStep.THIRD -> thirdStopShutterTicks
    }

    fun apertureStops(step: ExposureStep): DoubleArray =
        apertureTicks(step).map(ExposureScaleTick::coordinate).toDoubleArray()

    fun shutterStops(step: ExposureStep): DoubleArray =
        shutterTicks(step).map(ExposureScaleTick::coordinate).toDoubleArray()

    fun apertureValueForCoordinate(coordinate: Double, step: ExposureStep): Double =
        nominalValueAtCoordinate(apertureTicks(step), coordinate)
            ?: apertureFromStop(coordinate)

    fun shutterValueForCoordinate(coordinate: Double, step: ExposureStep): Double =
        nominalValueAtCoordinate(shutterTicks(step), coordinate)
            ?: 2.0.pow(coordinate)

    fun nearestApertureStop(
        target: Double,
        step: ExposureStep = ExposureStep.THIRD,
    ): Double {
        val values = apertureStops(step)
        return values.minByOrNull { abs(it - target) } ?: values.first()
    }

    fun nearestShutterLogSeconds(
        target: Double,
        step: ExposureStep = ExposureStep.THIRD,
    ): Double {
        val values = shutterStops(step)
        val markedMaximum = values.maxOrNull() ?: return target
        if (target > markedMaximum) {
            return ((target.coerceAtMost(maxShutterLogSeconds) * step.denominator).roundToInt()
                .toDouble() / step.denominator).coerceAtMost(maxShutterLogSeconds)
        }
        return values.minByOrNull { abs(it - target) } ?: values.first()
    }

    private fun nearestIndex(values: DoubleArray, target: Double): Int =
        values.indices.minByOrNull { abs(log2(values[it] / target)) } ?: 0

    private fun buildApertureTicks(
        nominalValues: DoubleArray,
        denominator: Int,
    ): List<ExposureScaleTick> = nominalValues.mapIndexed { index, nominalValue ->
        ExposureScaleTick(
            coordinate = index.toDouble() / denominator,
            nominalValue = nominalValue,
        )
    }

    private fun buildShutterTicks(
        nominalValues: DoubleArray,
        denominator: Int,
    ): List<ExposureScaleTick> {
        val oneSecondIndex = nominalValues.indexOfFirst { abs(it - 1.0) < 0.0001 }
        require(oneSecondIndex >= 0) { "Shutter scale must contain one second" }
        return nominalValues.mapIndexed { index, nominalValue ->
            ExposureScaleTick(
                coordinate = (oneSecondIndex - index).toDouble() / denominator,
                nominalValue = nominalValue,
            )
        }
    }

    private fun nominalValueAtCoordinate(
        ticks: List<ExposureScaleTick>,
        coordinate: Double,
    ): Double? = ticks.firstOrNull { abs(it.coordinate - coordinate) < 0.001 }
        ?.nominalValue
}
