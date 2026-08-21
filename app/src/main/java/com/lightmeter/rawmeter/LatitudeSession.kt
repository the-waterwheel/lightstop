package com.lightmeter.rawmeter

import kotlin.math.round

internal enum class LatitudeBoundary { SHADOW, HIGHLIGHT }

/** UI-independent state machine for selection, marker ordering, and metering application. */
internal class LatitudeSession {
    var selectedFilmId: String? = null
        private set
    var range: FilmLatitudeRange = FilmLatitudeRange.FULL_SCALE
        private set
    var applied: Boolean = false
        private set
    var initialized: Boolean = false
        private set

    fun open(repository: FilmLatitudeRepository) {
        val active = repository.loadApplied()
        selectedFilmId = active?.filmId
        range = active?.range ?: FilmLatitudeRange.FULL_SCALE
        applied = active != null
        initialized = true
    }

    fun select(profile: FilmLatitudeProfile, range: FilmLatitudeRange) {
        selectedFilmId = profile.id
        this.range = range.ordered()
    }

    fun reset() {
        selectedFilmId = null
        range = FilmLatitudeRange.FULL_SCALE
    }

    fun setBoundary(boundary: LatitudeBoundary, zone: Double): Boolean {
        val ev = round((zone.coerceIn(0.0, 10.0) - FilmLatitudeRange.MIDDLE_GRAY_ZONE) * 10.0) / 10.0
        val next = when (boundary) {
            LatitudeBoundary.SHADOW -> FilmLatitudeRange(
                shadowEv = ev.coerceAtMost(range.highlightEv),
                highlightEv = range.highlightEv,
            )
            LatitudeBoundary.HIGHLIGHT -> FilmLatitudeRange(
                shadowEv = range.shadowEv,
                highlightEv = ev.coerceAtLeast(range.shadowEv),
            )
        }
        if (next == range) return false
        range = next
        return true
    }

    fun setApplied(value: Boolean) {
        applied = value
    }
}
