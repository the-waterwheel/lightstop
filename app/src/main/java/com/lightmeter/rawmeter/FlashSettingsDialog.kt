package com.lightmeter.rawmeter

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

internal object FlashSettingsDialog {
    private val guideNumberPresets = listOf(20, 24, 28, 32, 36, 40, 42, 50, 56, 58, 60, 80, 100, 120, 160, 200)

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
            setPadding((22 * density).toInt(), (8 * density).toInt(), (22 * density).toInt(), 0)
        }

        fun label(chinese: String, englishText: String) = TextView(context).apply {
            text = if (english) englishText else chinese
            textSize = 13f
            setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
        }

        fun spinner(values: List<String>, selected: Int): Spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(selected.coerceIn(0, values.lastIndex))
        }

        content.addView(label("闪光指数标准（米）", "Guide number standard (metres)"))
        val gnLabels = guideNumberPresets.map { "GN$it" } + if (english) "Custom" else "自定义"
        val presetIndex = guideNumberPresets.indexOfFirst { it.toDouble() == initial.guideNumber }
        val gnSpinner = spinner(gnLabels, if (presetIndex >= 0) presetIndex else gnLabels.lastIndex)
        content.addView(gnSpinner)
        val gnInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatNumber(initial.guideNumber))
            hint = "100"
            setSelection(text.length)
        }
        content.addView(gnInput)
        gnSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                guideNumberPresets.getOrNull(position)?.let { gnInput.setText(it.toString()) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        content.addView(label("ISO（应用后同步普通测光转盘）", "ISO (syncs to the Normal meter dial)"))
        val isoLabels = state.isoValues.map(Int::toString)
        val isoSpinner = spinner(isoLabels, state.isoValues.indexOf(initial.iso).coerceAtLeast(0))
        content.addView(isoSpinner)

        content.addView(label("闪光灯功率", "Flash power"))
        val powerLabels = FlashPowerScale.denominators.map(FlashPowerScale::label)
        val powerSpinner = spinner(
            powerLabels,
            FlashPowerScale.denominators.indexOf(initial.powerDenominator).coerceAtLeast(0),
        )
        content.addView(powerSpinner)

        content.addView(label("闪光损失（档）", "Flash loss (stops)"))
        val lossInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(String.format(Locale.US, "%.2f", initial.lossStops))
            hint = "0.00"
            setSelection(text.length)
        }
        content.addView(lossInput)

        AlertDialog.Builder(context)
            .setTitle(if (english) "Flash settings" else "闪光灯设置")
            .setView(content)
            .setNegativeButton(if (english) "Cancel" else "取消", null)
            .setPositiveButton(if (english) "Save" else "保存") { _, _ ->
                val guideNumber = gnInput.text.toString().toDoubleOrNull() ?: initial.guideNumber
                val lossStops = lossInput.text.toString().toDoubleOrNull() ?: initial.lossStops
                val iso = state.isoValues.getOrElse(isoSpinner.selectedItemPosition) { state.iso }
                val power = FlashPowerScale.denominators.getOrElse(powerSpinner.selectedItemPosition) { 1 }
                onConfirmed(
                    initial.copy(
                        guideNumber = guideNumber,
                        iso = iso,
                        powerDenominator = power,
                        lossStops = lossStops,
                    ).normalized(),
                )
            }
            .show()
    }

    private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}
