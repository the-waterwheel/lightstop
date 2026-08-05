package com.lightmeter.rawmeter

import android.content.Context
import android.os.Build

data class CameraCalibrationRecord(
    val correctionEv: Double,
    val referenceEv100: Double?,
    val measuredEv100: Double?,
    val updatedAtEpochMs: Long,
    val calibrationCount: Int,
)

/** Keeps baseline and user corrections isolated for every device and camera id. */
class CameraCalibrationStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("raw_meter_calibration", Context.MODE_PRIVATE)

    @Synchronized
    fun userCorrection(cameraId: String): Double {
        val key = userKey(cameraId)
        return preferences.getFloat(key, 0f).toDouble()
    }

    @Synchronized
    fun record(cameraId: String): CameraCalibrationRecord? {
        val userKey = userKey(cameraId)
        if (!preferences.contains(userKey)) return null
        val reference = preferences.optionalFloat(referenceKey(cameraId))
        val measured = preferences.optionalFloat(measuredKey(cameraId))
        return CameraCalibrationRecord(
            correctionEv = preferences.getFloat(userKey, 0f).toDouble(),
            referenceEv100 = reference,
            measuredEv100 = measured,
            updatedAtEpochMs = preferences.getLong(updatedAtKey(cameraId), 0L),
            calibrationCount = preferences.getInt(countKey(cameraId), 1).coerceAtLeast(1),
        )
    }

    @Synchronized
    fun updateUserCorrection(
        cameraId: String,
        referenceEv100: Double,
        measuredEv100: Double,
    ): Double {
        val key = userKey(cameraId)
        val updated = CalibrationMath.updatedUserCorrection(
            currentCorrectionEv = userCorrection(cameraId),
            referenceEv100 = referenceEv100,
            measuredEv100 = measuredEv100,
        )
        val count = preferences.getInt(countKey(cameraId), 0) + 1
        preferences.edit()
            .putFloat(key, updated.toFloat())
            .putFloat(referenceKey(cameraId), referenceEv100.toFloat())
            .putFloat(measuredKey(cameraId), measuredEv100.toFloat())
            .putLong(updatedAtKey(cameraId), System.currentTimeMillis())
            .putInt(countKey(cameraId), count)
            .apply()
        return updated
    }

    @Synchronized
    fun resetUserCorrection(cameraId: String) {
        val key = userKey(cameraId)
        preferences.edit()
            .remove(key)
            .remove(referenceKey(cameraId))
            .remove(measuredKey(cameraId))
            .remove(updatedAtKey(cameraId))
            .remove(countKey(cameraId))
            .apply()
    }

    @Synchronized
    fun totalCorrection(cameraId: String): Double =
        baselineCorrection(cameraId) + userCorrection(cameraId)

    private fun baselineCorrection(cameraId: String): Double {
        val key = deviceKey(cameraId)
        if (preferences.contains(key)) {
            return preferences.getFloat(key, 0f).toDouble()
        }
        val default = knownBaseline(cameraId)
        preferences.edit().putFloat(key, default.toFloat()).apply()
        return default
    }

    private fun userKey(cameraId: String): String = "user_${deviceKey(cameraId)}"
    private fun referenceKey(cameraId: String): String = "reference_${deviceKey(cameraId)}"
    private fun measuredKey(cameraId: String): String = "measured_${deviceKey(cameraId)}"
    private fun updatedAtKey(cameraId: String): String = "updated_${deviceKey(cameraId)}"
    private fun countKey(cameraId: String): String = "count_${deviceKey(cameraId)}"

    private fun deviceKey(cameraId: String): String = buildString {
        append(Build.MANUFACTURER.lowercase())
        append('_')
        append(Build.MODEL.lowercase())
        append('_')
        append(cameraId)
    }.replace(Regex("[^a-z0-9_.-]"), "_")

    private fun knownBaseline(cameraId: String): Double =
        when {
            Build.MANUFACTURER.equals("vivo", ignoreCase = true) &&
                Build.MODEL.equals("V2405A", ignoreCase = true) &&
                cameraId == "0" -> 1.074
            else -> 0.0
        }

    private fun android.content.SharedPreferences.optionalFloat(key: String): Double? =
        if (contains(key)) getFloat(key, 0f).toDouble() else null
}
