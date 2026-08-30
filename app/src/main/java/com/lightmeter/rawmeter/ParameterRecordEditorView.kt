package com.lightmeter.rawmeter

import android.animation.ValueAnimator
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
import android.view.VelocityTracker
import android.view.animation.DecelerateInterpolator
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
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
    private var aperturePosition = 0f
    private var shutterPosition = 0f
    private var eiPosition = 0f
    private var selectorStartPosition = 0f
    private var selectorAnimator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null
    private var noteScroll = 0f
    private var noteScrollStart = 0f
    private var touchedNoteIndex: Int? = null

    fun open(value: ParameterCaptureDraft) {
        draft = value
        syncSelectorPositions(value)
        noteScroll = 0f
        invalidate()
    }

    fun currentDraft(): ParameterCaptureDraft? = draft

    fun selectFilm(profile: FilmLatitudeProfile) {
        draft = draft?.let { current ->
            current.copy(
                filmId = profile.id,
                filmName = profile.displayName,
                filmIso = profile.iso,
                snapshot = profile.iso?.let { current.snapshot.copy(ei = it) } ?: current.snapshot,
            )
        }
        draft?.let(::syncSelectorPositions)
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
        drawSelector(canvas, geometry.aperture, localized("光圈", "Aperture"), apertureLabels(), aperturePosition)
        drawSelector(canvas, geometry.shutter, localized("快门", "Shutter"), shutterLabels(), shutterPosition)
        drawSelector(canvas, geometry.ei, "EI", eiValues(value).map(Int::toString), eiPosition)
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
        boldPaint.textSize = 13f * scaledDensity
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
                notePaint.color = foreground
                notePaint.textAlign = Paint.Align.LEFT
                notePaint.textSize = 11f * scaledDensity
                val text = TextUtils.ellipsize(note, notePaint, row.width() - 16f * density, TextUtils.TruncateAt.END)
                centered(canvas, text.toString(), row.left + 8f * density, row.centerY(), notePaint)
            }
        }
        canvas.restore()
    }

    private fun drawSelector(canvas: Canvas, rect: RectF, title: String, labels: List<String>, position: Float) {
        drawPanel(canvas, rect)
        VerticalDetentStripRenderer.draw(
            canvas, rect, title, labels, position, density, scaledDensity,
            background, foreground, muted, red, paint, boldPaint,
        )
    }

    private fun drawSave(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawRoundRect(geometry.save, 7f * density, 7f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = Color.WHITE
        boldPaint.textSize = 13f * scaledDensity
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
                noteScrollStart = noteScroll
                target = targetAt(event.x, event.y)
                if (isSelector(target)) {
                    selectorAnimator?.cancel()
                    selectorStartPosition = selectorPosition(target)
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                if (target == Target.NOTE) touchedNoteIndex = noteIndexAt(event.y, value)
                parent?.requestDisallowInterceptTouchEvent(target in listOf(Target.APERTURE, Target.SHUTTER, Target.EI, Target.NOTE))
                return target != Target.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - touchStartY
                if (abs(dy) > touchSlop) moved = true
                when (target) {
                    Target.APERTURE, Target.SHUTTER, Target.EI -> {
                        velocityTracker?.addMovement(event)
                        updateSelector(target, selectorStartPosition - dy / (26f * density))
                    }
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
                if (isSelector(target)) {
                    if (!cancelled && moved) settleSelector(event) else {
                        velocityTracker?.recycle()
                        velocityTracker = null
                    }
                }
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

    private fun updateSelector(selector: Target, requestedPosition: Float) {
        val current = draft ?: return
        val lastIndex = selectorLastIndex(selector)
        if (lastIndex < 0) return
        val position = requestedPosition.coerceIn(0f, lastIndex.toFloat())
        val index = position.roundToInt().coerceIn(0, lastIndex)
        val before = when (selector) {
            Target.APERTURE -> aperturePosition.roundToInt()
            Target.SHUTTER -> shutterPosition.roundToInt()
            Target.EI -> eiPosition.roundToInt()
            else -> index
        }
        when (selector) {
            Target.APERTURE -> {
                aperturePosition = position
                val coordinate = ExposureMath.apertureTicks(state.apertureStep)[index].coordinate
                draft = current.copy(snapshot = current.snapshot.copy(apertureCoordinate = coordinate))
            }
            Target.SHUTTER -> {
                shutterPosition = position
                val coordinate = ExposureMath.shutterTicks(state.shutterStep)[index].coordinate
                draft = current.copy(snapshot = current.snapshot.copy(shutterCoordinate = coordinate))
            }
            Target.EI -> {
                eiPosition = position
                draft = current.copy(snapshot = current.snapshot.copy(ei = eiValues(current)[index]))
            }
            else -> Unit
        }
        if (index != before) tick()
        postInvalidateOnAnimation()
    }

    private fun settleSelector(event: MotionEvent) {
        val selector = target
        val tracker = velocityTracker
        tracker?.addMovement(event)
        tracker?.computeCurrentVelocity(1000)
        val velocitySteps = -(tracker?.yVelocity ?: 0f) / (26f * density)
        tracker?.recycle()
        velocityTracker = null
        val start = selectorPosition(selector)
        val projected = (start + velocitySteps * 0.11f)
            .coerceIn(0f, selectorLastIndex(selector).toFloat())
        val destination = projected.roundToInt().toFloat()
        selectorAnimator?.cancel()
        selectorAnimator = ValueAnimator.ofFloat(start, destination).apply {
            duration = (170L + kotlin.math.min(220f, abs(destination - start) * 42f)).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { updateSelector(selector, it.animatedValue as Float) }
            start()
        }
    }

    private fun syncSelectorPositions(value: ParameterCaptureDraft) {
        val apertureTicks = ExposureMath.apertureTicks(state.apertureStep)
        aperturePosition = apertureTicks.indices.minByOrNull {
            abs(apertureTicks[it].coordinate - value.snapshot.apertureCoordinate)
        }?.toFloat() ?: 0f
        val shutterTicks = ExposureMath.shutterTicks(state.shutterStep)
        shutterPosition = shutterTicks.indices.minByOrNull {
            abs(shutterTicks[it].coordinate - value.snapshot.shutterCoordinate)
        }?.toFloat() ?: 0f
        eiPosition = eiValues(value).indexOf(value.snapshot.ei).coerceAtLeast(0).toFloat()
    }

    private fun apertureLabels(): List<String> = ExposureMath.apertureTicks(state.apertureStep)
        .map { ExposureMath.formatAperture(it.nominalValue) }

    private fun shutterLabels(): List<String> = ExposureMath.shutterTicks(state.shutterStep)
        .map { ExposureMath.formatShutter(it.nominalValue) }

    private fun selectorPosition(selector: Target): Float = when (selector) {
        Target.APERTURE -> aperturePosition
        Target.SHUTTER -> shutterPosition
        Target.EI -> eiPosition
        else -> 0f
    }

    private fun selectorLastIndex(selector: Target): Int = when (selector) {
        Target.APERTURE -> ExposureMath.apertureTicks(state.apertureStep).lastIndex
        Target.SHUTTER -> ExposureMath.shutterTicks(state.shutterStep).lastIndex
        Target.EI -> draft?.let(::eiValues)?.lastIndex ?: -1
        else -> -1
    }

    private fun isSelector(value: Target): Boolean =
        value == Target.APERTURE || value == Target.SHUTTER || value == Target.EI

    private fun eiValues(value: ParameterCaptureDraft): List<Int> =
        (state.isoValues.asList() + value.snapshot.ei).distinct().sorted()

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

    private fun tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    override fun onDetachedFromWindow() {
        selectorAnimator?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

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
