package com.lightmeter.rawmeter

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

internal object FlashSettingsDialog {
    fun show(
        context: Context,
        state: MeterState,
        initial: FlashConfiguration,
        onConfirmed: (FlashConfiguration) -> Unit,
    ) {
        val english = state.menuLanguage == MenuLanguage.ENGLISH
        val density = context.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f, density), dp(10f, density), dp(24f, density), dp(16f, density))
        }

        fun label(chinese: String, englishText: String) = TextView(context).apply {
            text = if (english) englishText else chinese
            textSize = 17f
            setPadding(0, dp(14f, density), 0, dp(6f, density))
        }

        fun spinner(values: List<String>, selected: Int): Spinner = Spinner(context).apply {
            adapter = largeSpinnerAdapter(context, values, density)
            setSelection(selected.coerceIn(0, values.lastIndex))
            minimumHeight = dp(54f, density)
        }

        content.addView(label("闪光指数标定 ISO", "Guide-number reference ISO"))
        val referenceLabels = state.isoValues.map { "GN$it" }
        val referenceIndex = nearestIsoIndex(state, initial.guideNumberReferenceIso)
        val referenceSpinner = spinner(referenceLabels, referenceIndex)
        content.addView(referenceSpinner)

        content.addView(label("闪光灯功率", "Flash power"))
        val powerLabels = FlashPowerScale.denominators.map(FlashPowerScale::label)
        val powerSpinner = spinner(
            powerLabels,
            FlashPowerScale.denominators.indexOf(initial.powerDenominator).coerceAtLeast(0),
        )
        content.addView(powerSpinner)

        content.addView(label("闪光损失（档）", "Flash loss (stops)"))
        val lossInput = numericInput(
            context = context,
            state = state,
            density = density,
            textSizeSp = 18f,
            signed = true,
        ).apply {
            setText(String.format(Locale.US, "%.2f", initial.lossStops))
            hint = "0.00"
            setSelection(text.length)
        }
        content.addView(lossInput)

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (english) "Flash settings" else "闪光灯设置")
            .setView(content)
            .setNegativeButton(if (english) "Cancel" else "取消", null)
            .setPositiveButton(if (english) "Save" else "保存") { _, _ ->
                val lossStops = lossInput.text.toString().toDoubleOrNull() ?: initial.lossStops
                val referenceIso = state.isoValues.getOrElse(
                    referenceSpinner.selectedItemPosition,
                ) { initial.guideNumberReferenceIso }
                val power = FlashPowerScale.denominators.getOrElse(powerSpinner.selectedItemPosition) { 1 }
                onConfirmed(
                    initial.copy(
                        guideNumberReferenceIso = referenceIso,
                        powerDenominator = power,
                        lossStops = lossStops,
                    ).normalized(),
                )
            }
            .show()
        styleDialog(dialog, density)
    }

    fun showGuideNumberInput(
        context: Context,
        state: MeterState,
        initial: FlashConfiguration,
        onConfirmed: (Double) -> Unit,
    ) {
        val english = state.menuLanguage == MenuLanguage.ENGLISH
        val density = context.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f, density), dp(10f, density), dp(24f, density), dp(16f, density))
        }
        content.addView(TextView(context).apply {
            text = if (english) {
                "Enter the numeric value specified for GN${initial.guideNumberReferenceIso}."
            } else {
                "输入 GN${initial.guideNumberReferenceIso} 标准下的闪光指数数值。"
            }
            textSize = 17f
            setLineSpacing(dp(3f, density).toFloat(), 1f)
            setPadding(0, 0, 0, dp(14f, density))
        })
        val input = numericInput(
            context = context,
            state = state,
            density = density,
            textSizeSp = 22f,
        ).apply {
            setText(formatNumber(initial.guideNumber))
            hint = "100"
            setSelection(text.length)
            setSelectAllOnFocus(true)
        }
        content.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(60f, density),
            ),
        )
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (english) "Guide number" else "闪光指数")
            .setView(content)
            .setNegativeButton(if (english) "Cancel" else "取消", null)
            .setPositiveButton(if (english) "Save" else "保存") { _, _ ->
                val value = input.text.toString().toDoubleOrNull() ?: initial.guideNumber
                onConfirmed(value.coerceIn(1.0, 1000.0))
            }
            .show()
        styleDialog(dialog, density)
    }

    fun showMeteringIsoInput(
        context: Context,
        state: MeterState,
        initial: FlashConfiguration,
        onConfirmed: (Int) -> Unit,
    ) {
        val english = state.menuLanguage == MenuLanguage.ENGLISH
        val density = context.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f, density), dp(10f, density), dp(24f, density), dp(16f, density))
        }
        content.addView(TextView(context).apply {
            text = if (english) {
                "Used only for flash calculation; the Normal meter ISO dial is not changed."
            } else {
                "仅用于闪光计算，不会改变普通测光转盘的 ISO。"
            }
            textSize = 17f
            setLineSpacing(dp(3f, density).toFloat(), 1f)
            setPadding(0, dp(8f, density), 0, dp(10f, density))
        })
        val values = state.isoValues.map(Int::toString)
        val isoSpinner = Spinner(context).apply {
            adapter = largeSpinnerAdapter(context, values, density)
            setSelection(nearestIsoIndex(state, initial.iso))
            minimumHeight = dp(54f, density)
        }
        content.addView(isoSpinner)
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (english) "Flash metering ISO" else "闪光测光 ISO")
            .setView(content)
            .setNegativeButton(if (english) "Cancel" else "取消", null)
            .setPositiveButton(if (english) "Save" else "保存") { _, _ ->
                onConfirmed(state.isoValues.getOrElse(isoSpinner.selectedItemPosition) { state.iso })
            }
            .show()
        styleDialog(dialog, density)
    }

    private fun numericInput(
        context: Context,
        state: MeterState,
        density: Float,
        textSizeSp: Float,
        signed: Boolean = false,
    ): EditText = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        isSingleLine = true
        gravity = Gravity.CENTER_VERTICAL
        textSize = textSizeSp
        minHeight = dp(56f, density)
        setPadding(dp(16f, density), dp(8f, density), dp(16f, density), dp(8f, density))
        background = GradientDrawable().apply {
            cornerRadius = 7f * density
            setColor(Color.TRANSPARENT)
            setStroke(
                dp(1f, density).coerceAtLeast(1),
                if (state.isDarkMode) Color.rgb(190, 190, 186) else Color.rgb(80, 80, 78),
            )
        }
    }

    private fun largeSpinnerAdapter(
        context: Context,
        values: List<String>,
        density: Float,
    ): ArrayAdapter<String> = object : ArrayAdapter<String>(
        context,
        android.R.layout.simple_spinner_item,
        values,
    ) {
        init {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            styleSpinnerView(super.getView(position, convertView, parent), density, dropdown = false)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            styleSpinnerView(super.getDropDownView(position, convertView, parent), density, dropdown = true)
    }

    private fun styleSpinnerView(view: View, density: Float, dropdown: Boolean): View = view.apply {
        (this as? TextView)?.apply {
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(if (dropdown) 54f else 50f, density)
            setPadding(
                dp(if (dropdown) 16f else 8f, density),
                dp(8f, density),
                dp(12f, density),
                dp(8f, density),
            )
        }
    }

    private fun styleDialog(dialog: AlertDialog, density: Float) {
        val alertTitleId = dialog.context.resources.getIdentifier("alertTitle", "id", "android")
        if (alertTitleId != 0) dialog.findViewById<TextView>(alertTitleId)?.textSize = 22f
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            textSize = 17f
            setLineSpacing(dp(3f, density).toFloat(), 1f)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            textSize = 17f
            minHeight = dp(52f, density)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            textSize = 17f
            minHeight = dp(52f, density)
        }
    }

    private fun dp(value: Float, density: Float): Int = (value * density).roundToInt()

    private fun nearestIsoIndex(state: MeterState, value: Int): Int =
        state.isoValues.indices.minByOrNull { kotlin.math.abs(state.isoValues[it] - value) } ?: 0

    private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}
