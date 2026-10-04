package com.lightmeter.rawmeter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Latitude endpoints are expressed in EV relative to an 18% gray card (Zone V). */
data class FilmLatitudeRange(
    val shadowEv: Double,
    val highlightEv: Double,
) {
    val lowerZone: Double get() = (MIDDLE_GRAY_ZONE + shadowEv).coerceIn(MIN_ZONE, MAX_ZONE)
    val upperZone: Double get() = (MIDDLE_GRAY_ZONE + highlightEv).coerceIn(MIN_ZONE, MAX_ZONE)

    fun containsZone(zone: Double): Boolean =
        zone + COMPARISON_EPSILON >= MIDDLE_GRAY_ZONE + shadowEv &&
            zone - COMPARISON_EPSILON <= MIDDLE_GRAY_ZONE + highlightEv

    fun ordered(): FilmLatitudeRange = if (shadowEv <= highlightEv) {
        this
    } else {
        FilmLatitudeRange(highlightEv, shadowEv)
    }

    companion object {
        const val MIN_ZONE = 0.0
        const val MAX_ZONE = 10.0
        const val MIDDLE_GRAY_ZONE = 5.0
        const val MIN_CUSTOM_EV = -20.0
        const val MAX_CUSTOM_EV = 20.0
        private const val COMPARISON_EPSILON = 1e-6
        val FULL_SCALE = FilmLatitudeRange(-5.0, 5.0)
    }
}

data class FilmLatitudeProfile(
    val id: String,
    val manufacturer: String,
    val model: String,
    val iso: Int?,
    val type: String,
    val discontinued: Boolean,
    val originalRange: FilmLatitudeRange,
    val custom: Boolean = false,
    /** Search-only metadata. These names are never used as the visible film name. */
    val searchAliases: List<String> = emptyList(),
) {
    val displayName: String
        get() = listOf(manufacturer, model).filter(String::isNotBlank).joinToString(" ")

    internal val primarySearchKeys: List<String> by lazy {
        FilmSearchText.primaryKeys(this)
    }
    internal val aliasSearchKeys: List<String> by lazy {
        FilmSearchText.aliasKeys(this)
    }
}

data class AppliedFilmLatitude(
    val range: FilmLatitudeRange,
    val filmId: String?,
    val filmName: String?,
)

/** Parses the immutable workbook export plus the audited manufacturer-document overlay. */
internal class FilmLatitudeCatalog(context: Context) {
    private val updates = context.assets
        .open(FILM_DATABASE_UPDATES_ASSET)
        .bufferedReader()
        .use { reader -> JSONObject(reader.readText()) }
    private val searchAliases = FilmSearchAliasCatalog(
        context.assets.open(FILM_SEARCH_ALIASES_ASSET)
            .bufferedReader()
            .use { reader -> JSONObject(reader.readText()) },
    )

    val films: List<FilmLatitudeProfile> = buildList {
        addAll(
            context.assets.open("film_latitude_2026_08_17.json")
                .bufferedReader()
                .use { reader -> parse(JSONArray(reader.readText())) },
        )
        val patches = updates.optJSONArray("profilePatches") ?: JSONArray()
        val patchById = buildMap {
            for (index in 0 until patches.length()) {
                val patch = patches.getJSONObject(index)
                put("builtin-${patch.getInt("id")}", patch)
            }
        }
        replaceAll { profile -> patchById[profile.id]?.let { profile.patchedWith(it) } ?: profile }
        addAll(parse(updates.optJSONArray("profiles") ?: JSONArray()))
    }.distinctBy(FilmLatitudeProfile::id)
        .map { profile -> profile.copy(searchAliases = searchAliases.aliasesFor(profile)) }

    private fun parse(array: JSONArray): List<FilmLatitudeProfile> = buildList(array.length()) {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                FilmLatitudeProfile(
                    id = "builtin-${item.getInt("id")}",
                    manufacturer = item.optString("manufacturer"),
                    model = item.optString("model"),
                    iso = item.optInt("iso").takeIf { it > 0 },
                    type = item.optString("type"),
                    discontinued = item.optBoolean("discontinued"),
                    originalRange = FilmLatitudeRange(
                        shadowEv = item.getDouble("shadowEv"),
                        highlightEv = item.getDouble("highlightEv"),
                    ).ordered(),
                ),
            )
        }
    }

    private fun FilmLatitudeProfile.patchedWith(item: JSONObject): FilmLatitudeProfile = copy(
        manufacturer = item.optString("manufacturer", manufacturer),
        model = item.optString("model", model),
        iso = if (item.has("iso")) item.optInt("iso").takeIf { it > 0 } else iso,
        type = item.optString("type", type),
        discontinued = if (item.has("discontinued")) item.optBoolean("discontinued") else discontinued,
        originalRange = if (item.has("shadowEv") && item.has("highlightEv")) {
            FilmLatitudeRange(
                shadowEv = item.getDouble("shadowEv"),
                highlightEv = item.getDouble("highlightEv"),
            ).ordered()
        } else {
            originalRange
        },
    )

    private companion object {
        const val FILM_DATABASE_UPDATES_ASSET = "film_database_updates_2026_09_08.json"
        const val FILM_SEARCH_ALIASES_ASSET = "film_search_aliases_2026_09_19.json"
    }
}

/** Keeps alternate names out of the presentation model while making the index data-driven. */
private class FilmSearchAliasCatalog(root: JSONObject) {
    private val manufacturerAliases = root.optJSONArray("manufacturerAliases")
        .objects()
        .associate { item -> item.optString("manufacturer") to item.stringList("aliases") }

    private val modelAliases = root.optJSONArray("modelAliases")
        .objects()
        .map { item -> item.optString("modelContains") to item.stringList("aliases") }

    private val profileAliases = root.optJSONArray("profileAliases")
        .objects()
        .associate { item -> "builtin-${item.getInt("id")}" to item.stringList("aliases") }

    fun aliasesFor(profile: FilmLatitudeProfile): List<String> = buildList {
        addAll(manufacturerAliases[profile.manufacturer].orEmpty())
        modelAliases.forEach { (modelFragment, aliases) ->
            if (modelFragment.isNotBlank() && profile.model.contains(modelFragment, ignoreCase = true)) {
                addAll(aliases)
            }
        }
        addAll(profileAliases[profile.id].orEmpty())
    }.distinct()

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) {
        emptyList()
    } else {
        buildList(length()) {
            for (index in 0 until length()) optJSONObject(index)?.let(::add)
        }
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val values = optJSONArray(key) ?: return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                values.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }
}
