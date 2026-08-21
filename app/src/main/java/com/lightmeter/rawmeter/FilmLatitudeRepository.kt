package com.lightmeter.rawmeter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** User-owned latitude changes are kept separately from the immutable workbook values. */
internal class FilmLatitudeRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val builtIns = FilmLatitudeCatalog(context).films

    fun films(): List<FilmLatitudeProfile> = builtIns + customFilms()

    fun find(id: String?): FilmLatitudeProfile? = id?.let { target ->
        films().firstOrNull { it.id == target }
    }

    fun effectiveRange(profile: FilmLatitudeProfile): FilmLatitudeRange =
        overrides()[profile.id] ?: profile.originalRange

    fun isFavorite(id: String): Boolean = preferences.getStringSet(KEY_FAVORITES, emptySet())
        ?.contains(id) == true

    fun toggleFavorite(id: String): Boolean {
        val values = preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toMutableSet()
        val favorite = if (id in values) {
            values.remove(id)
            false
        } else {
            values.add(id)
            true
        }
        preferences.edit().putStringSet(KEY_FAVORITES, values).apply()
        return favorite
    }

    fun saveOverride(id: String, range: FilmLatitudeRange) {
        val values = overrides().toMutableMap()
        values[id] = range.ordered()
        saveRanges(KEY_OVERRIDES, values)
    }

    fun resetOverride(id: String) {
        val values = overrides().toMutableMap()
        if (values.remove(id) != null) saveRanges(KEY_OVERRIDES, values)
    }

    fun addCustomFilm(
        name: String,
        shadowEv: Double,
        highlightEv: Double,
    ): FilmLatitudeProfile {
        val profile = FilmLatitudeProfile(
            id = "custom-${UUID.randomUUID()}",
            manufacturer = "",
            model = name.trim(),
            iso = null,
            type = "",
            discontinued = false,
            originalRange = FilmLatitudeRange(shadowEv, highlightEv).ordered(),
            custom = true,
        )
        val array = loadArray(KEY_CUSTOM_FILMS)
        array.put(profile.toJson())
        preferences.edit().putString(KEY_CUSTOM_FILMS, array.toString()).apply()
        return profile
    }

    fun loadApplied(): AppliedFilmLatitude? {
        if (!preferences.getBoolean(KEY_APPLIED, false)) return null
        return AppliedFilmLatitude(
            range = FilmLatitudeRange(
                storedDouble(KEY_APPLIED_SHADOW, -5.0),
                storedDouble(KEY_APPLIED_HIGHLIGHT, 5.0),
            ).ordered(),
            filmId = preferences.getString(KEY_APPLIED_FILM_ID, null),
            filmName = preferences.getString(KEY_APPLIED_FILM_NAME, null),
        )
    }

    fun saveApplied(value: AppliedFilmLatitude) {
        preferences.edit()
            .putBoolean(KEY_APPLIED, true)
            .putString(KEY_APPLIED_SHADOW, value.range.shadowEv.toString())
            .putString(KEY_APPLIED_HIGHLIGHT, value.range.highlightEv.toString())
            .putString(KEY_APPLIED_FILM_ID, value.filmId)
            .putString(KEY_APPLIED_FILM_NAME, value.filmName)
            .apply()
    }

    fun clearApplied() {
        preferences.edit()
            .putBoolean(KEY_APPLIED, false)
            .remove(KEY_APPLIED_FILM_ID)
            .remove(KEY_APPLIED_FILM_NAME)
            .apply()
    }

    private fun customFilms(): List<FilmLatitudeProfile> {
        val array = loadArray(KEY_CUSTOM_FILMS)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                add(
                    FilmLatitudeProfile(
                        id = item.optString("id"),
                        manufacturer = "",
                        model = name,
                        iso = null,
                        type = "",
                        discontinued = false,
                        originalRange = FilmLatitudeRange(
                            item.optDouble("shadowEv", -5.0),
                            item.optDouble("highlightEv", 5.0),
                        ).ordered(),
                        custom = true,
                    ),
                )
            }
        }
    }

    private fun FilmLatitudeProfile.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", model)
        .put("shadowEv", originalRange.shadowEv)
        .put("highlightEv", originalRange.highlightEv)

    private fun overrides(): Map<String, FilmLatitudeRange> {
        val array = loadArray(KEY_OVERRIDES)
        return buildMap(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                put(
                    id,
                    FilmLatitudeRange(
                        item.optDouble("shadowEv", -5.0),
                        item.optDouble("highlightEv", 5.0),
                    ).ordered(),
                )
            }
        }
    }

    private fun saveRanges(key: String, values: Map<String, FilmLatitudeRange>) {
        val array = JSONArray()
        values.forEach { (id, range) ->
            array.put(
                JSONObject()
                    .put("id", id)
                    .put("shadowEv", range.shadowEv)
                    .put("highlightEv", range.highlightEv),
            )
        }
        preferences.edit().putString(key, array.toString()).apply()
    }

    private fun loadArray(key: String): JSONArray = try {
        JSONArray(preferences.getString(key, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    /** Accepts the short-lived float format used during development, then writes strings. */
    private fun storedDouble(key: String, fallback: Double): Double = try {
        preferences.getString(key, null)?.toDoubleOrNull() ?: fallback
    } catch (_: ClassCastException) {
        preferences.getFloat(key, fallback.toFloat()).toDouble()
    }

    private companion object {
        const val PREFERENCES_NAME = "film_latitude_state"
        const val KEY_FAVORITES = "favorites"
        const val KEY_OVERRIDES = "overrides"
        const val KEY_CUSTOM_FILMS = "custom_films"
        const val KEY_APPLIED = "applied"
        const val KEY_APPLIED_SHADOW = "applied_shadow"
        const val KEY_APPLIED_HIGHLIGHT = "applied_highlight"
        const val KEY_APPLIED_FILM_ID = "applied_film_id"
        const val KEY_APPLIED_FILM_NAME = "applied_film_name"
    }
}
