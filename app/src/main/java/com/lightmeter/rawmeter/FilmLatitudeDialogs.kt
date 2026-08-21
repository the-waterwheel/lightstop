package com.lightmeter.rawmeter

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal class FilmLatitudeDialogs(
    private val context: Context,
    private val state: MeterState,
) {
    fun showCustomFilm(onCreated: (String, Double, Double) -> Unit) {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (20f * density).toInt()
            val vertical = (8f * density).toInt()
            setPadding(horizontal, vertical, horizontal, 0)
        }
        val name = field(localized("胶片名称", "Film name"), InputType.TYPE_CLASS_TEXT)
        val shadow = field(
            localized("阴影端 EV（-20 到 0）", "Shadow EV (-20 to 0)"),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
        )
        val highlight = field(
            localized("高光端 EV（0 到 +20）", "Highlight EV (0 to +20)"),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
        )
        shadow.setText("-3")
        highlight.setText("5")
        container.addView(name)
        container.addView(shadow)
        container.addView(highlight)
        val error = TextView(context).apply {
            setTextColor(android.graphics.Color.rgb(184, 38, 44))
            textSize = 13f
        }
        container.addView(
            error,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val dialog = AlertDialog.Builder(context)
            .setTitle(localized("手动录入胶片", "Add film manually"))
            .setView(container)
            .setNegativeButton(localized("取消", "Cancel"), null)
            .setPositiveButton(localized("保存", "Save"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text?.toString()?.trim().orEmpty()
                val shadowEv = shadow.text?.toString()?.toDoubleOrNull()
                val highlightEv = highlight.text?.toString()?.toDoubleOrNull()
                val valid = title.isNotBlank() &&
                    shadowEv != null && shadowEv in FilmLatitudeRange.MIN_CUSTOM_EV..0.0 &&
                    highlightEv != null && highlightEv in 0.0..FilmLatitudeRange.MAX_CUSTOM_EV
                if (!valid) {
                    error.text = localized(
                        "请输入名称；阴影端为 -20～0，高光端为 0～+20。",
                        "Enter a name, shadow from -20 to 0, and highlight from 0 to +20.",
                    )
                    return@setOnClickListener
                }
                onCreated(title, shadowEv!!, highlightEv!!)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun field(hint: String, inputType: Int): EditText = EditText(context).apply {
        this.hint = hint
        this.inputType = inputType
        isSingleLine = true
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese
}
