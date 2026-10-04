package com.lightmeter.rawmeter

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import org.opencv.core.Point

/** Motion seed passed into optical flow; vision remains authoritative whenever it is reliable. */
internal data class MotionPrediction(
    val dx: Float,
    val dy: Float,
    val rollRadians: Float,
    val screenXRotation: Float,
    val screenYRotation: Float,
    val displayHorizontalScale: Float,
    val displayVerticalScale: Float,
    /** Physical FOV-derived scales reserved for long off-screen angular integration. */
    val angularHorizontalScale: Float,
    val angularVerticalScale: Float,
    val displayOriented: Boolean,
    val xTranslationTrusted: Boolean,
    val yTranslationTrusted: Boolean,
)

internal data class AffineMotion(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val reliable: Boolean,
    val inlierCount: Int,
) {
    fun map(point: Point): Point = Point(
        m00 * point.x + m01 * point.y + m02,
        m10 * point.x + m11 * point.y + m12,
    )
}

internal data class GyroCalibrationSnapshot(
    val horizontalScale: Double?,
    val horizontalSamples: Int,
    val verticalScale: Double?,
    val verticalSamples: Int,
)

/** Camera-space direction retained while a marker is outside the projected image. */
internal data class OffscreenRay(
    var x: Double,
    var y: Double,
    var z: Double,
)

/**
 * Owns gyroscope registration, screen-axis integration and visual self-calibration.
 *
 * This component predicts only a bounded seed. OpenCvZoneMarkerTracker decides whether that seed
 * is trustworthy and never lets it override a reliable RANSAC/optical-flow result.
 */
internal class ZoneGyroscopeMotion(
    private val textureView: TextureView,
    private val meterState: MeterState,
) {
    private class AxisCalibration {
        val samples = ArrayDeque<Double>()

        fun estimate(): Double? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            } else {
                sorted[middle]
            }
        }
    }

    private data class OrientationCalibration(
        val horizontal: AxisCalibration = AxisCalibration(),
        val vertical: AxisCalibration = AxisCalibration(),
    )

    private val accumulationLock = Any()
    private val calibrationLock = Any()
    private val calibrations = mutableMapOf<Int, OrientationCalibration>()
    private val sensorManager = textureView.context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var timestampNs = 0L
    private var accumulatedScreenX = 0f
    private var accumulatedScreenY = 0f
    private var accumulatedScreenZ = 0f
    private var registered = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!registered || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
            val previousTimestamp = timestampNs
            timestampNs = event.timestamp
            if (previousTimestamp == 0L) return
            val dt = ((event.timestamp - previousTimestamp) * 1e-9f).coerceIn(0f, 0.08f)
            if (dt <= 0f) return
            val deviceX = event.values[0]
            val deviceY = event.values[1]
            val (screenX, screenY) = when (textureView.display?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_90 -> deviceY to -deviceX
                Surface.ROTATION_180 -> -deviceX to -deviceY
                Surface.ROTATION_270 -> -deviceY to deviceX
                else -> deviceX to deviceY
            }
            synchronized(accumulationLock) {
                accumulatedScreenX += screenX * dt
                accumulatedScreenY += screenY * dt
                accumulatedScreenZ += event.values[2] * dt
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(handler: Handler) {
        resetAccumulation()
        if (!registered && gyroscope != null) {
            registered = sensorManager?.registerListener(
                listener,
                gyroscope,
                SensorManager.SENSOR_DELAY_GAME,
                handler,
            ) == true
        }
    }

    fun stop() {
        if (registered) sensorManager?.unregisterListener(listener)
        registered = false
        resetAccumulation()
    }

    fun resetAccumulation() {
        timestampNs = 0L
        synchronized(accumulationLock) {
            accumulatedScreenX = 0f
            accumulatedScreenY = 0f
            accumulatedScreenZ = 0f
        }
    }

    fun consume(width: Int, height: Int, displayOriented: Boolean): MotionPrediction {
        val rotations = synchronized(accumulationLock) {
            Triple(accumulatedScreenX, accumulatedScreenY, accumulatedScreenZ).also {
                accumulatedScreenX = 0f
                accumulatedScreenY = 0f
                accumulatedScreenZ = 0f
            }
        }
        val screenXRotation = rotations.first
        val screenYRotation = rotations.second
        val screenZRotation = rotations.third
        val displayDegrees = displayRotationDegrees()
        val info = meterState.cameraInfo
        val screenAspect = width.toDouble() / height.coerceAtLeast(1).toDouble()
        var horizontalFov = Math.toRadians(64.0)
        var verticalFov = 2.0 * atan(tan(horizontalFov / 2.0) / screenAspect)
        val visibleSensor = meterState.previewVisibleSensorSizeMm()
        if (info.focalLengthMm > 0f && visibleSensor != null) {
            val relativeRotation = (info.sensorOrientationDegrees - displayDegrees + 360) % 360
            val frameAspectInSensor = if (relativeRotation == 90 || relativeRotation == 270) {
                1.0 / screenAspect
            } else {
                screenAspect
            }
            val sensorAspect = visibleSensor.width / visibleSensor.height
            val cropWidth: Double
            val cropHeight: Double
            if (sensorAspect > frameAspectInSensor) {
                cropHeight = visibleSensor.height
                cropWidth = cropHeight * frameAspectInSensor
            } else {
                cropWidth = visibleSensor.width
                cropHeight = cropWidth / frameAspectInSensor
            }
            val displayZoom = meterState.zoom.toDouble().coerceAtLeast(1.0)
            val physicalWidth = (
                if (relativeRotation == 90 || relativeRotation == 270) cropHeight else cropWidth
                ) / displayZoom
            val physicalHeight = (
                if (relativeRotation == 90 || relativeRotation == 270) cropWidth else cropHeight
                ) / displayZoom
            horizontalFov = 2.0 * atan(physicalWidth / (2.0 * info.focalLengthMm))
            verticalFov = 2.0 * atan(physicalHeight / (2.0 * info.focalLengthMm))
        }
        val metadataHorizontalScale = 1.0 / (2.0 * tan(horizontalFov.coerceAtLeast(0.12) / 2.0))
        val metadataVerticalScale = 1.0 / (2.0 * tan(verticalFov.coerceAtLeast(0.12) / 2.0))
        val learned = snapshot(width, height)
        val horizontalScale = blendedScale(
            metadataHorizontalScale,
            learned.horizontalScale,
            learned.horizontalSamples,
        )
        val verticalScale = blendedScale(
            metadataVerticalScale,
            learned.verticalScale,
            learned.verticalSamples,
        )
        val horizontalTrusted = learned.horizontalSamples >= MIN_TRUSTED_SAMPLES
        val verticalTrusted = learned.verticalSamples >= MIN_TRUSTED_SAMPLES
        val displayDx = screenYRotation * horizontalScale
        val displayDy = screenXRotation * verticalScale
        val analysisDelta = ZoneCoordinateMapper.displayVectorToAnalysis(
            displayDx,
            displayDy,
            displayOriented,
            displayDegrees,
        )
        val (xTrusted, yTrusted) = displayTrustToAnalysisTrust(
            horizontalTrusted,
            verticalTrusted,
            displayOriented,
            displayDegrees,
        )
        val maxDxFraction = if (xTrusted) MAX_TRUSTED_FRAME_FRACTION else MAX_SEED_FRAME_FRACTION
        val maxDyFraction = if (yTrusted) MAX_TRUSTED_FRAME_FRACTION else MAX_SEED_FRAME_FRACTION
        return MotionPrediction(
            dx = (analysisDelta.x * width)
                .coerceIn(-width * maxDxFraction, width * maxDxFraction).toFloat(),
            dy = (analysisDelta.y * height)
                .coerceIn(-height * maxDyFraction, height * maxDyFraction).toFloat(),
            rollRadians = screenZRotation.coerceIn(-0.35f, 0.35f),
            screenXRotation = screenXRotation,
            screenYRotation = screenYRotation,
            displayHorizontalScale = horizontalScale.toFloat(),
            displayVerticalScale = verticalScale.toFloat(),
            angularHorizontalScale = metadataHorizontalScale.toFloat(),
            angularVerticalScale = metadataVerticalScale.toFloat(),
            displayOriented = displayOriented,
            xTranslationTrusted = xTrusted,
            yTranslationTrusted = yTrusted,
        )
    }

    fun affine(
        prediction: MotionPrediction,
        width: Int,
        height: Int,
        trustedTranslationOnly: Boolean,
    ): AffineMotion {
        val angle = prediction.rollRadians.toDouble()
        val cos = cos(angle)
        val sin = sin(angle)
        val centerX = width / 2.0
        val centerY = height / 2.0
        val dx = if (!trustedTranslationOnly || prediction.xTranslationTrusted) prediction.dx.toDouble() else 0.0
        val dy = if (!trustedTranslationOnly || prediction.yTranslationTrusted) prediction.dy.toDouble() else 0.0
        return AffineMotion(
            cos,
            -sin,
            dx + centerX - cos * centerX + sin * centerY,
            sin,
            cos,
            dy + centerY - sin * centerX - cos * centerY,
            false,
            0,
        )
    }

    fun map(point: Point, prediction: MotionPrediction, width: Int, height: Int): Point =
        affine(prediction, width, height, trustedTranslationOnly = false).map(point)

    /**
     * Project an off-screen ray in angular space instead of repeatedly applying a planar pixel
     * translation. The latter is accurate near the optical axis but accumulates large error once
     * a marker has moved beyond the frame boundary.
     */
    fun mapAngular(
        point: Point,
        prediction: MotionPrediction,
        width: Int,
        height: Int,
    ): Point {
        val displayPoint = ZoneCoordinateMapper.analysisPointToDisplayNormalized(
            point,
            width,
            height,
            prediction.displayOriented,
            displayRotationDegrees(),
        )
        val horizontalScale = prediction.angularHorizontalScale.toDouble().coerceAtLeast(0.05)
        val verticalScale = prediction.angularVerticalScale.toDouble().coerceAtLeast(0.05)
        val horizontalAngle = atan((displayPoint.x - 0.5) / horizontalScale)
        val verticalAngle = atan((displayPoint.y - 0.5) / verticalScale)
        val projectedX = 0.5 + tan(
            (horizontalAngle + prediction.screenYRotation)
                .coerceIn(-MAX_PROJECTED_RAY_ANGLE, MAX_PROJECTED_RAY_ANGLE),
        ) * horizontalScale
        val projectedY = 0.5 + tan(
            (verticalAngle + prediction.screenXRotation)
                .coerceIn(-MAX_PROJECTED_RAY_ANGLE, MAX_PROJECTED_RAY_ANGLE),
        ) * verticalScale
        val roll = prediction.rollRadians.toDouble()
        val rollCos = cos(roll)
        val rollSin = sin(roll)
        val centeredX = projectedX - 0.5
        val centeredY = projectedY - 0.5
        val rolledDisplay = Point(
            0.5 + rollCos * centeredX - rollSin * centeredY,
            0.5 + rollSin * centeredX + rollCos * centeredY,
        )
        return ZoneCoordinateMapper.displayNormalizedToAnalysisPoint(
            rolledDisplay,
            width,
            height,
            prediction.displayOriented,
            displayRotationDegrees(),
        )
    }

    fun rayFromAnalysisPoint(
        point: Point,
        prediction: MotionPrediction,
        width: Int,
        height: Int,
    ): OffscreenRay {
        val displayPoint = ZoneCoordinateMapper.analysisPointToDisplayNormalized(
            point,
            width,
            height,
            prediction.displayOriented,
            displayRotationDegrees(),
        )
        val horizontalScale = prediction.angularHorizontalScale.toDouble().coerceAtLeast(0.05)
        val verticalScale = prediction.angularVerticalScale.toDouble().coerceAtLeast(0.05)
        return normalizeRay(
            OffscreenRay(
                x = (displayPoint.x - 0.5) / horizontalScale,
                y = (displayPoint.y - 0.5) / verticalScale,
                z = 1.0,
            ),
        )
    }

    /** Apply one common orthonormal camera rotation; angular spacing between rays is preserved. */
    fun advanceRay(ray: OffscreenRay, prediction: MotionPrediction) {
        val yaw = prediction.screenYRotation.toDouble()
        val pitch = -prediction.screenXRotation.toDouble()
        val roll = prediction.rollRadians.toDouble()

        val yawCos = cos(yaw)
        val yawSin = sin(yaw)
        val yawX = yawCos * ray.x + yawSin * ray.z
        val yawZ = -yawSin * ray.x + yawCos * ray.z

        val pitchCos = cos(pitch)
        val pitchSin = sin(pitch)
        val pitchY = pitchCos * ray.y - pitchSin * yawZ
        val pitchZ = pitchSin * ray.y + pitchCos * yawZ

        val rollCos = cos(roll)
        val rollSin = sin(roll)
        ray.x = rollCos * yawX - rollSin * pitchY
        ray.y = rollSin * yawX + rollCos * pitchY
        ray.z = pitchZ
        normalizeRay(ray)
    }

    fun analysisPointFromRay(
        ray: OffscreenRay,
        prediction: MotionPrediction,
        width: Int,
        height: Int,
    ): Point {
        val horizontalScale = prediction.angularHorizontalScale.toDouble().coerceAtLeast(0.05)
        val verticalScale = prediction.angularVerticalScale.toDouble().coerceAtLeast(0.05)
        val safeZ = when {
            ray.z >= MIN_PROJECTABLE_RAY_Z -> ray.z
            ray.z <= -MIN_PROJECTABLE_RAY_Z -> ray.z
            else -> if (ray.z >= 0.0) MIN_PROJECTABLE_RAY_Z else -MIN_PROJECTABLE_RAY_Z
        }
        val displayPoint = Point(
            0.5 + (ray.x / safeZ).coerceIn(-MAX_PROJECTED_TANGENT, MAX_PROJECTED_TANGENT) *
                horizontalScale,
            0.5 + (ray.y / safeZ).coerceIn(-MAX_PROJECTED_TANGENT, MAX_PROJECTED_TANGENT) *
                verticalScale,
        )
        return ZoneCoordinateMapper.displayNormalizedToAnalysisPoint(
            displayPoint,
            width,
            height,
            prediction.displayOriented,
            displayRotationDegrees(),
        )
    }

    private fun normalizeRay(ray: OffscreenRay): OffscreenRay {
        val length = kotlin.math.sqrt(ray.x * ray.x + ray.y * ray.y + ray.z * ray.z)
            .coerceAtLeast(1e-9)
        ray.x /= length
        ray.y /= length
        ray.z /= length
        return ray
    }

    fun updateCalibration(
        visualMotion: AffineMotion,
        prediction: MotionPrediction,
        width: Int,
        height: Int,
        minimumInliers: Int,
    ) {
        if (visualMotion.inlierCount < minimumInliers) return
        val affineScale = hypot(visualMotion.m00, visualMotion.m10)
        if (abs(affineScale - 1.0) > MAX_AFFINE_SCALE_ERROR) return
        val center = Point(width / 2.0, height / 2.0)
        val mappedCenter = visualMotion.map(center)
        val displayDelta = ZoneCoordinateMapper.analysisVectorToDisplay(
            mappedCenter.x - center.x,
            mappedCenter.y - center.y,
            prediction.displayOriented,
            displayRotationDegrees(),
        )
        val orientationKey = orientationKey(width, height)
        synchronized(calibrationLock) {
            val calibration = calibrations.getOrPut(orientationKey) { OrientationCalibration() }
            calibration.horizontal.addObservation(
                prediction.screenYRotation.toDouble(),
                displayDelta.x,
                width,
            )
            calibration.vertical.addObservation(
                prediction.screenXRotation.toDouble(),
                displayDelta.y,
                height,
            )
        }
    }

    fun snapshot(width: Int, height: Int): GyroCalibrationSnapshot = synchronized(calibrationLock) {
        val calibration = calibrations[orientationKey(width, height)]
        GyroCalibrationSnapshot(
            calibration?.horizontal?.estimate(),
            calibration?.horizontal?.samples?.size ?: 0,
            calibration?.vertical?.estimate(),
            calibration?.vertical?.samples?.size ?: 0,
        )
    }

    private fun AxisCalibration.addObservation(angleRadians: Double, pixelDelta: Double, frameExtent: Int) {
        if (abs(angleRadians) < MIN_CALIBRATION_ANGLE ||
            abs(angleRadians) > MAX_CALIBRATION_ANGLE ||
            abs(pixelDelta) < MIN_CALIBRATION_PIXELS ||
            frameExtent <= 0
        ) return
        val candidate = pixelDelta / angleRadians / frameExtent
        if (!candidate.isFinite() || candidate !in MIN_NORMALIZED_SCALE..MAX_NORMALIZED_SCALE) return
        val current = estimate()
        if (current != null && samples.size >= MIN_OUTLIER_FILTER_SAMPLES &&
            candidate !in (current * MIN_OBSERVATION_RATIO)..(current * MAX_OBSERVATION_RATIO)
        ) return
        samples.addLast(candidate)
        while (samples.size > MAX_CALIBRATION_SAMPLES) samples.removeFirst()
    }

    private fun blendedScale(metadata: Double, learned: Double?, sampleCount: Int): Double {
        if (learned == null || sampleCount < MIN_BLEND_SAMPLES) return metadata
        // Motion blur, parallax and moving subjects can all produce a visually plausible affine
        // transform with the wrong gyro ratio. Never let those samples redefine the lens FOV.
        val boundedLearned = learned.coerceIn(
            metadata * MIN_LEARNED_TO_METADATA_RATIO,
            metadata * MAX_LEARNED_TO_METADATA_RATIO,
        )
        val learnedWeight = ((sampleCount - MIN_BLEND_SAMPLES + 1).toDouble() /
            BLEND_FULL_CONFIDENCE_SAMPLES).coerceIn(0.0, 1.0)
        return metadata * (1.0 - learnedWeight) + boundedLearned * learnedWeight
    }

    private fun displayTrustToAnalysisTrust(
        horizontalTrusted: Boolean,
        verticalTrusted: Boolean,
        displayOriented: Boolean,
        displayDegrees: Int,
    ): Pair<Boolean, Boolean> {
        if (displayOriented) return horizontalTrusted to verticalTrusted
        return when (displayDegrees) {
            90, 270 -> verticalTrusted to horizontalTrusted
            else -> horizontalTrusted to verticalTrusted
        }
    }

    private fun displayRotationDegrees(): Int = when (textureView.display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun orientationKey(width: Int, height: Int): Int = if (width >= height) 0 else 1

    private companion object {
        const val MAX_AFFINE_SCALE_ERROR = 0.06
        const val MIN_CALIBRATION_ANGLE = 0.0008
        const val MAX_CALIBRATION_ANGLE = 0.09
        const val MIN_CALIBRATION_PIXELS = 0.35
        const val MIN_NORMALIZED_SCALE = 0.18
        const val MAX_NORMALIZED_SCALE = 2.5
        const val MIN_OBSERVATION_RATIO = 0.58
        const val MAX_OBSERVATION_RATIO = 1.72
        const val MIN_OUTLIER_FILTER_SAMPLES = 4
        const val MAX_CALIBRATION_SAMPLES = 21
        const val MIN_BLEND_SAMPLES = 3
        const val MIN_TRUSTED_SAMPLES = 6
        const val BLEND_FULL_CONFIDENCE_SAMPLES = 8.0
        const val MIN_LEARNED_TO_METADATA_RATIO = 0.55
        const val MAX_LEARNED_TO_METADATA_RATIO = 1.8
        const val MAX_SEED_FRAME_FRACTION = 0.16
        const val MAX_TRUSTED_FRAME_FRACTION = 0.42
        const val MAX_PROJECTED_RAY_ANGLE = 1.45
        const val MIN_PROJECTABLE_RAY_Z = 0.08
        const val MAX_PROJECTED_TANGENT = 12.0
    }
}
