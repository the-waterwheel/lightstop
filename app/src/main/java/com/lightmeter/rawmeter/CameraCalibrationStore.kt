package com.lightmeter.rawmeter

import android.content.Context
import android.os.Build

data class CameraCalibrationRecord(
    val rawCorrectionEv: Double?,
    val compatibleCorrectionEv: Double?,
    val referenceEv100: Double?,
    val rawMeasuredEv100: Double?,
    val compatibleMeasuredEv100: Double?,
    val updatedAtEpochMs: Long,
    val calibrationCount: Int,
) {
    /** Legacy aliases retained for older UI call sites and saved records. */
    val correctionEv: Double
        get() = rawCorrectionEv ?: compatibleCorrectionEv ?: 0.0
    val measuredEv100: Double?
        get() = rawMeasuredEv100 ?: compatibleMeasuredEv100
}

/**
 * Keeps RAW and preview-stream corrections isolated for every device and camera id.
 *
 * The persisted `compatible_*` keys are intentionally retained so existing installations keep
 * their calibration after the user-facing name changed to "Preview stream".
 */
class CameraCalibrationStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences("raw_meter_calibration", Context.MODE_PRIVATE)

    @Synchronized
    fun userCorrection(cameraId: String, source: MeteringSource = MeteringSource.RAW): Double {
        if (!CalibrationEnvironmentStore.isCalibrationTimestampValid(
                appContext,
                preferences.getLong(updatedAtKey(cameraId), 0L),
            )
        ) return 0.0
        val key = userKey(cameraId, source)
        return preferences.getFloat(key, 0f).toDouble()
    }

    fun hasCalibrationArtifacts(): Boolean = preferences.all.keys.any { key ->
        key.startsWith("user_") || key.startsWith("compatible_user_") ||
            key.startsWith("history_")
    }

    @Synchronized
    fun record(cameraId: String): CameraCalibrationRecord? = readActiveRecord(cameraId)

    @Synchronized
    fun history(cameraId: String): List<CameraCalibrationRecord> {
        val records = (0 until HISTORY_LIMIT).mapNotNull { index ->
            readHistoryRecord(cameraId, index)
        }
        if (records.isNotEmpty()) return records.sortedByDescending { it.updatedAtEpochMs }
        return listOfNotNull(readActiveRecord(cameraId))
    }

    private fun readActiveRecord(cameraId: String): CameraCalibrationRecord? {
        val rawKey = userKey(cameraId, MeteringSource.RAW)
        val compatibleKey = userKey(cameraId, MeteringSource.ISP_PREVIEW)
        if (!preferences.contains(rawKey) && !preferences.contains(compatibleKey)) return null
        val updatedAt = preferences.getLong(updatedAtKey(cameraId), 0L)
        if (!CalibrationEnvironmentStore.isCalibrationTimestampValid(appContext, updatedAt)) {
            return null
        }
        return CameraCalibrationRecord(
            rawCorrectionEv = preferences.optionalFloat(rawKey),
            compatibleCorrectionEv = preferences.optionalFloat(compatibleKey),
            referenceEv100 = preferences.optionalFloat(referenceKey(cameraId)),
            rawMeasuredEv100 = preferences.optionalFloat(rawMeasuredKey(cameraId)),
            compatibleMeasuredEv100 = preferences.optionalFloat(compatibleMeasuredKey(cameraId)),
            updatedAtEpochMs = updatedAt,
            calibrationCount = preferences.getInt(countKey(cameraId), 1).coerceAtLeast(1),
        )
    }

    @Synchronized
    fun updateUserCorrections(
        cameraId: String,
        referenceEv100: Double,
        rawMeasuredEv100: Double?,
        compatibleMeasuredEv100: Double?,
    ): CameraCalibrationRecord {
        require(rawMeasuredEv100 != null || compatibleMeasuredEv100 != null) {
            "At least one calibration measurement is required"
        }
        val current = readActiveRecord(cameraId)
        val updatedRaw = rawMeasuredEv100?.let { measured ->
            CalibrationMath.updatedUserCorrection(
                currentCorrectionEv = userCorrection(cameraId, MeteringSource.RAW),
                referenceEv100 = referenceEv100,
                measuredEv100 = measured,
            )
        } ?: current?.rawCorrectionEv
        val updatedCompatible = compatibleMeasuredEv100?.let { measured ->
            CalibrationMath.updatedUserCorrection(
                currentCorrectionEv = userCorrection(cameraId, MeteringSource.ISP_PREVIEW),
                referenceEv100 = referenceEv100,
                measuredEv100 = measured,
            )
        } ?: current?.compatibleCorrectionEv
        val previous = history(cameraId)
        val count = maxOf(
            preferences.getInt(countKey(cameraId), 0),
            previous.maxOfOrNull { it.calibrationCount } ?: 0,
        ) + 1
        val record = CameraCalibrationRecord(
            rawCorrectionEv = updatedRaw,
            compatibleCorrectionEv = updatedCompatible,
            referenceEv100 = referenceEv100,
            rawMeasuredEv100 = rawMeasuredEv100,
            compatibleMeasuredEv100 = compatibleMeasuredEv100,
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
        return record
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
            remove(userKey(cameraId, MeteringSource.RAW))
            remove(userKey(cameraId, MeteringSource.ISP_PREVIEW))
            remove(referenceKey(cameraId))
            remove(rawMeasuredKey(cameraId))
            remove(compatibleMeasuredKey(cameraId))
            remove(updatedAtKey(cameraId))
            remove(countKey(cameraId))
        }.apply()
    }

    @Synchronized
    fun totalCorrection(cameraId: String, source: MeteringSource = MeteringSource.RAW): Double =
        if (source == MeteringSource.RAW) {
            baselineCorrection(cameraId) + userCorrection(cameraId, source)
        } else {
            userCorrection(cameraId, source)
        }

    private fun baselineCorrection(cameraId: String): Double {
        val key = deviceKey(cameraId)
        if (preferences.contains(key)) {
            return preferences.getFloat(key, 0f).toDouble()
        }
        val default = knownBaseline(cameraId)
        preferences.edit().putFloat(key, default.toFloat()).apply()
        return default
    }

    private fun userKey(cameraId: String, source: MeteringSource): String =
        if (source == MeteringSource.RAW) {
            // Keep the original key for seamless migration of existing RAW calibration.
            "user_${deviceKey(cameraId)}"
        } else {
            "compatible_user_${deviceKey(cameraId)}"
        }

    private fun referenceKey(cameraId: String): String = "reference_${deviceKey(cameraId)}"
    private fun rawMeasuredKey(cameraId: String): String = "measured_${deviceKey(cameraId)}"
    private fun compatibleMeasuredKey(cameraId: String): String =
        "compatible_measured_${deviceKey(cameraId)}"
    private fun updatedAtKey(cameraId: String): String = "updated_${deviceKey(cameraId)}"
    private fun countKey(cameraId: String): String = "count_${deviceKey(cameraId)}"

    private fun historyKey(cameraId: String, index: Int, field: String): String =
        "history_${deviceKey(cameraId)}_${index}_$field"

    private fun readHistoryRecord(cameraId: String, index: Int): CameraCalibrationRecord? {
        val rawCorrection = historyKey(cameraId, index, "correction")
        val compatibleCorrection = historyKey(cameraId, index, "compatible_correction")
        if (!preferences.contains(rawCorrection) && !preferences.contains(compatibleCorrection)) {
            return null
        }
        val updatedAt = preferences.getLong(historyKey(cameraId, index, "updated"), 0L)
        if (!CalibrationEnvironmentStore.isCalibrationTimestampValid(appContext, updatedAt)) {
            return null
        }
        return CameraCalibrationRecord(
            rawCorrectionEv = preferences.optionalFloat(rawCorrection),
            compatibleCorrectionEv = preferences.optionalFloat(compatibleCorrection),
            referenceEv100 = preferences.optionalFloat(historyKey(cameraId, index, "reference")),
            rawMeasuredEv100 = preferences.optionalFloat(historyKey(cameraId, index, "measured")),
            compatibleMeasuredEv100 = preferences.optionalFloat(
                historyKey(cameraId, index, "compatible_measured"),
            ),
            updatedAtEpochMs = updatedAt,
            calibrationCount = preferences.getInt(historyKey(cameraId, index, "count"), index + 1)
                .coerceAtLeast(1),
        )
    }

    private fun android.content.SharedPreferences.Editor.writeActiveRecord(
        cameraId: String,
        record: CameraCalibrationRecord,
    ) {
        putOptionalFloat(userKey(cameraId, MeteringSource.RAW), record.rawCorrectionEv)
        putOptionalFloat(
            userKey(cameraId, MeteringSource.ISP_PREVIEW),
            record.compatibleCorrectionEv,
        )
        putOptionalFloat(referenceKey(cameraId), record.referenceEv100)
        putOptionalFloat(rawMeasuredKey(cameraId), record.rawMeasuredEv100)
        putOptionalFloat(compatibleMeasuredKey(cameraId), record.compatibleMeasuredEv100)
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
                putOptionalFloat(
                    historyKey(cameraId, index, "correction"),
                    record.rawCorrectionEv,
                )
                putOptionalFloat(
                    historyKey(cameraId, index, "compatible_correction"),
                    record.compatibleCorrectionEv,
                )
                putOptionalFloat(
                    historyKey(cameraId, index, "reference"),
                    record.referenceEv100,
                )
                putOptionalFloat(
                    historyKey(cameraId, index, "measured"),
                    record.rawMeasuredEv100,
                )
                putOptionalFloat(
                    historyKey(cameraId, index, "compatible_measured"),
                    record.compatibleMeasuredEv100,
                )
                putLong(historyKey(cameraId, index, "updated"), record.updatedAtEpochMs)
                putInt(historyKey(cameraId, index, "count"), record.calibrationCount)
            }
        }
    }

    private fun android.content.SharedPreferences.Editor.clearHistoryRecord(
        cameraId: String,
        index: Int,
    ) {
        listOf(
            "correction",
            "compatible_correction",
            "reference",
            "measured",
            "compatible_measured",
            "updated",
            "count",
        ).forEach { field -> remove(historyKey(cameraId, index, field)) }
    }

    private fun android.content.SharedPreferences.Editor.putOptionalFloat(
        key: String,
        value: Double?,
    ) {
        if (value == null) remove(key) else putFloat(key, value.toFloat())
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
