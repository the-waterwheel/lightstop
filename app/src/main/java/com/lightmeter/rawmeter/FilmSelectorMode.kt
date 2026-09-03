package com.lightmeter.rawmeter

internal enum class FilmSelectorMode { STANDARD, RECIPROCITY }

internal object FilmSelectorOrdering {
    fun <T> availableFirst(items: List<T>, isAvailable: (T) -> Boolean): List<T> =
        items.sortedBy { item -> if (isAvailable(item)) 0 else 1 }
}
