package com.lightmeter.rawmeter

import java.text.Normalizer
import java.util.Locale

internal object FilmSelectorSearch {
    /**
     * Original visible names and types always outrank matches found only through an alias.
     * Availability is deliberately a secondary key so reciprocity mode cannot reverse that rule.
     */
    fun filterAndOrder(
        films: List<FilmLatitudeProfile>,
        query: String,
        isAvailable: ((FilmLatitudeProfile) -> Boolean)? = null,
    ): List<FilmLatitudeProfile> {
        val normalizedQuery = FilmSearchText.normalize(query)
        if (normalizedQuery.isBlank()) {
            return isAvailable?.let { FilmSelectorOrdering.availableFirst(films, it) } ?: films
        }

        return films.mapIndexedNotNull { index, film ->
            val matchRank = when {
                film.primarySearchKeys.any { it.contains(normalizedQuery) } -> ORIGINAL_NAME_MATCH
                film.aliasSearchKeys.any { it.contains(normalizedQuery) } -> ALIAS_MATCH
                else -> null
            } ?: return@mapIndexedNotNull null
            RankedFilm(
                film = film,
                matchRank = matchRank,
                availabilityRank = if (isAvailable == null || isAvailable(film)) 0 else 1,
                sourceIndex = index,
            )
        }.sortedWith(
            compareBy<RankedFilm>(
                RankedFilm::matchRank,
                RankedFilm::availabilityRank,
                RankedFilm::sourceIndex,
            ),
        ).map(RankedFilm::film)
    }

    private data class RankedFilm(
        val film: FilmLatitudeProfile,
        val matchRank: Int,
        val availabilityRank: Int,
        val sourceIndex: Int,
    )

    private const val ORIGINAL_NAME_MATCH = 0
    private const val ALIAS_MATCH = 1
}

internal object FilmSearchText {
    fun primaryKeys(film: FilmLatitudeProfile): List<String> = listOf(
        normalize(film.displayName),
        normalize(film.type),
    )

    /** Supports natural combined queries such as "富士 400" or "柯达 炮塔 400". */
    fun aliasKeys(film: FilmLatitudeProfile): List<String> = buildList {
        addAll(film.searchAliases)
        film.searchAliases.forEach { alias ->
            add("$alias ${film.displayName}")
            film.iso?.let { speed -> add("$alias $speed") }
        }
        for (firstIndex in film.searchAliases.indices) {
            for (secondIndex in film.searchAliases.indices) {
                if (firstIndex == secondIndex) continue
                val pair = "${film.searchAliases[firstIndex]} ${film.searchAliases[secondIndex]}"
                add(pair)
                film.iso?.let { speed -> add("$pair $speed") }
            }
        }
    }.map(::normalize).distinct()

    /** NFKD also makes full-width text and accented Latin names behave like their typed variants. */
    fun normalize(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
