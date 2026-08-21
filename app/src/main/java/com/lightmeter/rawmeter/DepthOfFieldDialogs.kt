package com.lightmeter.rawmeter

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.roundToInt

/** Selection and bounded custom-value dialogs kept independent from calculator drawing. */
internal class DepthOfFieldDialogs(
    private val context: Context,
    private val state: MeterState,
    private val onFrameSelected: (FrameFormat) -> Unit,
    private val onCircleOfConfusionSelected: (Double) -> Unit,
) {
    private val density = context.resources.displayMetrics.density

    fun showFrame(current: FrameFormat) {
        val labels = FrameFormat.ALL.map { it.displayLabel(state.menuLanguage) } +
            localized("自定义画幅…", "Custom format…")
        AlertDialog.Builder(context)
            .setTitle(localized("选择画幅（毫米）", "Select format (millimetres)"))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == FrameFormat.ALL.size) {
                    showCustomFrame(current)
                } else {
                    onFrameSelected(FrameFormat.ALL[which])
                }
            }
            .setNegativeButton(localized("取消", "Cancel"), null)
            .show()
    }

    fun showCircleOfConfusion(format: FrameFormat, currentValue: Double) {
        val recommended = DepthOfFieldMath.recommendedCircleOfConfusionMm(format)
        val values = (listOf(recommended) + DepthOfFieldMath.commonCircleOfConfusionMm.toList())
            .distinctBy { (it * 10_000).roundToInt() }
        val labels = values.mapIndexed { index, value ->
            val suffix = if (index == 0) localized("（画幅建议）", " (recommended)") else ""
            "${"%.3f".format(value)} mm$suffix"
        } + localized("自定义…", "Custom…")
        AlertDialog.Builder(context)
            .setTitle(localized("最大弥散圆 c", "Circle of confusion c"))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == values.size) {
                    showCustomCircle(currentValue)
                } else {
                    onCircleOfConfusionSelected(values[which])
                }
            }
            .setNegativeButton(localized("取消", "Cancel"), null)
            .show()
    }

    private fun showCustomFrame(current: FrameFormat) {
        val widthEditor = decimalEditor(current.widthMm)
        val heightEditor = decimalEditor(current.heightMm)
        val container = editorContainer(widthEditor, heightEditor)
        widthEditor.hint = localized("宽度 mm", "Width mm")
        heightEditor.hint = localized("高度 mm", "Height mm")
        AlertDialog.Builder(context)
            .setTitle(localized("自定义画幅", "Custom format"))
            .setView(container)
            .setPositiveButton(localized("确定", "OK")) { _, _ ->
                val custom = DepthOfFieldMath.customFormat(
                    widthEditor.text.toString().toDoubleOrNull() ?: Double.NaN,
                    heightEditor.text.toString().toDoubleOrNull() ?: Double.NaN,
                )
                if (custom == null) {
                    showRangeError(
                        localized(
                            "画幅长宽必须在 1–300 mm 之间",
                            "Frame edges must be between 1 and 300 mm",
                        ),
                    )
                } else {
                    onFrameSelected(custom)
                }
            }
            .setNegativeButton(localized("取消", "Cancel"), null)
            .show()
    }

    private fun showCustomCircle(currentValue: Double) {
        val editor = decimalEditor(currentValue).apply {
            hint = localized("c（毫米）", "c (millimetres)")
        }
        AlertDialog.Builder(context)
            .setTitle(localized("自定义弥散圆", "Custom circle of confusion"))
            .setView(editorContainer(editor))
            .setPositiveButton(localized("确定", "OK")) { _, _ ->
                val value = editor.text.toString().toDoubleOrNull()
                if (value == null || value !in DepthOfFieldMath.MIN_COC_MM..DepthOfFieldMath.MAX_COC_MM) {
                    showRangeError(
                        localized(
                            "c 必须在 0.001–1.000 mm 之间",
                            "c must be between 0.001 and 1.000 mm",
                        ),
                    )
                } else {
                    onCircleOfConfusionSelected(value)
                }
            }
            .setNegativeButton(localized("取消", "Cancel"), null)
            .show()
    }

    private fun editorContainer(vararg editors: EditText): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (22f * density).roundToInt()
            setPadding(horizontal, (8f * density).roundToInt(), horizontal, 0)
            editors.forEach(::addView)
        }

    private fun decimalEditor(value: Double): EditText = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(
            if (value % 1.0 == 0.0) {
                value.toInt().toString()
            } else {
                value.toString().trimEnd('0').trimEnd('.')
            },
        )
        selectAll()
    }

    private fun showRangeError(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese
}
