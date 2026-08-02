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
) {
    val landscapeAspect: Float
        get() = (maxOf(widthMm, heightMm) / minOf(widthMm, heightMm)).toFloat()

    companion object {
        val ALL = listOf(
            FrameFormat("135", "135 · 3:2", 36.0, 24.0),
            FrameFormat("half", "半格 · 4:3", 24.0, 18.0),
            FrameFormat("645", "6×4.5 · 4:3", 56.0, 42.0),
            FrameFormat("66", "6×6 · 1:1", 56.0, 56.0),
            FrameFormat("67", "6×7 · 5:4", 70.0, 56.0),
            FrameFormat("69", "6×9 · 3:2", 84.0, 56.0),
            FrameFormat("xpan", "XPan · 65:24", 65.0, 24.0),
        )
    }
}

data class CameraUiInfo(
    val cameraId: String = "",
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
    val status: String = "正在准备相机",
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

enum class MeteringSource {
    RAW,
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

class MeterState(context: Context) {
    private val preferences =
        context.getSharedPreferences("raw_light_meter_state", Context.MODE_PRIVATE)

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

    var exposureCompSteps: Int =
        if (storedCompensationDivisor == 6) {
            storedCompensationSteps.coerceIn(-30, 30)
        } else {
            (storedCompensationSteps * 2).coerceIn(-30, 30)
        }
        set(value) {
            field = value.coerceIn(-30, 30)
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
    var measuring: Boolean = false
    var cameraInfo: CameraUiInfo = CameraUiInfo()
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

    fun updateSetting(key: SettingKey, value: String) {
        when (key) {
            SettingKey.APERTURE_STEP -> apertureStep = enumValue(value, apertureStep)
            SettingKey.SHUTTER_STEP -> shutterStep = enumValue(value, shutterStep)
            SettingKey.METERING_MODE -> meteringMode = enumValue(value, meteringMode)
            SettingKey.LANGUAGE -> menuLanguage = enumValue(value, menuLanguage)
            SettingKey.THEME -> appTheme = enumValue(value, appTheme)
            SettingKey.HANDEDNESS -> handedness = enumValue(value, handedness)
        }
        persist()
    }

    fun settingValue(key: SettingKey): String = when (key) {
        SettingKey.APERTURE_STEP -> apertureStep.name
        SettingKey.SHUTTER_STEP -> shutterStep.name
        SettingKey.METERING_MODE -> meteringMode.name
        SettingKey.LANGUAGE -> menuLanguage.name
        SettingKey.THEME -> appTheme.name
        SettingKey.HANDEDNESS -> handedness.name
    }

    fun equivalent35mm(): Int? {
        val info = cameraInfo
        if (info.focalLengthMm <= 0f || info.sensorWidthMm <= 0f || info.sensorHeightMm <= 0f) {
            return null
        }
        val sensorAspect = info.sensorWidthMm / info.sensorHeightMm
        val screenFrameAspect =
            if (frameLandscape) frameFormat.landscapeAspect else 1f / frameFormat.landscapeAspect
        val assumedDisplayDegrees = if (landscape) 90 else 0
        val relativeRotation =
            (info.sensorOrientationDegrees - assumedDisplayDegrees + 360) % 360
        val frameAspect =
            if (relativeRotation == 90 || relativeRotation == 270) {
                1f / screenFrameAspect
            } else {
                screenFrameAspect
            }
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
        return (info.focalLengthMm * 43.266615 / effectiveDiagonal * zoom).toInt()
    }

    private fun persist() {
        preferences.edit()
            .putInt("iso_index", isoIndex)
            .putInt("exposure_comp_steps", exposureCompSteps)
            .putInt("exposure_comp_divisor", 6)
            .putFloat("zoom", zoom)
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
            .putString("metering_mode", meteringMode.name)
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
    val maxShutterLogSeconds: Double get() = shutterLogSeconds.first()

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
