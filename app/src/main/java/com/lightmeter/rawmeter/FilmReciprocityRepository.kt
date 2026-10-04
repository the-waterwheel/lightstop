package com.lightmeter.rawmeter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log2

internal class FilmReciprocityCatalog(context: Context) {
    private val root = context.assets
        .open("film_reciprocity_2026_08_24.json")
        .bufferedReader()
        .use { JSONObject(it.readText()) }
    private val updates = context.assets
        .open(FILM_DATABASE_UPDATES_ASSET)
        .bufferedReader()
        .use { JSONObject(it.readText()) }

    val methods: Map<String, ReciprocityMethod> = buildMap {
        parseMethods(root.getJSONArray("methods")).forEach { method -> put(method.id, method) }
        parseMethods(updates.optJSONArray("methods") ?: JSONArray()).forEach { method -> put(method.id, method) }
    }

    val methodIdByFilmId: Map<String, String> = buildMap {
        putMappings(root.getJSONArray("films"))
        putMappings(updates.optJSONArray("films") ?: JSONArray())
    }

    private fun parseMethods(array: JSONArray): List<ReciprocityMethod> = buildList(array.length()) {
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
            add(
                ReciprocityMethod(
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
                ),
            )
        }
    }

    private fun MutableMap<String, String>.putMappings(array: JSONArray) {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            put("builtin-${item.getInt("filmId")}", item.getString("methodId"))
        }
    }

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf(Double::isFinite)

    private companion object {
        const val FILM_DATABASE_UPDATES_ASSET = "film_database_updates_2026_09_08.json"
    }
}

/** Keeps calculator selection and metering application independent from editable latitude data. */
internal class FilmReciprocityRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val catalog = FilmReciprocityCatalog(context)
    private val userOverrides = loadOverrides().toMutableMap()

    fun methodForFilm(filmId: String?): ReciprocityMethod? = filmId?.let { id ->
        userOverrides[id] ?: originalMethodForFilm(id)
    }

    fun originalMethodForFilm(filmId: String?): ReciprocityMethod? = filmId
        ?.let(catalog.methodIdByFilmId::get)
        ?.let(catalog.methods::get)

    fun hasCalculationData(filmId: String?): Boolean = methodForFilm(filmId)?.hasCalculationData == true

    fun hasUserOverride(filmId: String): Boolean = filmId in userOverrides

    fun saveUserOverride(filmId: String, value: ReciprocityMethod) {
        val points = value.points
            .filter { point ->
                point.meteredSeconds.isFinite() && point.correctedSeconds.isFinite() &&
                    point.meteredSeconds > 0.0 && point.correctedSeconds >= point.meteredSeconds
            }
            .sortedBy(ReciprocityPoint::meteredSeconds)
            .distinctBy(ReciprocityPoint::meteredSeconds)
        val method = value.copy(
            id = "user-$filmId",
            evidence = "USER",
            warning = "用户录入的倒易率数据；建议通过实拍和包围曝光验证。",
            sourceUrl = "",
            points = points,
        )
        require(method.hasCalculationData) { "User reciprocity method is not calculable" }
        require(method.hasValidPointCurve()) { "User reciprocity point curve is not monotone in stops" }
        userOverrides[filmId] = method
        saveOverrides()
    }

    fun resetUserOverride(filmId: String): Boolean {
        val removed = userOverrides.remove(filmId) != null
        if (removed) saveOverrides()
        return removed
    }

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

    private fun loadOverrides(): Map<String, ReciprocityMethod> {
        val array = try {
            JSONArray(preferences.getString(KEY_USER_OVERRIDES, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        return buildMap(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val filmId = item.optString("filmId").trim()
                val methodJson = item.optJSONObject("method") ?: continue
                val method = parseUserMethod(filmId, methodJson) ?: continue
                if (filmId.isNotBlank()) put(filmId, method)
            }
        }
    }

    private fun parseUserMethod(filmId: String, item: JSONObject): ReciprocityMethod? {
        val type = runCatching { ReciprocityMethodType.valueOf(item.optString("type")) }.getOrNull()
            ?.takeIf { it in USER_EDITABLE_TYPES }
            ?: return null
        val noCompensation = item.nullableDouble("noCompensationSeconds")
            ?.takeIf { it > 0.0 }
            ?: return null
        val pointsArray = item.optJSONArray("points") ?: JSONArray()
        val points = buildList {
            for (index in 0 until pointsArray.length()) {
                val point = pointsArray.optJSONObject(index) ?: continue
                val metered = point.nullableDouble("meteredSeconds") ?: continue
                val corrected = point.nullableDouble("correctedSeconds") ?: continue
                if (metered > 0.0 && corrected >= metered) {
                    add(ReciprocityPoint(metered, corrected, null))
                }
            }
        }.sortedBy(ReciprocityPoint::meteredSeconds).distinctBy(ReciprocityPoint::meteredSeconds)
        val method = ReciprocityMethod(
            id = "user-$filmId",
            type = type,
            parameter = item.nullableDouble("parameter"),
            noCompensationSeconds = noCompensation,
            officialMaximumSeconds = item.nullableDouble("officialMaximumSeconds"),
            evidence = "USER",
            longExposureFilter = "",
            filterRule = "",
            warning = "用户录入的倒易率数据；建议通过实拍和包围曝光验证。",
            sourceUrl = "",
            points = points,
        )
        return method.takeIf { it.hasCalculationData && it.hasValidPointCurve() }
    }

    private fun saveOverrides() {
        val array = JSONArray()
        userOverrides.forEach { (filmId, method) ->
            val points = JSONArray()
            method.points.forEach { point ->
                points.put(
                    JSONObject()
                        .put("meteredSeconds", point.meteredSeconds)
                        .put("correctedSeconds", point.correctedSeconds),
                )
            }
            val value = JSONObject()
                .put("type", method.type.name)
                .put("parameter", method.parameter ?: JSONObject.NULL)
                .put("noCompensationSeconds", method.noCompensationSeconds)
                .put("officialMaximumSeconds", method.officialMaximumSeconds ?: JSONObject.NULL)
                .put("points", points)
            array.put(JSONObject().put("filmId", filmId).put("method", value))
        }
        preferences.edit().putString(KEY_USER_OVERRIDES, array.toString()).apply()
    }

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf(Double::isFinite)

    private fun ReciprocityMethod.hasValidPointCurve(): Boolean {
        if (type != ReciprocityMethodType.TABLE) return true
        if (points.any { it.meteredSeconds <= noCompensationSeconds }) return false
        return points.zipWithNext().none { (left, right) ->
            log2(right.correctedSeconds / right.meteredSeconds) + 1e-9 <
                log2(left.correctedSeconds / left.meteredSeconds)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "film_reciprocity_state"
        const val KEY_SELECTED_FILM_ID = "selected_film_id"
        const val KEY_APPLIED = "applied"
        const val KEY_APPLIED_FILM_ID = "applied_film_id"
        const val KEY_USER_OVERRIDES = "user_method_overrides_v1"
        val USER_EDITABLE_TYPES = setOf(
            ReciprocityMethodType.TABLE,
            ReciprocityMethodType.POWER,
            ReciprocityMethodType.FIXED_EV,
            ReciprocityMethodType.BOUNDED_UNCHANGED,
        )
    }
}
