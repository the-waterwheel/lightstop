package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal data class FilmSelectorGeometry(
    val back: RectF,
    val add: RectF,
    val search: RectF,
    val favorites: RectF,
    val list: RectF,
    val rowHeight: Float,
) {
    companion object {
        val EMPTY = FilmSelectorGeometry(RectF(), RectF(), RectF(), RectF(), RectF(), 1f)
    }
}

internal object FilmSelectorGeometryCalculator {
    fun calculate(
        width: Int,
        height: Int,
        density: Float,
        safeTop: Float,
    ): FilmSelectorGeometry {
        if (width <= 0 || height <= 0) return FilmSelectorGeometry.EMPTY
        val density = AdaptiveLayout.density(width, height, density, LayoutProfile.SCROLL)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = max(10f * density, min(w, h) * 0.018f)
        val headerTop = max(pad, safeTop + 4f * density)
        val headerHeight = 48f * density
        val target = 48f * density
        val back = RectF(pad, headerTop, pad + target, headerTop + headerHeight)
        val controlsTop = headerTop + headerHeight + 5f * density
        val controlHeight = 48f * density
        val add = RectF(pad, controlsTop, pad + controlHeight, controlsTop + controlHeight)
        val favorites = RectF(w - pad - controlHeight, controlsTop, w - pad, controlsTop + controlHeight)
        val search = RectF(add.right + 6f * density, controlsTop, favorites.left - 6f * density, controlsTop + controlHeight)
        val listTop = controlsTop + controlHeight + 8f * density
        val list = RectF(pad, listTop, w - pad, h - pad)
        val rowHeight = (list.height() * 0.15f).coerceIn(72f * density, 92f * density)
        return FilmSelectorGeometry(back, add, search, favorites, list, rowHeight)
    }
}

internal data class FilmLatitudeCardGeometry(
    val artwork: RectF,
    val textLeft: Float,
    val textRight: Float,
    val reset: RectF,
    val favorite: RectF,
) {
    companion object {
        private const val ARTWORK_ASPECT = 4f / 3f

        fun calculate(row: RectF, density: Float, textDensity: Float): FilmLatitudeCardGeometry {
            val artworkWidth = min((row.height() - 16f * density) * ARTWORK_ASPECT, row.width() * 0.31f)
            val halfArtworkHeight = artworkWidth / ARTWORK_ASPECT / 2f
            val artwork = RectF(
                row.left + 8f * density,
                row.centerY() - halfArtworkHeight,
                row.left + 8f * density + artworkWidth,
                row.centerY() + halfArtworkHeight,
            )
            val textLeft = artwork.right + 10f * density
            val actionTop = row.bottom - 36f * density
            val favorite = RectF(row.right - 44f * density, actionTop, row.right, row.bottom)
            val reset = RectF(max(textLeft, favorite.left - 106f * textDensity), actionTop, favorite.left, row.bottom)
            return FilmLatitudeCardGeometry(artwork, textLeft, row.right - 10f * density, reset, favorite)
        }
    }
}
