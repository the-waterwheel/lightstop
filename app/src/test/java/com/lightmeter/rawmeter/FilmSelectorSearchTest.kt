package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class FilmSelectorSearchTest {
    @Test
    fun `normalization matches names despite punctuation spaces case and accents`() {
        val tMax = film("tmax", "Kodak", "T-MAX 400")
        val fantome = film("fantome", "Lomography", "Fantôme Kino 8")

        assertEquals(listOf(tMax), FilmSelectorSearch.filterAndOrder(listOf(tMax, fantome), "t max"))
        assertEquals(listOf(fantome), FilmSelectorSearch.filterAndOrder(listOf(tMax, fantome), "fantome"))
    }

    @Test
    fun `alias-only match is included without changing its visible name`() {
        val portra = film("portra", "Kodak", "PORTRA 400", aliases = listOf("炮塔"))

        val result = FilmSelectorSearch.filterAndOrder(listOf(portra), "炮塔")

        assertEquals(listOf(portra), result)
        assertEquals("Kodak PORTRA 400", result.single().displayName)
    }

    @Test
    fun `manufacturer and family aliases can be combined with film speed`() {
        val portra = film(
            id = "portra",
            manufacturer = "Kodak",
            model = "PORTRA 400",
            iso = 400,
            aliases = listOf("柯达", "炮塔"),
        )

        assertEquals(listOf(portra), FilmSelectorSearch.filterAndOrder(listOf(portra), "柯达炮塔400"))
    }

    @Test
    fun `original name matches are ahead of alias-only matches`() {
        val aliasMatch = film("alias", "Kodak", "PORTRA 400", aliases = listOf("Acros"))
        val originalMatch = film("original", "Fujifilm", "NEOPAN 100 ACROS")

        val result = FilmSelectorSearch.filterAndOrder(listOf(aliasMatch, originalMatch), "acros")

        assertEquals(listOf(originalMatch, aliasMatch), result)
    }

    @Test
    fun `reciprocity availability only reorders films inside the same match rank`() {
        val aliasAvailable = film("alias-available", "Other", "Film", aliases = listOf("Acros"))
        val originalUnavailable = film("original-unavailable", "Fujifilm", "ACROS 100")
        val originalAvailable = film("original-available", "Fujifilm", "ACROS II 100")

        val result = FilmSelectorSearch.filterAndOrder(
            films = listOf(aliasAvailable, originalUnavailable, originalAvailable),
            query = "acros",
            isAvailable = { it.id.endsWith("available") && !it.id.startsWith("original-un") },
        )

        assertEquals(listOf(originalAvailable, originalUnavailable, aliasAvailable), result)
    }

    @Test
    fun `blank query preserves availability-first behavior`() {
        val unavailable = film("unavailable", "A", "One")
        val available = film("available", "B", "Two")

        val result = FilmSelectorSearch.filterAndOrder(
            films = listOf(unavailable, available),
            query = " ",
            isAvailable = { it.id == "available" },
        )

        assertEquals(listOf(available, unavailable), result)
    }

    private fun film(
        id: String,
        manufacturer: String,
        model: String,
        iso: Int? = null,
        aliases: List<String> = emptyList(),
    ) = FilmLatitudeProfile(
        id = id,
        manufacturer = manufacturer,
        model = model,
        iso = iso,
        type = "",
        discontinued = false,
        originalRange = FilmLatitudeRange.FULL_SCALE,
        searchAliases = aliases,
    )
}
