package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.os.Build
import kotlin.math.abs
import kotlin.math.sqrt

/** Calibrated geometry for one actual YUV output, never an equivalent-focal-length guess. */
internal data class MotionDistanceGeometry(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double, val skew: Double,
    val sensorToCamera: DistanceRotation,
    val focusDiopters: Float,
) {
    fun ray(x: Double, y: Double): DistanceVector {
        val ny = (y - cy) / fy
        return DistanceVector((x - cx - skew * ny) / fx, ny, 1.0)
    }

    fun compatible(other: MotionDistanceGeometry): Boolean =
        abs(fx / other.fx - 1) < 0.001 && abs(fy / other.fy - 1) < 0.001 &&
            abs(cx - other.cx) < 0.1 && abs(cy - other.cy) < 0.1 &&
            abs(focusDiopters - other.focusDiopters) < 0.03 && sensorToCamera == other.sensorToCamera

    companion object {
        fun canAttempt(chars: CameraCharacteristics): Boolean =
            chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME &&
                chars.get(CameraCharacteristics.LENS_POSE_REFERENCE) in setOf(
                    CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA, CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE) &&
                chars.get(CameraCharacteristics.LENS_POSE_ROTATION)?.size == 4 &&
                chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.size == 5 &&
                chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                    it == chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                } == true

        fun from(chars: CameraCharacteristics, result: CaptureResult, width: Int, height: Int, sampleStep: Int = 1): MotionDistanceGeometry? {
            if (width <= 0 || height <= 0 || sampleStep <= 0) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                result.get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { !it.isFinite() || abs(it - 1f) > 0.001f } == true) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                result.get(CaptureResult.SENSOR_PIXEL_MODE)?.let { it != CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT } == true) return null
            if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) !=
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) return null
            if (chars.get(CameraCharacteristics.LENS_POSE_REFERENCE) !in setOf(
                    CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA, CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE)) return null
            // Unknown stabilisation can turn OIS/EIS image shifts into fictitious translation.
            if (result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE) != CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF ||
                result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE) != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF) return null
            if (result.get(CaptureResult.LENS_STATE) != CameraMetadata.LENS_STATE_STATIONARY) return null
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
            if (exposure !in 1..20_000_000L) return null
            val intrinsic = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
                ?: chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) ?: return null
            val pose = chars.get(CameraCharacteristics.LENS_POSE_ROTATION) ?: return null
            if (intrinsic.size != 5 || intrinsic.any { !it.isFinite() } || intrinsic[0] <= 0 || intrinsic[1] <= 0 ||
                pose.size != 4 || pose.any { !it.isFinite() }) return null
            val norm = sqrt(pose.sumOf { it.toDouble() * it })
            if (abs(norm - 1) > 0.01) return null
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
            val pre = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: return null
            val crop = result.get(CaptureResult.SCALER_CROP_REGION) ?: return null
            // Cropping/distortion coordinate conversion is ambiguous when these arrays differ.
            // Exclude it until that particular device geometry can be verified.
            if (active != pre || crop.width() <= 0 || crop.height() <= 0 || !active.contains(crop)) return null
            val distortion = result.get(CaptureResult.DISTORTION_CORRECTION_MODE)
            if (distortion == null || distortion == CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) {
                val coefficients = chars.get(CameraCharacteristics.LENS_DISTORTION) ?: return null
                if (coefficients.any { !it.isFinite() || abs(it) > 0.0001f }) return null
            }
            var cropW = crop.width().toDouble()
            var cropH = crop.height().toDouble()
            val aspect = width.toDouble() / height
            if (cropW / cropH > aspect) cropW = cropH * aspect else cropH = cropW / aspect
            val sx = width / cropW
            val sy = height / cropH
            val left = crop.exactCenterX() - cropW / 2
            val top = crop.exactCenterY() - cropH / 2
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return null
            if (!focus.isFinite() || focus < 0f) return null
            return MotionDistanceGeometry(intrinsic[0]*sx, intrinsic[1]*sy,
                (intrinsic[2] + pre.left - left)*sx - 0.5 / sampleStep,
                (intrinsic[3] + pre.top - top)*sy - 0.5 / sampleStep, intrinsic[4]*sx,
                DistanceRotation(pose[0]/norm, pose[1]/norm, pose[2]/norm, pose[3]/norm), focus)
        }
    }
}
