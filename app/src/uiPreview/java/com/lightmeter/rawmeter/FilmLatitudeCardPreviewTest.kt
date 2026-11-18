package com.lightmeter.rawmeter

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilmLatitudeCardPreviewTest {
    @Test fun artworkSlotIsLandscapeAtBothListWidths() {
        for (width in listOf(600f, 800f)) {
            val artwork = FilmLatitudeCardGeometry.calculate(RectF(0f, 0f, width, 198f), 2f, 2f).artwork
            assertEquals(4f / 3f, artwork.width() / artwork.height(), 0.001f)
        }
    }

    @Test fun reelCornersUseTheCardSurfaceAndTheWhiteStickerRemains() {
        val app = RuntimeEnvironment.getApplication()
        val film = FilmLatitudeRepository(app).films().single { it.id == "builtin-104" }
        val bitmap = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888)
        val surface = Color.rgb(38, 38, 36)
        bitmap.eraseColor(surface)
        FilmPackagingRenderer(app.assets).draw(Canvas(bitmap), RectF(0f, 0f, 200f, 150f), film, surface, 2f)
        assertEquals(surface, bitmap.getPixel(30, 5))
        assertTrue((80..120).any { x ->
            (95..135).any { y ->
                val pixel = bitmap.getPixel(x, y)
                Color.red(pixel) > 225 && Color.green(pixel) > 225 && Color.blue(pixel) > 225
            }
        })
        bitmap.recycle()
    }

    @Test fun renderCineStillAndReelsInTheSelector() {
        val app = RuntimeEnvironment.getApplication()
        val context = ContextThemeWrapper(app, R.style.Theme_Lightstop)
        val state = MeterState(context).apply { appTheme = AppTheme.DARK }
        val repository = FilmLatitudeRepository(context)
        // Isolate the preview list from other tests that exercise favorites.
        for (film in repository.films()) {
            val shouldShow = film.id in setOf("builtin-1", "builtin-38", "builtin-43", "builtin-45",
                "builtin-104", "builtin-106", "builtin-113")
            if (repository.isFavorite(film.id) != shouldShow) repository.toggleFavorite(film.id)
        }
        val view = FilmSelectorView(context, state, repository, FilmReciprocityRepository(context))
        view.open(FilmSelectorMode.LATITUDE)
        view.measure(View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 840, 1800)
        val geometry = FilmSelectorGeometryCalculator.calculate(840, 1800, context.resources.displayMetrics.density, 0f)
        tap(view, geometry.favorites.centerX(), geometry.favorites.centerY())
        val bitmap = render(view)
        val output = File(requireNotNull(System.getProperty("uiPreview.output")), "film-latitude-cards")
            .apply { mkdirs() }
        File(output, "cine-packaging-dark.png").outputStream()
            .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun artworkAndFallbackRenderAndTheRightSideActionsReachTheSelectedFilm() {
        val output = File(requireNotNull(System.getProperty("uiPreview.output")), "film-latitude-cards")
            .apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        for (compact in listOf(false, true)) {
            val configuration = Configuration(app.resources.configuration).apply {
                if (compact) fontScale = 1.3f
            }
            val context = ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_Lightstop)
            val state = MeterState(context).apply {
                appTheme = if (compact) AppTheme.DARK else AppTheme.LIGHT
                menuLanguage = if (compact) MenuLanguage.ENGLISH else MenuLanguage.CHINESE
            }
            val repository = FilmLatitudeRepository(context)
            val view = FilmSelectorView(context, state, repository, FilmReciprocityRepository(context))
            val width = if (compact) 640 else 840
            val height = if (compact) 1440 else 1800
            val density = context.resources.displayMetrics.density
            view.open(FilmSelectorMode.LATITUDE)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val geometry = FilmSelectorGeometryCalculator.calculate(width, height, density, 0f)
            val row = repository.films().first { it.id == "builtin-7" }
            val search = view.getChildAt(0) as EditText
            if (!compact) {
                repository.saveOverride(row.id, FilmLatitudeRange(-2.0, 2.0))
                search.setText("ColorPlus 200")
                val actionY = geometry.list.top + 90f * density
                assertTrue(!repository.isFavorite(row.id))
                tap(view, geometry.list.right - 20f * density, actionY)
                assertTrue(repository.isFavorite(row.id))
                tap(view, geometry.list.right - 88f * density, actionY)
                assertEquals(row.originalRange, repository.effectiveRange(row))
                render(view).let { bitmap ->
                    assertEquals(Color.BLACK, bitmap.getPixel((geometry.list.left + 14f * density).toInt(),
                        (geometry.list.top + 18f * density).toInt()))
                    bitmap.recycle()
                }
            }

            search.setText("")
            for (id in listOf(7, 10, 30, 126, 199, 246, 260)) {
                val filmId = "builtin-$id"
                if (!repository.isFavorite(filmId)) repository.toggleFavorite(filmId)
            }
            tap(view, geometry.favorites.centerX(), geometry.favorites.centerY())
            val screenshot = render(view)
            File(output, if (compact) "compact-dark-en.png" else "portrait-light-zh.png").outputStream()
                .use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshot.recycle()
        }
    }

    private fun render(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun tap(view: View, x: Float, y: Float) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0L, 10L, action, x, y, 0)
            try {
                assertTrue(view.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }
}
