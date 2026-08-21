package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class ParameterRecordEditorView(
    context: Context,
    private val state: MeterState,
) : View(context) {

    interface Listener {
        fun onCancelRequested(draft: ParameterCaptureDraft)
        fun onFilmSelectionRequested()
        fun onSaveRequested(draft: ParameterCaptureDraft)
    }

    var listener: Listener? = null

    private enum class Target { CLOSE, FILM, ADD_NOTE, NOTE, APERTURE, SHUTTER, EI, SAVE, NONE }

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
    private val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.BOLD) }
    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(224, 224, 220) else Color.rgb(20, 20, 20)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(105, 105, 101) else Color.rgb(178, 178, 174)
    private val panel: Int get() = if (state.isDarkMode) Color.rgb(45, 45, 43) else Color.rgb(235, 235, 232)
    private val red = Color.rgb(201, 39, 46)
    private var geometry = ParameterRecordEditorGeometry.EMPTY
    private var draft: ParameterCaptureDraft? = null
    private var target = Target.NONE
    private var touchStartY = 0f
    private var moved = false
    private var startAperture = 0.0
    private var startShutter = 0.0
    private var startEiIndex = 0
    private var noteScroll = 0f
    private var noteScrollStart = 0f
    private var touchedNoteIndex: Int? = null

    fun open(value: ParameterCaptureDraft) {
        draft = value
        noteScroll = 0f
        invalidate()
    }

    fun currentDraft(): ParameterCaptureDraft? = draft

    fun selectFilm(profile: FilmLatitudeProfile) {
        draft = draft?.copy(filmId = profile.id, filmName = profile.displayName)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry = ParameterRecordEditorGeometryCalculator.calculate(w, h, density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        val value = draft ?: return
        drawClose(canvas)
        drawFilm(canvas, value)
        drawNotes(canvas, value)
        drawSelector(canvas, geometry.aperture, "f", ExposureMath.formatAperture(
            ExposureMath.apertureValueForCoordinate(value.snapshot.apertureCoordinate, state.apertureStep),
        ))
        drawSelector(canvas, geometry.shutter, "s", ExposureMath.formatShutter(
            ExposureMath.shutterValueForCoordinate(value.snapshot.shutterCoordinate, state.shutterStep),
        ))
        drawSelector(canvas, geometry.ei, "EI", value.snapshot.ei.toString())
        drawSave(canvas)
    }

    private fun drawClose(canvas: Canvas) {
        val x = geometry.close.centerX()
        val y = geometry.close.centerY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.color = foreground
        canvas.drawLine(x - 7f * density, y - 7f * density, x + 7f * density, y + 7f * density, paint)
        canvas.drawLine(x + 7f * density, y - 7f * density, x - 7f * density, y + 7f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = 12f * scaledDensity
        centered(canvas, localized("记录参数", "Record parameters"), width / 2f, y, boldPaint)
    }

    private fun drawFilm(canvas: Canvas, value: ParameterCaptureDraft) {
        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawRoundRect(geometry.film, 6f * density, 6f * density, paint)
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.color = Color.WHITE
        boldPaint.textSize = 11f * scaledDensity
        val label = value.filmName ?: localized("选择胶片类型", "Select film")
        val fitted = TextUtils.ellipsize(label, boldPaint, geometry.film.width() - geometry.filmEdit.width() - 20f * density, TextUtils.TruncateAt.END)
        centered(canvas, fitted.toString(), geometry.film.left + 10f * density, geometry.film.centerY(), boldPaint)
        boldPaint.textAlign = Paint.Align.CENTER
        centered(canvas, localized("修改", "Edit"), geometry.filmEdit.centerX(), geometry.filmEdit.centerY(), boldPaint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = Color.WHITE
        canvas.drawLine(geometry.filmEdit.left, geometry.filmEdit.top + 7f * density, geometry.filmEdit.left, geometry.filmEdit.bottom - 7f * density, paint)
    }

    private fun drawNotes(canvas: Canvas, value: ParameterCaptureDraft) {
        drawPanel(canvas, geometry.addNote)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 22f * scaledDensity
        boldPaint.color = foreground
        centered(canvas, "+", geometry.addNote.centerX(), geometry.addNote.centerY(), boldPaint)
        canvas.save()
        canvas.clipRect(geometry.notes)
        val rowHeight = 38f * density
        val maxScroll = (value.notes.size * rowHeight - geometry.notes.height()).coerceAtLeast(0f)
        noteScroll = noteScroll.coerceIn(0f, maxScroll)
        if (value.notes.isEmpty()) {
            paint.color = muted
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 10f * scaledDensity
            centered(canvas, localized("可添加最多 10 条备注", "Add up to 10 notes"), geometry.notes.left + 8f * density, geometry.notes.centerY(), paint)
        } else {
            value.notes.forEachIndexed { index, note ->
                val row = RectF(
                    geometry.notes.left,
                    geometry.notes.top + index * rowHeight - noteScroll,
                    geometry.notes.right,
                    geometry.notes.top + (index + 1) * rowHeight - noteScroll - 3f * density,
                )
                if (!RectF.intersects(row, geometry.notes)) return@forEachIndexed
                drawPanel(canvas, row)
                paint.color = foreground
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 9f * scaledDensity
                val text = TextUtils.ellipsize(note, TextPaint(paint), row.width() - 16f * density, TextUtils.TruncateAt.END)
                centered(canvas, text.toString(), row.left + 8f * density, row.centerY(), paint)
            }
        }
        canvas.restore()
    }

    private fun drawSelector(canvas: Canvas, rect: RectF, title: String, value: String) {
        drawPanel(canvas, rect)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = red
        boldPaint.textSize = 10f * scaledDensity
        canvas.drawText(title, rect.centerX(), rect.top + 17f * density, boldPaint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = muted
        canvas.drawLine(rect.centerX(), rect.top + 25f * density, rect.centerX(), rect.bottom - 25f * density, paint)
        for (i in -2..2) {
            val y = rect.centerY() + i * 15f * density
            val length = if (i == 0) 14f * density else 7f * density
            canvas.drawLine(rect.centerX() - length, y, rect.centerX() + length, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRoundRect(
            RectF(rect.left + 5f * density, rect.centerY() - 17f * density, rect.right - 5f * density, rect.centerY() + 17f * density),
            4f * density,
            4f * density,
            paint,
        )
        boldPaint.color = foreground
        boldPaint.textSize = 11f * scaledDensity
        centered(canvas, value, rect.centerX(), rect.centerY(), boldPaint)
    }

    private fun drawSave(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawRoundRect(geometry.save, 7f * density, 7f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = Color.WHITE
        boldPaint.textSize = 11f * scaledDensity
        centered(canvas, localized("保存", "Save"), geometry.save.centerX(), geometry.save.centerY(), boldPaint)
    }

    private fun drawPanel(canvas: Canvas, rect: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = muted
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val value = draft ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.y
                moved = false
                startAperture = value.snapshot.apertureCoordinate
                startShutter = value.snapshot.shutterCoordinate
                startEiIndex = state.isoValues.indexOf(value.snapshot.ei).coerceAtLeast(0)
                noteScrollStart = noteScroll
                target = targetAt(event.x, event.y)
                if (target == Target.NOTE) touchedNoteIndex = noteIndexAt(event.y, value)
                parent?.requestDisallowInterceptTouchEvent(target in listOf(Target.APERTURE, Target.SHUTTER, Target.EI, Target.NOTE))
                return target != Target.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - touchStartY
                if (abs(dy) > touchSlop) moved = true
                when (target) {
                    Target.APERTURE -> updateAperture(value, dy)
                    Target.SHUTTER -> updateShutter(value, dy)
                    Target.EI -> updateEi(value, dy)
                    Target.NOTE -> {
                        val maxScroll = (value.notes.size * 38f * density - geometry.notes.height()).coerceAtLeast(0f)
                        noteScroll = (noteScrollStart - dy).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (!cancelled && !moved) {
                    performClick()
                    handleTap(value)
                }
                target = Target.NONE
                touchedNoteIndex = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun targetAt(x: Float, y: Float): Target = when {
        geometry.close.contains(x, y) -> Target.CLOSE
        geometry.film.contains(x, y) -> Target.FILM
        geometry.addNote.contains(x, y) -> Target.ADD_NOTE
        geometry.notes.contains(x, y) -> Target.NOTE
        geometry.aperture.contains(x, y) -> Target.APERTURE
        geometry.shutter.contains(x, y) -> Target.SHUTTER
        geometry.ei.contains(x, y) -> Target.EI
        geometry.save.contains(x, y) -> Target.SAVE
        else -> Target.NONE
    }

    private fun handleTap(value: ParameterCaptureDraft) {
        when (target) {
            Target.CLOSE -> listener?.onCancelRequested(value)
            Target.FILM -> listener?.onFilmSelectionRequested()
            Target.ADD_NOTE -> if (value.notes.size < MAX_NOTES) showNoteDialog(null)
            Target.NOTE -> touchedNoteIndex?.let { showNoteDialog(it) }
            Target.SAVE -> listener?.onSaveRequested(value)
            else -> Unit
        }
    }

    private fun updateAperture(value: ParameterCaptureDraft, dy: Float) {
        val step = exposureStepSize(state.apertureStep)
        val coordinate = ExposureMath.nearestApertureStop(startAperture - dy / (34f * density) * step, state.apertureStep)
        draft = value.copy(snapshot = value.snapshot.copy(apertureCoordinate = coordinate))
        tick()
        invalidate()
    }

    private fun updateShutter(value: ParameterCaptureDraft, dy: Float) {
        val step = exposureStepSize(state.shutterStep)
        val coordinate = ExposureMath.nearestShutterLogSeconds(startShutter - dy / (34f * density) * step, state.shutterStep)
        draft = value.copy(snapshot = value.snapshot.copy(shutterCoordinate = coordinate))
        tick()
        invalidate()
    }

    private fun updateEi(value: ParameterCaptureDraft, dy: Float) {
        val delta = (dy / (28f * density)).roundToInt()
        val index = (startEiIndex - delta).coerceIn(0, state.isoValues.lastIndex)
        draft = value.copy(snapshot = value.snapshot.copy(ei = state.isoValues[index]))
        tick()
        invalidate()
    }

    private fun noteIndexAt(y: Float, value: ParameterCaptureDraft): Int? {
        val index = ((y - geometry.notes.top + noteScroll) / (38f * density)).toInt()
        return index.takeIf { it in value.notes.indices }
    }

    private fun showNoteDialog(index: Int?) {
        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 5
            setText(index?.let { draft?.notes?.getOrNull(it) }.orEmpty())
        }
        AlertDialog.Builder(context)
            .setTitle(if (index == null) localized("新增备注", "Add note") else localized("修改备注", "Edit note"))
            .setView(field)
            .setNegativeButton(localized("取消", "Cancel"), null)
            .setPositiveButton(localized("保存", "Save")) { _, _ ->
                val text = field.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) return@setPositiveButton
                val current = draft ?: return@setPositiveButton
                val notes = current.notes.toMutableList()
                if (index == null) notes += text else if (index in notes.indices) notes[index] = text
                draft = current.copy(notes = notes.take(MAX_NOTES))
                invalidate()
            }
            .show()
    }

    private fun exposureStepSize(step: ExposureStep): Double = when (step) {
        ExposureStep.FULL -> 1.0
        ExposureStep.HALF -> 0.5
        ExposureStep.THIRD -> 1.0 / 3.0
    }

    private fun tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    private fun centered(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object { const val MAX_NOTES = 10 }
}
