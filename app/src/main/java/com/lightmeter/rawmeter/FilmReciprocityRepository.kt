package com.lightmeter.rawmeter

import android.content.Context
import org.json.JSONObject

internal class FilmReciprocityCatalog(context: Context) {
    private val root = context.assets
        .open("film_reciprocity_2026_08_23.json")
        .bufferedReader()
        .use { JSONObject(it.readText()) }

    val methods: Map<String, ReciprocityMethod> = buildMap {
        val array = root.getJSONArray("methods")
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val pointsArray = item.getJSONArray("points")
            val points = buildList(pointsArray.length()) {
                for (pointIndex in 0 until pointsArray.length()) {
                    val point = pointsArray.getJSONObject(pointIndex)
                    add(
                        ReciprocityPoint(
                            meteredSeconds = point.getDouble("meteredSeconds"),
                            correctedSeconds = point.getDouble("correctedSeconds"),
                            filter = point.optString("filter").trim().takeIf(String::isNotEmpty),
                        ),
                    )
                }
            }
            val method = ReciprocityMethod(
                id = item.getString("id"),
                type = runCatching {
                    ReciprocityMethodType.valueOf(item.getString("type"))
                }.getOrDefault(ReciprocityMethodType.NONE),
                parameter = item.nullableDouble("parameter"),
                noCompensationSeconds = item.nullableDouble("noCompensationSeconds") ?: 1.0,
                officialMaximumSeconds = item.nullableDouble("officialMaximumSeconds"),
                evidence = item.optString("evidence"),
                longExposureFilter = item.optString("longExposureFilter"),
                filterRule = item.optString("filterRule"),
                warning = item.optString("warning"),
                sourceUrl = item.optString("sourceUrl"),
                points = points,
            )
            put(method.id, method)
        }
    }

    val methodIdByFilmId: Map<String, String> = buildMap {
        val array = root.getJSONArray("films")
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            put("builtin-${item.getInt("filmId")}", item.getString("methodId"))
        }
    }

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf(Double::isFinite)
}

/** Keeps calculator selection and metering application independent from editable latitude data. */
internal class FilmReciprocityRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val catalog = FilmReciprocityCatalog(context)

    fun methodForFilm(filmId: String?): ReciprocityMethod? = filmId
        ?.let(catalog.methodIdByFilmId::get)
        ?.let(catalog.methods::get)

    fun selectedFilmId(): String? = preferences.getString(KEY_SELECTED_FILM_ID, null)

    fun saveSelectedFilmId(filmId: String) {
        preferences.edit().putString(KEY_SELECTED_FILM_ID, filmId).apply()
    }

    fun clearSelectedFilmId() {
        preferences.edit().remove(KEY_SELECTED_FILM_ID).apply()
    }

    fun appliedFilmId(): String? = if (preferences.getBoolean(KEY_APPLIED, false)) {
        preferences.getString(KEY_APPLIED_FILM_ID, null)
    } else {
        null
    }

    fun appliedMethod(): ReciprocityMethod? = methodForFilm(appliedFilmId())

    fun saveAppliedFilmId(filmId: String) {
        preferences.edit()
            .putBoolean(KEY_APPLIED, true)
            .putString(KEY_APPLIED_FILM_ID, filmId)
            .apply()
    }

    fun clearApplied() {
        preferences.edit()
            .putBoolean(KEY_APPLIED, false)
            .remove(KEY_APPLIED_FILM_ID)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "film_reciprocity_state"
        const val KEY_SELECTED_FILM_ID = "selected_film_id"
        const val KEY_APPLIED = "applied"
        const val KEY_APPLIED_FILM_ID = "applied_film_id"
    }
}
