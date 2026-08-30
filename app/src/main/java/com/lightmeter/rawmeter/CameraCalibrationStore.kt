package com.lightmeter.rawmeter

import android.content.Context
import android.os.Build

/**
 * Keeps RAW, YUV, and displayed ISP-preview corrections isolated for every device and camera id.
 *
 * The old shared processed-stream key remains readable as a clearly labelled migration fallback.
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
        val correction = preferences.optionalFloat(userKey(cameraId, source))
        return correction ?: if (source == MeteringSource.RAW) 0.0 else {
            preferences.optionalFloat(legacyCompatibleKey(cameraId)) ?: 0.0
        }
    }

    fun hasCalibrationArtifacts(): Boolean = preferences.all.keys.any { key ->
        key.startsWith("user_") || key.startsWith("yuv_user_") ||
            key.startsWith("isp_user_") || key.startsWith("compatible_user_") ||
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
        val yuvKey = userKey(cameraId, MeteringSource.YUV_PREVIEW)
        val ispKey = userKey(cameraId, MeteringSource.ISP_PREVIEW)
        val legacyKey = legacyCompatibleKey(cameraId)
        if (!preferences.contains(rawKey) && !preferences.contains(yuvKey) &&
            !preferences.contains(ispKey) && !preferences.contains(legacyKey)
        ) return null
        val updatedAt = preferences.getLong(updatedAtKey(cameraId), 0L)
        if (!CalibrationEnvironmentStore.isCalibrationTimestampValid(appContext, updatedAt)) {
            return null
        }
        return CameraCalibrationRecord(
            raw = StreamCalibration(
                correctionEv = preferences.optionalFloat(rawKey),
                measuredEv100 = preferences.optionalFloat(rawMeasuredKey(cameraId)),
            ),
            yuv = StreamCalibration(
                correctionEv = preferences.optionalFloat(yuvKey),
                measuredEv100 = preferences.optionalFloat(yuvMeasuredKey(cameraId)),
            ),
            ispPreview = StreamCalibration(
                correctionEv = preferences.optionalFloat(ispKey),
                measuredEv100 = preferences.optionalFloat(ispMeasuredKey(cameraId)),
            ),
            legacyCompatibleCorrectionEv = preferences.optionalFloat(legacyKey),
            legacyCompatibleMeasuredEv100 = preferences.optionalFloat(legacyCompatibleMeasuredKey(cameraId)),
            referenceEv100 = preferences.optionalFloat(referenceKey(cameraId)),
            updatedAtEpochMs = updatedAt,
            calibrationCount = preferences.getInt(countKey(cameraId), 1).coerceAtLeast(1),
            schemaVersion = preferences.getInt(schemaVersionKey(cameraId), 1),
        )
    }

    @Synchronized
    fun updateUserCorrections(
        cameraId: String,
        referenceEv100: Double,
        measurements: Map<MeteringSource, Double>,
    ): CameraCalibrationRecord {
        require(measurements.isNotEmpty()) {
            "At least one calibration measurement is required"
        }
        val current = readActiveRecord(cameraId)
        val updatedRaw = updatedStream(
            cameraId = cameraId,
            source = MeteringSource.RAW,
            previous = current?.raw,
            measurement = measurements[MeteringSource.RAW],
            referenceEv100 = referenceEv100,
        )
        val updatedYuv = updatedStream(
            cameraId = cameraId,
            source = MeteringSource.YUV_PREVIEW,
            previous = current?.yuv,
            measurement = measurements[MeteringSource.YUV_PREVIEW],
            referenceEv100 = referenceEv100,
        )
        val updatedIsp = updatedStream(
            cameraId = cameraId,
            source = MeteringSource.ISP_PREVIEW,
            previous = current?.ispPreview,
            measurement = measurements[MeteringSource.ISP_PREVIEW],
            referenceEv100 = referenceEv100,
        )
        val previous = history(cameraId)
        val count = maxOf(
            preferences.getInt(countKey(cameraId), 0),
            previous.maxOfOrNull { it.calibrationCount } ?: 0,
        ) + 1
        val record = CameraCalibrationRecord(
            raw = updatedRaw,
            yuv = updatedYuv,
            ispPreview = updatedIsp,
            legacyCompatibleCorrectionEv = current?.legacyCompatibleCorrectionEv,
            legacyCompatibleMeasuredEv100 = current?.legacyCompatibleMeasuredEv100,
            referenceEv100 = referenceEv100,
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

    private fun updatedStream(
        cameraId: String,
        source: MeteringSource,
        previous: StreamCalibration?,
        measurement: Double?,
        referenceEv100: Double,
    ): StreamCalibration = if (measurement == null) {
        previous ?: StreamCalibration(correctionEv = null, measuredEv100 = null)
    } else {
        StreamCalibration(
            correctionEv = CalibrationMath.updatedUserCorrection(
                currentCorrectionEv = userCorrection(cameraId, source),
                referenceEv100 = referenceEv100,
                measuredEv100 = measurement,
            ),
            measuredEv100 = measurement,
        )
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
            remove(userKey(cameraId, MeteringSource.YUV_PREVIEW))
            remove(userKey(cameraId, MeteringSource.ISP_PREVIEW))
            remove(legacyCompatibleKey(cameraId))
            remove(referenceKey(cameraId))
            remove(rawMeasuredKey(cameraId))
            remove(yuvMeasuredKey(cameraId))
            remove(ispMeasuredKey(cameraId))
            remove(legacyCompatibleMeasuredKey(cameraId))
            remove(updatedAtKey(cameraId))
            remove(countKey(cameraId))
            remove(schemaVersionKey(cameraId))
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

    private fun userKey(cameraId: String, source: MeteringSource): String = when (source) {
        MeteringSource.RAW -> "user_${deviceKey(cameraId)}"
        MeteringSource.YUV_PREVIEW -> "yuv_user_${deviceKey(cameraId)}"
        MeteringSource.ISP_PREVIEW -> "isp_user_${deviceKey(cameraId)}"
    }

    private fun legacyCompatibleKey(cameraId: String): String = "compatible_user_${deviceKey(cameraId)}"
    private fun referenceKey(cameraId: String): String = "reference_${deviceKey(cameraId)}"
    private fun rawMeasuredKey(cameraId: String): String = "measured_${deviceKey(cameraId)}"
    private fun yuvMeasuredKey(cameraId: String): String = "yuv_measured_${deviceKey(cameraId)}"
    private fun ispMeasuredKey(cameraId: String): String = "isp_measured_${deviceKey(cameraId)}"
    private fun legacyCompatibleMeasuredKey(cameraId: String): String =
        "compatible_measured_${deviceKey(cameraId)}"
    private fun updatedAtKey(cameraId: String): String = "updated_${deviceKey(cameraId)}"
    private fun countKey(cameraId: String): String = "count_${deviceKey(cameraId)}"
    private fun schemaVersionKey(cameraId: String): String = "schema_${deviceKey(cameraId)}"

    private fun historyKey(cameraId: String, index: Int, field: String): String =
        "history_${deviceKey(cameraId)}_${index}_$field"

    private fun readHistoryRecord(cameraId: String, index: Int): CameraCalibrationRecord? {
        val rawCorrection = historyKey(cameraId, index, "correction")
        val yuvCorrection = historyKey(cameraId, index, "yuv_correction")
        val ispCorrection = historyKey(cameraId, index, "isp_correction")
        val legacyCorrection = historyKey(cameraId, index, "compatible_correction")
        if (!preferences.contains(rawCorrection) && !preferences.contains(yuvCorrection) &&
            !preferences.contains(ispCorrection) && !preferences.contains(legacyCorrection)
        ) {
            return null
        }
        val updatedAt = preferences.getLong(historyKey(cameraId, index, "updated"), 0L)
        if (!CalibrationEnvironmentStore.isCalibrationTimestampValid(appContext, updatedAt)) {
            return null
        }
        return CameraCalibrationRecord(
            raw = StreamCalibration(
                correctionEv = preferences.optionalFloat(rawCorrection),
                measuredEv100 = preferences.optionalFloat(historyKey(cameraId, index, "measured")),
            ),
            yuv = StreamCalibration(
                correctionEv = preferences.optionalFloat(yuvCorrection),
                measuredEv100 = preferences.optionalFloat(historyKey(cameraId, index, "yuv_measured")),
            ),
            ispPreview = StreamCalibration(
                correctionEv = preferences.optionalFloat(ispCorrection),
                measuredEv100 = preferences.optionalFloat(historyKey(cameraId, index, "isp_measured")),
            ),
            legacyCompatibleCorrectionEv = preferences.optionalFloat(legacyCorrection),
            legacyCompatibleMeasuredEv100 = preferences.optionalFloat(
                historyKey(cameraId, index, "compatible_measured"),
            ),
            referenceEv100 = preferences.optionalFloat(historyKey(cameraId, index, "reference")),
            updatedAtEpochMs = updatedAt,
            calibrationCount = preferences.getInt(historyKey(cameraId, index, "count"), index + 1)
                .coerceAtLeast(1),
            schemaVersion = preferences.getInt(historyKey(cameraId, index, "schema"), 1),
        )
    }

    private fun android.content.SharedPreferences.Editor.writeActiveRecord(
        cameraId: String,
        record: CameraCalibrationRecord,
    ) {
        putOptionalFloat(userKey(cameraId, MeteringSource.RAW), record.rawCorrectionEv)
        putOptionalFloat(
            userKey(cameraId, MeteringSource.YUV_PREVIEW),
            record.yuvCorrectionEv,
        )
        putOptionalFloat(userKey(cameraId, MeteringSource.ISP_PREVIEW), record.ispPreviewCorrectionEv)
        putOptionalFloat(legacyCompatibleKey(cameraId), record.legacyCompatibleCorrectionEv)
        putOptionalFloat(referenceKey(cameraId), record.referenceEv100)
        putOptionalFloat(rawMeasuredKey(cameraId), record.rawMeasuredEv100)
        putOptionalFloat(yuvMeasuredKey(cameraId), record.yuvMeasuredEv100)
        putOptionalFloat(ispMeasuredKey(cameraId), record.ispPreviewMeasuredEv100)
        putOptionalFloat(legacyCompatibleMeasuredKey(cameraId), record.legacyCompatibleMeasuredEv100)
        putLong(updatedAtKey(cameraId), record.updatedAtEpochMs)
        putInt(countKey(cameraId), record.calibrationCount)
        putInt(schemaVersionKey(cameraId), record.schemaVersion)
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
                    historyKey(cameraId, index, "yuv_correction"),
                    record.yuvCorrectionEv,
                )
                putOptionalFloat(
                    historyKey(cameraId, index, "isp_correction"),
                    record.ispPreviewCorrectionEv,
                )
                putOptionalFloat(
                    historyKey(cameraId, index, "compatible_correction"),
                    record.legacyCompatibleCorrectionEv,
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
                    historyKey(cameraId, index, "yuv_measured"),
                    record.yuvMeasuredEv100,
                )
                putOptionalFloat(
                    historyKey(cameraId, index, "isp_measured"),
                    record.ispPreviewMeasuredEv100,
                )
                putOptionalFloat(
                    historyKey(cameraId, index, "compatible_measured"),
                    record.legacyCompatibleMeasuredEv100,
                )
                putLong(historyKey(cameraId, index, "updated"), record.updatedAtEpochMs)
                putInt(historyKey(cameraId, index, "count"), record.calibrationCount)
                putInt(historyKey(cameraId, index, "schema"), record.schemaVersion)
            }
        }
    }

    private fun android.content.SharedPreferences.Editor.clearHistoryRecord(
        cameraId: String,
        index: Int,
    ) {
        listOf(
            "correction",
            "yuv_correction",
            "isp_correction",
            "compatible_correction",
            "reference",
            "measured",
            "yuv_measured",
            "isp_measured",
            "compatible_measured",
            "updated",
            "count",
            "schema",
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
