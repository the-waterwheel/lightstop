package com.lightmeter.rawmeter

import kotlin.math.ln
import kotlin.math.exp

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

data class RecordedRawGrid(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    val referenceLuma: Float,
    val screenToSensorRotationDegrees: Int = 0,
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
        val screenX = x.coerceIn(0f, 1f)
        val screenY = y.coerceIn(0f, 1f)
        val (sensorX, sensorY) = when (((screenToSensorRotationDegrees % 360) + 360) % 360) {
            90 -> screenY to (1f - screenX)
            180 -> (1f - screenX) to (1f - screenY)
            270 -> (1f - screenY) to screenX
            else -> screenX to screenY
        }
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
