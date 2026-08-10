package com.lightmeter.rawmeter

import kotlin.math.roundToInt

enum class ZoneTrackingState {
    PENDING,
    TRACKED,
    UNCERTAIN,
    LOST,
}

data class ZoneMarker(
    val id: Int,
    var ev100: Double? = null,
    val weight: Double = 1.0,
    var normalizedX: Float = 0.5f,
    var normalizedY: Float = 0.5f,
    var trackingState: ZoneTrackingState = ZoneTrackingState.PENDING,
    var source: MeteringSource = MeteringSource.RAW,
)

/**
 * Session-only state for Zone System placement. Measurements are deliberately not persisted:
 * they describe the current scene, while app settings and calibration continue to be persisted
 * by [MeterState].
 */
class ZoneMeterSession {
    val markers: MutableList<ZoneMarker> = mutableListOf()

    var iso: Int = 100
        private set

    var apertureCoordinate: Double = ExposureMath.apertureStop(5.6)
        private set
    var shutterCoordinate: Double = ExposureMath.log2(1.0 / 125.0)
        private set
    var selectedMarkerId: Int? = null
        private set
    var pendingMarkerId: Int? = null
        private set
    var initialized = false
        private set

    private var placementOffsetBeforePending = 0.0

    fun initializeFromMeter(state: MeterState) {
        // Zone mode deliberately has no ISO control. Capture the ISO selected in Normal every
        // time Zone is entered, then use this immutable session value for every calculation.
        iso = state.iso
        val evAtIso = (state.effectiveEv100 ?: 10.0) + ExposureMath.log2(iso / 100.0)
        if (state.exposureLockMode == ExposureLockMode.APERTURE) {
            apertureCoordinate = state.lockedApertureStop
            shutterCoordinate = apertureCoordinate - evAtIso
        } else {
            shutterCoordinate = state.lockedShutterLogSeconds
            apertureCoordinate = shutterCoordinate + evAtIso
        }
        clampCoordinates()
        initialized = true
    }

    fun beginMarker(
        iso: Int,
        normalizedX: Float = 0.5f,
        normalizedY: Float = 0.5f,
    ): ZoneMarker? {
        if (pendingMarkerId != null) return null
        placementOffsetBeforePending = if (markers.any { it.ev100 != null }) {
            weightedMeanEv100() - selectedExposureEv100(iso)
        } else {
            0.0
        }
        // IDs describe the points that still exist in the current scene. Deleted IDs do not
        // reserve a slot; when the list becomes empty, the next point starts at 1 again.
        val marker = ZoneMarker(
            id = (markers.maxOfOrNull(ZoneMarker::id) ?: 0) + 1,
            normalizedX = normalizedX.coerceIn(0f, 1f),
            normalizedY = normalizedY.coerceIn(0f, 1f),
        )
        markers += marker
        pendingMarkerId = marker.id
        selectedMarkerId = null
        return marker
    }

    fun completePending(reading: MeterReading, iso: Int, lockMode: ExposureLockMode): ZoneMarker? {
        val id = pendingMarkerId ?: return null
        val marker = markers.firstOrNull { it.id == id } ?: return null
        marker.ev100 = reading.sceneEv100
        marker.source = reading.source
        marker.trackingState = ZoneTrackingState.TRACKED
        pendingMarkerId = null
        val targetExposureEv100 = weightedMeanEv100() - placementOffsetBeforePending
        setExposureEv100(targetExposureEv100, iso, lockMode)
        return marker
    }

    fun cancelPending(): ZoneMarker? {
        val id = pendingMarkerId ?: return null
        pendingMarkerId = null
        return markers.firstOrNull { it.id == id }?.also(markers::remove)
    }

    fun selectedExposureEv100(iso: Int): Double =
        apertureCoordinate - shutterCoordinate - ExposureMath.log2(iso / 100.0)

    fun zoneFor(marker: ZoneMarker, iso: Int): Double? =
        marker.ev100?.let { 5.0 + it - selectedExposureEv100(iso) }

    fun weightedMeanEv100(): Double {
        val measured = markers.filter { it.ev100 != null && it.weight > 0.0 }
        val totalWeight = measured.sumOf(ZoneMarker::weight)
        if (totalWeight <= 0.0) return selectedExposureEv100(100)
        return measured.sumOf { it.ev100!! * it.weight } / totalWeight
    }

    fun weightedMeanZone(iso: Int): Double? {
        if (markers.none { it.ev100 != null }) return null
        return 5.0 + weightedMeanEv100() - selectedExposureEv100(iso)
    }

    fun shiftZones(deltaZones: Double, iso: Int, lockMode: ExposureLockMode) {
        if (markers.none { it.ev100 != null }) return
        val targetExposure = selectedExposureEv100(iso) - deltaZones
        setExposureEv100(targetExposure, iso, lockMode)
    }

    fun setManualAperture(coordinate: Double, snap: Boolean) {
        apertureCoordinate = if (snap) {
            ExposureMath.nearestApertureStop(coordinate)
        } else {
            coordinate.coerceIn(ExposureMath.minApertureStop, ExposureMath.maxApertureStop)
        }
    }

    fun setManualShutter(coordinate: Double, snap: Boolean) {
        shutterCoordinate = if (snap) {
            ExposureMath.nearestShutterLogSeconds(coordinate)
        } else {
            coordinate.coerceIn(
                ExposureMath.minShutterLogSeconds,
                ExposureMath.maxShutterLogSeconds,
            )
        }
    }

    fun syncLockedCoordinate(state: MeterState) {
        state.setLockedExposureCoordinate(
            if (state.exposureLockMode == ExposureLockMode.APERTURE) {
                apertureCoordinate
            } else {
                shutterCoordinate
            },
        )
        state.snapLockedExposure()
    }

    fun selectMarker(id: Int?): Boolean {
        if (id == null || markers.none { it.id == id }) {
            selectedMarkerId = null
            return false
        }
        if (selectedMarkerId == id) return true
        selectedMarkerId = id
        return false
    }

    fun removeMarker(id: Int, iso: Int, lockMode: ExposureLockMode): ZoneMarker? {
        if (pendingMarkerId == id) return null
        val placementOffset = if (markers.any { it.ev100 != null }) {
            weightedMeanEv100() - selectedExposureEv100(iso)
        } else {
            0.0
        }
        val marker = markers.firstOrNull { it.id == id } ?: return null
        markers.remove(marker)
        if (selectedMarkerId == id) selectedMarkerId = null
        if (markers.any { it.ev100 != null }) {
            setExposureEv100(weightedMeanEv100() - placementOffset, iso, lockMode)
        }
        return marker
    }

    fun clear(): List<ZoneMarker> {
        val removed = markers.toList()
        markers.clear()
        pendingMarkerId = null
        selectedMarkerId = null
        return removed
    }

    fun updateTracking(id: Int, x: Float, y: Float, trackingState: ZoneTrackingState) {
        markers.firstOrNull { it.id == id }?.let { marker ->
            // Keep virtual off-screen coordinates so the tracker can bring a point back when
            // the camera returns. Rendering and hit-testing simply ignore positions outside the
            // visible 0..1 viewport.
            marker.normalizedX = x
            marker.normalizedY = y
            marker.trackingState = trackingState
        }
    }

    fun formattedZone(marker: ZoneMarker, iso: Int): String {
        val zone = zoneFor(marker, iso) ?: return "--"
        if (zone < 0.0) return "<0"
        if (zone > 10.0) return ">X"
        val nearest = zone.roundToInt().coerceIn(0, 10)
        val difference = zone - nearest
        val base = ZONE_LABELS[nearest]
        return if (kotlin.math.abs(difference) < 0.05) {
            base
        } else {
            "$base${if (difference >= 0.0) "+" else ""}${"%.1f".format(difference)}"
        }
    }

    private fun setExposureEv100(targetEv100: Double, iso: Int, lockMode: ExposureLockMode) {
        val targetAtIso = targetEv100 + ExposureMath.log2(iso / 100.0)
        if (lockMode == ExposureLockMode.APERTURE) {
            shutterCoordinate = (apertureCoordinate - targetAtIso).coerceIn(
                ExposureMath.minShutterLogSeconds,
                ExposureMath.maxShutterLogSeconds,
            )
        } else {
            apertureCoordinate = (shutterCoordinate + targetAtIso).coerceIn(
                ExposureMath.minApertureStop,
                ExposureMath.maxApertureStop,
            )
        }
    }

    private fun clampCoordinates() {
        apertureCoordinate = apertureCoordinate.coerceIn(
            ExposureMath.minApertureStop,
            ExposureMath.maxApertureStop,
        )
        shutterCoordinate = shutterCoordinate.coerceIn(
            ExposureMath.minShutterLogSeconds,
            ExposureMath.maxShutterLogSeconds,
        )
    }

    companion object {
        val ZONE_LABELS = listOf("0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
    }
}
