package com.lightmeter.rawmeter

import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sqrt

enum class ParameterRecordMode { NORMAL, ZONE }

data class RecordedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
)

data class RecordedZonePoint(
    val id: Int,
    val normalizedX: Float,
    val normalizedY: Float,
    val ev100: Double?,
    val source: MeteringSource,
)

enum class RecordedFlashDistanceMode { MANUAL, AUTO }

enum class RecordedFlashAdjustmentStatus { APPLIED, DISTANCE_UNAVAILABLE, FLASH_DOMINATES, INVALID }

/** Frozen at capture start; it is never recomputed from later flash-tool state. */
data class RecordedFlashSnapshot(
    /** Normalized ISO-100 value retained for backwards-compatible record calculations. */
    val guideNumberIso100: Double,
    val configuredIso: Int,
    val powerDenominator: Int,
    val lossStops: Double,
    val distanceMode: RecordedFlashDistanceMode,
    val configuredDistanceMeters: Double?,
    val effectiveDistanceMeters: Double?,
    val effectiveGuideNumber: Double?,
    val compensationStops: Double,
    val adjustmentStatus: RecordedFlashAdjustmentStatus,
    /** Value and reference standard exactly as entered in the flash tool. */
    val configuredGuideNumber: Double = guideNumberIso100,
    val guideNumberReferenceIso: Int = 100,
)

/** Frozen diagnostic state of the active automatic-distance provider at capture start. */
data class RecordedDistanceSnapshot(
    val status: DistanceMeasurementStatus,
    val meters: Double?,
    val lowerMeters: Double?,
    val upperMeters: Double?,
    val confidence: Double?,
    val quality: DistanceQuality?,
    val source: DistanceSource?,
    val isFreshAtCapture: Boolean,
    /** Null because Camera2 sensor timestamps are not comparable with wall-clock capture time. */
    val ageMsAtCapture: Long?,
    val cameraIdentity: String?,
    val targetX: Float?,
    val targetY: Float?,
    val sampleCount: Int?,
)

data class RecordedRawGrid(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    val referenceLuma: Float,
    val screenToSensorRotationDegrees: Int = 0,
    /** Whether the user-facing preview was mirrored when this grid was recorded. */
    val screenToSensorMirrored: Boolean = false,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
) {
    fun relativeEvAt(x: Float, y: Float): Double? {
        if (width <= 0 || height <= 0 || values.size != width * height || referenceLuma <= 0f) return null
        val value = sampledValueAt(x, y) ?: return null
        return ln(value / referenceLuma) / ln(2.0)
    }

    fun rebasedForKnownPoints(baseEv100: Double?, points: List<RecordedZonePoint>): RecordedRawGrid {
        val base = baseEv100 ?: return this
        val impliedLogReferences = points.mapNotNull { point ->
            val ev = point.ev100 ?: return@mapNotNull null
            val value = sampledValueAt(point.normalizedX, point.normalizedY) ?: return@mapNotNull null
            ln(value.toDouble()) - (ev - base) * ln(2.0)
        }
        if (impliedLogReferences.isEmpty()) return this
        val reference = exp(impliedLogReferences.average()).toFloat()
        return if (reference.isFinite() && reference > 0f) copy(referenceLuma = reference) else this
    }

    private fun sampledValueAt(x: Float, y: Float): Float? {
        if (width <= 0 || height <= 0 || values.size != width * height) return null
        val (sensorX, sensorY) = ScreenToSensorCoordinateTransform(
            screenToSensorRotationDegrees,
            screenToSensorMirrored,
        ).map(x, y)
        val mappedX = cropLeft + sensorX * (cropRight - cropLeft)
        val mappedY = cropTop + sensorY * (cropBottom - cropTop)
        val column = (mappedX.coerceIn(0f, 1f) * (width - 1)).toInt()
        val row = (mappedY.coerceIn(0f, 1f) * (height - 1)).toInt()
        val value = values[row * width + column]
        return value.takeIf { it > 0f }
    }
}

data class ParameterMeterSnapshot(
    val mode: ParameterRecordMode,
    val apertureCoordinate: Double,
    val shutterCoordinate: Double,
    val ei: Int,
    val ev100: Double?,
    val zonePoints: List<RecordedZonePoint>,
    val flash: RecordedFlashSnapshot? = null,
    val distance: RecordedDistanceSnapshot? = null,
)

data class ParameterRecordOptions(
    val recordGps: Boolean,
    val recordTime: Boolean,
    val recordRaw: Boolean,
)

data class ParameterCaptureDraft(
    val id: String,
    val snapshot: ParameterMeterSnapshot,
    val previewTempPath: String,
    /** Calibration identity of the active physical camera, when the platform reports one. */
    val cameraId: String? = null,
    val rawTempPath: String? = null,
    val rawGrid: RecordedRawGrid? = null,
    val location: RecordedLocation? = null,
    val capturedAtEpochMs: Long? = null,
    val filmId: String? = null,
    val filmName: String? = null,
    val filmIso: Int? = null,
    val notes: List<String> = emptyList(),
)

data class ParameterRecordEntry(
    val id: String,
    val categoryId: String,
    val capturedAtEpochMs: Long?,
    val previewPath: String,
    val rawPath: String?,
    /** Null only for records created before camera identity was persisted. */
    val cameraId: String? = null,
    val mode: ParameterRecordMode,
    val apertureCoordinate: Double,
    val shutterCoordinate: Double,
    val ei: Int,
    val ev100: Double?,
    val filmId: String?,
    val filmName: String?,
    val filmIso: Int?,
    val notes: List<String>,
    val location: RecordedLocation?,
    val zonePoints: List<RecordedZonePoint>,
    val rawGrid: RecordedRawGrid?,
    val flash: RecordedFlashSnapshot? = null,
    val distance: RecordedDistanceSnapshot? = null,
) {
    fun selectedExposureEv100(): Double =
        apertureCoordinate - shutterCoordinate - ExposureMath.log2(ei / 100.0)

    /** Resolves old Normal RAW records that did not store an independent scene EV. */
    fun rawEv100At(normalizedX: Float, normalizedY: Float): Double? {
        val relative = rawGrid?.relativeEvAt(normalizedX, normalizedY) ?: return null
        return (ev100 ?: selectedExposureEv100()) + relative
    }

    fun resolvedZonePointEv100(point: RecordedZonePoint): Double? =
        point.ev100 ?: rawEv100At(point.normalizedX, point.normalizedY)
}

/** Pure conversion kept beside persistence models so the capture boundary is explicit and testable. */
internal object ParameterRecordCaptureSnapshot {
    fun flash(state: MeterState): RecordedFlashSnapshot? {
        val configuration = state.appliedFlashConfiguration ?: return null
        val adjustment = state.currentFlashAdjustment()
        val effectiveDistance = when {
            adjustment.status != FlashAdjustmentStatus.APPLIED -> null
            configuration.distanceMeters != null -> configuration.distanceMeters
            else -> state.distanceMeasurementState.effectiveMetersForFlash
        }
        return RecordedFlashSnapshot(
            guideNumberIso100 = configuration.guideNumber * sqrt(
                100.0 / configuration.guideNumberReferenceIso.coerceAtLeast(1).toDouble(),
            ),
            configuredIso = configuration.iso,
            powerDenominator = configuration.powerDenominator,
            lossStops = configuration.lossStops,
            distanceMode = if (configuration.isAutoDistance) {
                RecordedFlashDistanceMode.AUTO
            } else {
                RecordedFlashDistanceMode.MANUAL
            },
            configuredDistanceMeters = configuration.distanceMeters,
            effectiveDistanceMeters = effectiveDistance,
            effectiveGuideNumber = adjustment.effectiveGuideNumber,
            compensationStops = adjustment.compensationStops,
            adjustmentStatus = when (adjustment.status) {
                FlashAdjustmentStatus.APPLIED -> RecordedFlashAdjustmentStatus.APPLIED
                FlashAdjustmentStatus.DISTANCE_UNAVAILABLE -> RecordedFlashAdjustmentStatus.DISTANCE_UNAVAILABLE
                FlashAdjustmentStatus.FLASH_DOMINATES -> RecordedFlashAdjustmentStatus.FLASH_DOMINATES
                FlashAdjustmentStatus.INVALID -> RecordedFlashAdjustmentStatus.INVALID
            },
            configuredGuideNumber = configuration.guideNumber,
            guideNumberReferenceIso = configuration.guideNumberReferenceIso,
        )
    }

    fun distance(state: DistanceMeasurementState): RecordedDistanceSnapshot? {
        val estimate = state.estimate
        if (estimate == null && state.status == DistanceMeasurementStatus.IDLE) return null
        return RecordedDistanceSnapshot(
            status = state.status,
            meters = estimate?.meters,
            lowerMeters = estimate?.lowerMeters,
            upperMeters = estimate?.upperMeters,
            confidence = estimate?.confidence,
            quality = estimate?.quality,
            source = estimate?.source,
            isFreshAtCapture = estimate?.isFresh == true,
            ageMsAtCapture = null,
            cameraIdentity = estimate?.cameraIdentity,
            targetX = estimate?.target?.x,
            targetY = estimate?.target?.y,
            sampleCount = estimate?.sampleCount,
        )
    }
}

data class ParameterRecordCategory(
    val id: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val records: List<ParameterRecordEntry>,
) {
    val coverPath: String? get() = records.lastOrNull()?.previewPath

    val defaultFilm: ParameterFilmSelection?
        get() = records.firstNotNullOfOrNull { record ->
            val id = record.filmId ?: return@firstNotNullOfOrNull null
            val name = record.filmName ?: return@firstNotNullOfOrNull null
            ParameterFilmSelection(id, name, record.filmIso)
        }

    val filmSummary: String?
        get() {
            val names = records.mapNotNull(ParameterRecordEntry::filmName).distinct()
            return when {
                names.isEmpty() -> null
                names.size == 1 -> names.first()
                else -> "${names.first()}…"
            }
        }
}

data class ParameterFilmSelection(
    val id: String,
    val name: String,
    val iso: Int?,
)

data class RawRecordArtifact(
    val filePath: String,
    val rawGrid: RecordedRawGrid,
)
