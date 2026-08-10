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
        return readActiveRecord(cameraId)
    }

    @Synchronized
    fun history(cameraId: String): List<CameraCalibrationRecord> {
        val records = (0 until HISTORY_LIMIT).mapNotNull { index ->
            readHistoryRecord(cameraId, index)
        }
        if (records.isNotEmpty()) return records.sortedByDescending { it.updatedAtEpochMs }
        return listOfNotNull(readActiveRecord(cameraId))
    }

    private fun readActiveRecord(cameraId: String): CameraCalibrationRecord? {
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
        val updated = CalibrationMath.updatedUserCorrection(
            currentCorrectionEv = userCorrection(cameraId),
            referenceEv100 = referenceEv100,
            measuredEv100 = measuredEv100,
        )
        val previous = history(cameraId)
        val count = maxOf(
            preferences.getInt(countKey(cameraId), 0),
            previous.maxOfOrNull { it.calibrationCount } ?: 0,
        ) + 1
        val record = CameraCalibrationRecord(
            correctionEv = updated,
            referenceEv100 = referenceEv100,
            measuredEv100 = measuredEv100,
            updatedAtEpochMs = System.currentTimeMillis(),
            calibrationCount = count,
        )
        val updatedHistory = (listOf(record) + previous)
            .distinctBy { it.updatedAtEpochMs }
            .take(HISTORY_LIMIT)
        preferences.edit().apply {
            writeActiveRecord(cameraId, record)
            writeHistory(cameraId, updatedHistory)
        }.apply()
        return updated
    }

    @Synchronized
    fun restore(cameraId: String, updatedAtEpochMs: Long): CameraCalibrationRecord? {
        val selected = history(cameraId).firstOrNull {
            it.updatedAtEpochMs == updatedAtEpochMs
        } ?: return null
        preferences.edit().apply { writeActiveRecord(cameraId, selected) }.apply()
        return selected
    }

    @Synchronized
    fun resetUserCorrection(cameraId: String) {
        val retainedHistory = history(cameraId).take(HISTORY_LIMIT)
        preferences.edit().apply {
            writeHistory(cameraId, retainedHistory)
            remove(userKey(cameraId))
            remove(referenceKey(cameraId))
            remove(measuredKey(cameraId))
            remove(updatedAtKey(cameraId))
            remove(countKey(cameraId))
        }.apply()
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

    private fun historyKey(cameraId: String, index: Int, field: String): String =
        "history_${deviceKey(cameraId)}_${index}_$field"

    private fun readHistoryRecord(cameraId: String, index: Int): CameraCalibrationRecord? {
        val correctionKey = historyKey(cameraId, index, "correction")
        if (!preferences.contains(correctionKey)) return null
        return CameraCalibrationRecord(
            correctionEv = preferences.getFloat(correctionKey, 0f).toDouble(),
            referenceEv100 = preferences.optionalFloat(historyKey(cameraId, index, "reference")),
            measuredEv100 = preferences.optionalFloat(historyKey(cameraId, index, "measured")),
            updatedAtEpochMs = preferences.getLong(historyKey(cameraId, index, "updated"), 0L),
            calibrationCount = preferences.getInt(historyKey(cameraId, index, "count"), index + 1)
                .coerceAtLeast(1),
        )
    }

    private fun android.content.SharedPreferences.Editor.writeActiveRecord(
        cameraId: String,
        record: CameraCalibrationRecord,
    ) {
        putFloat(userKey(cameraId), record.correctionEv.toFloat())
        record.referenceEv100?.let { putFloat(referenceKey(cameraId), it.toFloat()) }
            ?: remove(referenceKey(cameraId))
        record.measuredEv100?.let { putFloat(measuredKey(cameraId), it.toFloat()) }
            ?: remove(measuredKey(cameraId))
        putLong(updatedAtKey(cameraId), record.updatedAtEpochMs)
        putInt(countKey(cameraId), record.calibrationCount)
    }

    private fun android.content.SharedPreferences.Editor.writeHistory(
        cameraId: String,
        records: List<CameraCalibrationRecord>,
    ) {
        repeat(HISTORY_LIMIT) { index ->
            val record = records.getOrNull(index)
            if (record == null) {
                clearHistoryRecord(cameraId, index)
            } else {
                putFloat(historyKey(cameraId, index, "correction"), record.correctionEv.toFloat())
                record.referenceEv100?.let {
                    putFloat(historyKey(cameraId, index, "reference"), it.toFloat())
                } ?: remove(historyKey(cameraId, index, "reference"))
                record.measuredEv100?.let {
                    putFloat(historyKey(cameraId, index, "measured"), it.toFloat())
                } ?: remove(historyKey(cameraId, index, "measured"))
                putLong(historyKey(cameraId, index, "updated"), record.updatedAtEpochMs)
                putInt(historyKey(cameraId, index, "count"), record.calibrationCount)
            }
        }
    }

    private fun android.content.SharedPreferences.Editor.clearHistoryRecord(
        cameraId: String,
        index: Int,
    ) {
        listOf("correction", "reference", "measured", "updated", "count").forEach { field ->
            remove(historyKey(cameraId, index, field))
        }
    }

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

    private companion object {
        const val HISTORY_LIMIT = 3
    }
}
