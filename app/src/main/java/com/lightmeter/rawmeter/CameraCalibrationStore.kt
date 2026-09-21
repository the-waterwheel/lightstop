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

    /**
     * Capture context of the currently configured route/output, set by CameraController before any
     * measurement. While it is null (for example a settings screen before the camera opens) stored
     * corrections remain visible for display, but a measurement never runs without a context.
     */
    @Volatile
    private var activeContext: CalibrationCaptureContext? = null

    fun setActiveContext(context: CalibrationCaptureContext?) {
        activeContext = context
    }

    /** Whether the stored value for this camera/source may be applied to the active context. */
    @Synchronized
    fun signatureState(
        cameraId: String,
        source: MeteringSource = MeteringSource.RAW,
    ): CalibrationSignatureState {
        val storedRaw = preferences.getString(signatureKey(cameraId, source), null)
        val storedPresent = storedRaw != null
        val stored = CalibrationSignature.parse(storedRaw)
        val current = currentSignature(source) ?: stored?.copy(
            buildFingerprintHash = CalibrationEnvironmentStore.buildFingerprintHash(),
        ) ?: return if (storedPresent) {
            CalibrationSignatureState.LEGACY_UNVERIFIED
        } else {
            CalibrationSignatureState.NONE
        }
        return CalibrationSignaturePolicy.classify(stored, storedPresent, current)
    }

    private fun currentSignature(source: MeteringSource): CalibrationSignature? =
        activeContext?.signature(source)

    @Synchronized
    fun userCorrection(cameraId: String, source: MeteringSource = MeteringSource.RAW): Double {
        if (!CalibrationEnvironmentStore.isCalibrationTimestampValid(
                appContext,
                preferences.getLong(updatedAtKey(cameraId), 0L),
            )
        ) return 0.0
        val schemaVersion = preferences.getInt(schemaVersionKey(cameraId), 1)
        // Version-2 processed corrections were measured from Y-only averages / fixed sRGB output.
        // Applying them after RGB reconstruction and tonemap inversion would double-correct an
        // unknown, scene-dependent error. RAW corrections remain valid across this migration.
        if (source != MeteringSource.RAW &&
            schemaVersion < CameraCalibrationRecord.CURRENT_SCHEMA_VERSION
        ) return 0.0
        val stored = preferences.optionalFloat(userKey(cameraId, source)) ?: return 0.0
        // With no active capture context there is nothing to validate against; the value is
        // display-only and every measurement path sets a context first.
        if (activeContext == null) return stored
        return if (signatureState(cameraId, source).isApplicable) stored else 0.0
    }

    fun hasMeteringCalibrationArtifacts(): Boolean = preferences.all.keys.any { key ->
        key.startsWith("user_") || key.startsWith("yuv_user_") ||
            key.startsWith("isp_user_") || key.startsWith("compatible_user_") ||
            key.startsWith("history_")
    }

    fun hasCalibrationArtifacts(): Boolean = hasMeteringCalibrationArtifacts() ||
        preferences.all.keys.any { key -> key.startsWith("preview_execution_user_") }

    /** Auxiliary preview-execution calibration; independent from RAW/YUV/ISP metering records. */
    @Synchronized
    fun exposurePreviewCorrection(cameraId: String): Double {
        val updatedAt = preferences.getLong(exposurePreviewUpdatedKey(cameraId), 0L)
        if (!CalibrationEnvironmentStore.isCalibrationTimestampValid(appContext, updatedAt)) {
            return 0.0
        }
        // Independent domain: the preview-execution correction is invalidated by an OS build
        // change, but never shares the RAW/YUV/ISP signatures or corrections.
        val storedBuild = preferences.getString(exposurePreviewBuildKey(cameraId), null)
        if (storedBuild != null &&
            storedBuild != CalibrationEnvironmentStore.buildFingerprintHash()
        ) {
            return 0.0
        }
        return (preferences.optionalFloat(exposurePreviewKey(cameraId)) ?: 0.0)
            .coerceIn(
                ExposurePreviewCalibrationMath.MIN_CORRECTION_EV,
                ExposurePreviewCalibrationMath.MAX_CORRECTION_EV,
            )
    }

    @Synchronized
    fun saveExposurePreviewCorrection(cameraId: String, correctionEv: Double): Double {
        require(correctionEv.isFinite()) { "Exposure preview correction must be finite" }
        val saved = ExposurePreviewCalibrationMath.clampAndSnapCorrection(correctionEv)
        preferences.edit()
            .putFloat(exposurePreviewKey(cameraId), saved.toFloat())
            .putLong(exposurePreviewUpdatedKey(cameraId), System.currentTimeMillis())
            .putString(
                exposurePreviewBuildKey(cameraId),
                CalibrationEnvironmentStore.buildFingerprintHash(),
            )
            .apply()
        return saved
    }

    @Synchronized
    fun resetExposurePreviewCorrection(cameraId: String) {
        preferences.edit()
            .remove(exposurePreviewKey(cameraId))
            .remove(exposurePreviewUpdatedKey(cameraId))
            .remove(exposurePreviewBuildKey(cameraId))
            .apply()
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
        val schemaVersion = preferences.getInt(schemaVersionKey(cameraId), 1)
        val processedCalibrationIsCurrent =
            schemaVersion >= CameraCalibrationRecord.CURRENT_SCHEMA_VERSION
        val record = CameraCalibrationRecord(
            raw = StreamCalibration(
                correctionEv = preferences.optionalFloat(rawKey),
                measuredEv100 = preferences.optionalFloat(rawMeasuredKey(cameraId)),
                signature = CalibrationSignature.parse(
                    preferences.getString(signatureKey(cameraId, MeteringSource.RAW), null),
                ),
            ),
            yuv = StreamCalibration(
                correctionEv = preferences.optionalFloat(yuvKey).takeIf {
                    processedCalibrationIsCurrent
                },
                measuredEv100 = preferences.optionalFloat(yuvMeasuredKey(cameraId)).takeIf {
                    processedCalibrationIsCurrent
                },
                signature = CalibrationSignature.parse(
                    preferences.getString(signatureKey(cameraId, MeteringSource.YUV_PREVIEW), null),
                ),
            ),
            ispPreview = StreamCalibration(
                correctionEv = preferences.optionalFloat(ispKey).takeIf {
                    processedCalibrationIsCurrent
                },
                measuredEv100 = preferences.optionalFloat(ispMeasuredKey(cameraId)).takeIf {
                    processedCalibrationIsCurrent
                },
                signature = CalibrationSignature.parse(
                    preferences.getString(signatureKey(cameraId, MeteringSource.ISP_PREVIEW), null),
                ),
            ),
            legacyCompatibleCorrectionEv = preferences.optionalFloat(legacyKey).takeIf {
                processedCalibrationIsCurrent
            },
            legacyCompatibleMeasuredEv100 = preferences.optionalFloat(
                legacyCompatibleMeasuredKey(cameraId),
            ).takeIf { processedCalibrationIsCurrent },
            referenceEv100 = preferences.optionalFloat(referenceKey(cameraId)),
            updatedAtEpochMs = updatedAt,
            calibrationCount = preferences.getInt(countKey(cameraId), 1).coerceAtLeast(1),
            schemaVersion = schemaVersion,
        )
        return record.takeIf {
            it.rawCorrectionEv != null || it.yuvCorrectionEv != null ||
                it.ispPreviewCorrectionEv != null || it.legacyCompatibleCorrectionEv != null
        }
    }

    @Synchronized
    fun updateUserCorrections(
        cameraId: String,
        referenceEv100: Double,
        measurements: Map<MeteringSource, Double>,
        signatures: Map<MeteringSource, CalibrationSignature> = emptyMap(),
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
            signature = signatures[MeteringSource.RAW],
        )
        val updatedYuv = updatedStream(
            cameraId = cameraId,
            source = MeteringSource.YUV_PREVIEW,
            previous = current?.yuv,
            measurement = measurements[MeteringSource.YUV_PREVIEW],
            referenceEv100 = referenceEv100,
            signature = signatures[MeteringSource.YUV_PREVIEW],
        )
        val updatedIsp = updatedStream(
            cameraId = cameraId,
            source = MeteringSource.ISP_PREVIEW,
            previous = current?.ispPreview,
            measurement = measurements[MeteringSource.ISP_PREVIEW],
            referenceEv100 = referenceEv100,
            signature = signatures[MeteringSource.ISP_PREVIEW],
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
        signature: CalibrationSignature?,
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
            // Prefer the signature captured at measurement time; the active context is only a
            // fallback for callers that did not supply one.
            signature = signature ?: currentSignature(source),
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
            remove(signatureKey(cameraId, MeteringSource.RAW))
            remove(signatureKey(cameraId, MeteringSource.YUV_PREVIEW))
            remove(signatureKey(cameraId, MeteringSource.ISP_PREVIEW))
        }.apply()
    }

    @Synchronized
    fun totalCorrection(cameraId: String, source: MeteringSource = MeteringSource.RAW): Double =
        userCorrection(cameraId, source)

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
    private fun signatureKey(cameraId: String, source: MeteringSource): String =
        "signature_${deviceKey(cameraId)}_${source.name}"
    private fun exposurePreviewKey(cameraId: String): String =
        "preview_execution_user_${deviceKey(cameraId)}"
    private fun exposurePreviewUpdatedKey(cameraId: String): String =
        "preview_execution_updated_${deviceKey(cameraId)}"
    private fun exposurePreviewBuildKey(cameraId: String): String =
        "preview_execution_build_${deviceKey(cameraId)}"

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
        val schemaVersion = preferences.getInt(historyKey(cameraId, index, "schema"), 1)
        val processedCalibrationIsCurrent =
            schemaVersion >= CameraCalibrationRecord.CURRENT_SCHEMA_VERSION
        val record = CameraCalibrationRecord(
            raw = StreamCalibration(
                correctionEv = preferences.optionalFloat(rawCorrection),
                measuredEv100 = preferences.optionalFloat(historyKey(cameraId, index, "measured")),
                signature = CalibrationSignature.parse(
                    preferences.getString(historyKey(cameraId, index, "signature"), null),
                ),
            ),
            yuv = StreamCalibration(
                correctionEv = preferences.optionalFloat(yuvCorrection).takeIf {
                    processedCalibrationIsCurrent
                },
                measuredEv100 = preferences.optionalFloat(
                    historyKey(cameraId, index, "yuv_measured"),
                ).takeIf { processedCalibrationIsCurrent },
                signature = CalibrationSignature.parse(
                    preferences.getString(historyKey(cameraId, index, "yuv_signature"), null),
                ),
            ),
            ispPreview = StreamCalibration(
                correctionEv = preferences.optionalFloat(ispCorrection).takeIf {
                    processedCalibrationIsCurrent
                },
                measuredEv100 = preferences.optionalFloat(
                    historyKey(cameraId, index, "isp_measured"),
                ).takeIf { processedCalibrationIsCurrent },
                signature = CalibrationSignature.parse(
                    preferences.getString(historyKey(cameraId, index, "isp_signature"), null),
                ),
            ),
            legacyCompatibleCorrectionEv = preferences.optionalFloat(legacyCorrection).takeIf {
                processedCalibrationIsCurrent
            },
            legacyCompatibleMeasuredEv100 = preferences.optionalFloat(
                historyKey(cameraId, index, "compatible_measured"),
            ).takeIf { processedCalibrationIsCurrent },
            referenceEv100 = preferences.optionalFloat(historyKey(cameraId, index, "reference")),
            updatedAtEpochMs = updatedAt,
            calibrationCount = preferences.getInt(historyKey(cameraId, index, "count"), index + 1)
                .coerceAtLeast(1),
            schemaVersion = schemaVersion,
        )
        return record.takeIf {
            it.rawCorrectionEv != null || it.yuvCorrectionEv != null ||
                it.ispPreviewCorrectionEv != null || it.legacyCompatibleCorrectionEv != null
        }
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
        putSignature(cameraId, MeteringSource.RAW, record.raw.signature)
        putSignature(cameraId, MeteringSource.YUV_PREVIEW, record.yuv.signature)
        putSignature(cameraId, MeteringSource.ISP_PREVIEW, record.ispPreview.signature)
        putLong(updatedAtKey(cameraId), record.updatedAtEpochMs)
        putInt(countKey(cameraId), record.calibrationCount)
        putInt(schemaVersionKey(cameraId), record.schemaVersion)
    }

    private fun android.content.SharedPreferences.Editor.putSignature(
        cameraId: String,
        source: MeteringSource,
        signature: CalibrationSignature?,
    ) {
        val key = signatureKey(cameraId, source)
        if (signature == null) remove(key) else putString(key, signature.serialize())
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
                putHistorySignature(cameraId, index, "signature", record.raw.signature)
                putHistorySignature(cameraId, index, "yuv_signature", record.yuv.signature)
                putHistorySignature(cameraId, index, "isp_signature", record.ispPreview.signature)
                putLong(historyKey(cameraId, index, "updated"), record.updatedAtEpochMs)
                putInt(historyKey(cameraId, index, "count"), record.calibrationCount)
                putInt(historyKey(cameraId, index, "schema"), record.schemaVersion)
            }
        }
    }

    private fun android.content.SharedPreferences.Editor.putHistorySignature(
        cameraId: String,
        index: Int,
        field: String,
        signature: CalibrationSignature?,
    ) {
        val key = historyKey(cameraId, index, field)
        if (signature == null) remove(key) else putString(key, signature.serialize())
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
            "signature",
            "yuv_signature",
            "isp_signature",
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

    private fun android.content.SharedPreferences.optionalFloat(key: String): Double? =
        if (contains(key)) getFloat(key, 0f).toDouble() else null

    private companion object {
        const val HISTORY_LIMIT = 3
    }
}
