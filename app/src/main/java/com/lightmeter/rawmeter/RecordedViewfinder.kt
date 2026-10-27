package com.lightmeter.rawmeter

/** Coordinates refer to the saved JPEG, before the gray framing overlay is drawn. */
data class RecordedViewfinder(
    val formatId: String,
    val frameLandscape: Boolean,
    val focalLengthMm: Int?,
    val framingZoom: Float,
    val inspectionZoom: Float,
    val frameCrop: NormalizedSensorViewport?,
    val meteringMode: MeteringMode,
    val meteringAngleDegrees: Int?,
    val meteringSource: MeteringSource?,
    val screenToSensorRotationDegrees: Int = 0,
    val screenToSensorMirrored: Boolean = false,
) {
    fun summary(language: MenuLanguage): String {
        val english = language == MenuLanguage.ENGLISH
        val format = FrameFormat.ALL.firstOrNull { it.id == formatId }
        val label = format?.displayLabel(language) ?: formatId
        val composition = if (frameLandscape) if (english) "Landscape" else "横拍" else if (english) "Portrait" else "竖拍"
        return if (frameCrop == null) "$label · ${"%.1f".format(inspectionZoom)}×" else listOfNotNull(label, composition, focalLengthMm?.let { "≈ $it mm" }).joinToString(" · ")
    }

    fun meteringLabel(language: MenuLanguage): String = when (meteringMode) {
        MeteringMode.AVERAGE -> if (language == MenuLanguage.ENGLISH) "Global" else "全局测光"
        MeteringMode.CENTER_WEIGHTED -> if (language == MenuLanguage.ENGLISH) "Center weighted" else "中央重点"
        MeteringMode.SPOT -> if (language == MenuLanguage.ENGLISH) "Spot" else "点测光"
        MeteringMode.ANGLE -> "${meteringAngleDegrees ?: AngleMeteringMath.DEFAULT_DEGREES}°"
    }
}

/** Reject empty/invalid grid cells instead of creating points with invented EVs. */
internal object RecordedPointEditing {
    fun toggle(points: List<RecordedZonePoint>, x: Float, y: Float, radiusX: Float, radiusY: Float,
        evAt: (Float, Float) -> Double?): List<RecordedZonePoint>? {
        val existing = points.firstOrNull { kotlin.math.abs(it.normalizedX - x) < radiusX && kotlin.math.abs(it.normalizedY - y) < radiusY }
        if (existing != null) return points.filterNot { it.id == existing.id }
        if (points.size >= 100 || x !in 0f..1f || y !in 0f..1f) return null
        val ev = evAt(x, y)?.takeIf(Double::isFinite) ?: return null
        return points + RecordedZonePoint((points.maxOfOrNull { it.id } ?: 0) + 1, x, y, ev, MeteringSource.RAW)
    }
}
