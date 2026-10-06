package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.UUID

/** Synthetic, app-private fixtures only: no camera, personal records or real RAW files. */
internal object RecordPreviewFixtures {
    fun seed(repository: ParameterRecordRepository) {
        if (repository.categories().isNotEmpty()) return
        for ((group, film) in listOf("Kodak PORTRA 400", "ILFORD HP5 PLUS 400").withIndex()) {
            repository.startCategory(1_791_000_000_000L + group * 86_400_000L)
            for (index in 0..4) {
                val draft = draft(repository, index).copy(filmName = film, filmId = "fixture-$group", filmIso = 400)
                repository.save(draft)
            }
            repository.finishActiveCategory()
        }
    }

    fun draft(repository: ParameterRecordRepository, index: Int = 0): ParameterCaptureDraft {
        val id = UUID.randomUUID().toString()
        val file = repository.createPendingPreviewFile(id)
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(218 - index * 8, 218 - index * 5, 210))
        paint.color = Color.rgb(120, 131, 124)
        canvas.drawRect(0f, 270f, 600f, 400f, paint)
        paint.color = Color.rgb(167, 27, 36)
        canvas.drawRect(210f + index * 8, 120f, 350f + index * 8, 290f, paint)
        paint.color = Color.rgb(60, 65, 61)
        canvas.drawCircle(420f, 225f, 50f, paint)
        paint.color = Color.rgb(75, 75, 72)
        paint.textSize = 16f
        canvas.drawText("LAYOUT FIXTURE ${index + 1}", 20f, 30f, paint)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
        // History only reads the saved grid. These bytes are a path fixture, never a real DNG.
        val rawFile = repository.createPendingRawFile(id).apply { writeBytes(ByteArray(16)) }
        val points = listOf(RecordedZonePoint(1, 0.4f, 0.35f, 9.0, MeteringSource.RAW),
            RecordedZonePoint(2, 0.7f, 0.65f, 11.0, MeteringSource.RAW))
        return ParameterCaptureDraft(id, ParameterMeterSnapshot(ParameterRecordMode.NORMAL,
            5.0, -7.0 + index / 3.0, 400, 10.0, points), file.absolutePath,
            rawTempPath = rawFile.absolutePath,
            rawGrid = RecordedRawGrid(4, 4, FloatArray(16) { 1f + it / 8f }, 2f),
            capturedAtEpochMs = 1_791_000_000_000L + index * 60_000L,
            filmName = "Kodak PORTRA 400", filmIso = 400, notes = listOf("Window light", "Keep highlights"))
    }

    fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    fun tap(view: View, x: Float, y: Float) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(0, 10, action, x, y, 0).also { view.onTouchEvent(it); it.recycle() }
        }
        settle()
    }

    fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)) }

    fun history(context: Context, state: MeterState, repository: ParameterRecordRepository,
        width: Int, height: Int, page: String): ParameterHistoryView {
        val view = ParameterHistoryView(context, state, repository)
        measure(view, width, height)
        view.open()
        settle()
        val geometry = ParameterHistoryGeometryCalculator.calculate(width, height,
            context.resources.displayMetrics.density, 0f)
        if (page != "history") tap(view, geometry.content.left + 20f, geometry.content.top + 20f)
        if (page.startsWith("history-detail")) {
            tap(view, geometry.content.left + 20f, geometry.content.top + 20f)
            if (page.endsWith("expanded") || page.endsWith("zone")) {
                tap(view, geometry.meteringHandle.centerX(), geometry.meteringHandle.centerY())
                val expanded = ParameterHistoryGeometryCalculator.calculate(width, height,
                    context.resources.displayMetrics.density, 0f, 1f)
                tap(view, expanded.metering.left + expanded.metering.width() *
                    (if (page.endsWith("zone")) 0.75f else 0.25f),
                    expanded.metering.top + 20f * context.resources.displayMetrics.density)
            }
        }
        return view
    }
}
