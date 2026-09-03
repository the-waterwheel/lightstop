package com.lightmeter.rawmeter

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.log2

/** Editor for user-owned reciprocity overrides. Built-in manufacturer data remains immutable. */
internal class FilmReciprocityDialogs(
    private val context: Context,
    private val state: MeterState,
) {
    fun showForFilm(
        filmName: String,
        existing: ReciprocityMethod?,
        canReset: Boolean,
        hasOriginal: Boolean,
        onSaved: (ReciprocityMethod) -> Unit,
        onReset: () -> Unit,
    ) {
        showEditor(
            filmName = filmName,
            existing = existing,
            newFilm = false,
            canReset = canReset,
            hasOriginal = hasOriginal,
            onSaved = { _, method -> onSaved(method) },
            onReset = onReset,
        )
    }

    fun showNewFilm(onCreated: (String, ReciprocityMethod) -> Unit) {
        showEditor(
            filmName = null,
            existing = null,
            newFilm = true,
            canReset = false,
            hasOriginal = false,
            onSaved = { name, method -> onCreated(name.orEmpty(), method) },
            onReset = {},
        )
    }

    private fun showEditor(
        filmName: String?,
        existing: ReciprocityMethod?,
        newFilm: Boolean,
        canReset: Boolean,
        hasOriginal: Boolean,
        onSaved: (String?, ReciprocityMethod) -> Unit,
        onReset: () -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (20f * density).toInt()
            val vertical = (8f * density).toInt()
            setPadding(horizontal, vertical, horizontal, (10f * density).toInt())
        }
        val name = field(localized("胶片名称", "Film name"), TEXT_INPUT).apply {
            visibility = if (newFilm) View.VISIBLE else View.GONE
        }
        if (newFilm) container.addView(name)

        container.addView(label(localized("计算方法", "Calculation method")))
        val choices = listOf(
            localized("离散点曲线（按档位拟合）", "Point curve (fit in stops)"),
            localized("幂函数 Tc = Tm^P", "Power Tc = Tm^P"),
            localized("固定 EV 补偿", "Fixed EV compensation"),
            localized("有限范围内无需补偿", "No correction in a finite range"),
        )
        val methodSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, choices)
        }
        container.addView(methodSpinner)

        val noCompensationLabel = label(localized("无需补偿上限（秒）", "No-correction limit (seconds)"))
        val noCompensation = field("1", NUMBER_INPUT).apply { setText("1") }
        val parameterLabel = label("")
        val parameter = field("", NUMBER_INPUT)
        val maximumLabel = label(localized("精确数据上限（秒，可留空）", "Exact-data limit (seconds, optional)"))
        val maximum = field("", NUMBER_INPUT)
        val pointsLabel = label(
            localized(
                "离散点：每行“测光秒 = 校正秒”",
                "Points: one ‘metered seconds = corrected seconds’ pair per line",
            ),
        )
        val points = EditText(context).apply {
            hint = localized("例如：\n2 = 3\n10 = 25\n30 = 150", "Example:\n2 = 3\n10 = 25\n30 = 150")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 9
            gravity = android.view.Gravity.TOP
        }
        val help = TextView(context).apply { textSize = 12f }
        val error = TextView(context).apply {
            setTextColor(android.graphics.Color.rgb(184, 38, 44))
            textSize = 13f
        }
        listOf(
            noCompensationLabel,
            noCompensation,
            parameterLabel,
            parameter,
            maximumLabel,
            maximum,
            pointsLabel,
            points,
            help,
            error,
        ).forEach(container::addView)

        val initialKind = EditorKind.from(existing?.type)
        methodSpinner.setSelection(initialKind.ordinal)
        noCompensation.setText(formatNumber(existing?.noCompensationSeconds ?: 1.0))
        existing?.parameter?.let { parameter.setText(formatNumber(it)) }
        existing?.officialMaximumSeconds?.let { maximum.setText(formatNumber(it)) }
        if (existing?.points?.isNotEmpty() == true) {
            points.setText(
                existing.points.joinToString("\n") { point ->
                    "${formatNumber(point.meteredSeconds)} = ${formatNumber(point.correctedSeconds)}"
                },
            )
        }

        fun updateFields(kind: EditorKind) {
            val table = kind == EditorKind.TABLE
            val parameterized = kind == EditorKind.POWER || kind == EditorKind.FIXED_EV
            val bounded = kind == EditorKind.BOUNDED_UNCHANGED
            parameterLabel.visibility = if (parameterized) View.VISIBLE else View.GONE
            parameter.visibility = if (parameterized) View.VISIBLE else View.GONE
            maximumLabel.visibility = if (bounded) View.GONE else View.VISIBLE
            maximum.visibility = if (bounded) View.GONE else View.VISIBLE
            pointsLabel.visibility = if (table) View.VISIBLE else View.GONE
            points.visibility = if (table) View.VISIBLE else View.GONE
            noCompensationLabel.text = if (bounded) {
                localized("最大无需补偿时间（秒）", "Maximum no-correction time (seconds)")
            } else {
                localized("无需补偿上限（秒）", "No-correction limit (seconds)")
            }
            parameterLabel.text = when (kind) {
                EditorKind.POWER -> localized("指数 P（幂函数以 1 秒为连续基准）", "Exponent P (continuous at 1 second)")
                EditorKind.FIXED_EV -> localized("增加曝光档数 EV", "Additional exposure in EV")
                else -> ""
            }
            help.text = when (kind) {
                EditorKind.TABLE -> localized(
                    "内部按 log2(测光秒) 与 log2(校正秒/测光秒) 的补偿档位做保形三次拟合；曲线通过所有节点，补偿不会回落。",
                    "A shape-preserving cubic fits log2(metered seconds) against log2(corrected/metered) compensation stops. It passes every point without a compensation dip.",
                )
                EditorKind.POWER -> localized(
                    "幂函数必须以 1 秒作为无需补偿边界，避免阈值处发生跳变。",
                    "Power curves use a 1-second no-correction boundary to avoid a value jump.",
                )
                EditorKind.FIXED_EV -> localized(
                    "在阈值后增加固定 EV；适合厂家明确给出固定补偿的资料。",
                    "Adds a fixed EV after the limit; use when the manufacturer specifies a fixed correction.",
                )
                EditorKind.BOUNDED_UNCHANGED -> localized(
                    "只表示厂家确认的无需补偿范围，超过上限不外推。",
                    "Represents only a confirmed no-correction range and does not extrapolate beyond it.",
                )
            }
        }
        methodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFields(EditorKind.entries[position])
                error.text = ""
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateFields(initialKind)

        val scroll = ScrollView(context).apply { addView(container) }
        val builder = AlertDialog.Builder(context)
            .setTitle(
                if (newFilm) localized("新增胶片倒易率", "Add film reciprocity")
                else localized("修改倒易率：$filmName", "Edit reciprocity: $filmName"),
            )
            .setView(scroll)
            .setNegativeButton(localized("取消", "Cancel"), null)
            .setPositiveButton(localized("保存", "Save"), null)
        if (canReset) {
            builder.setNeutralButton(
                if (hasOriginal) localized("重置原设定", "Reset original")
                else localized("清除数据", "Clear data"),
                null,
            )
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val kind = EditorKind.entries[methodSpinner.selectedItemPosition]
                val title = name.text?.toString()?.trim().orEmpty()
                val threshold = noCompensation.text?.toString()?.toDoubleOrNull()
                val parsed = buildMethod(
                    kind = kind,
                    threshold = threshold,
                    parameterText = parameter.text?.toString().orEmpty(),
                    maximumText = maximum.text?.toString().orEmpty(),
                    pointsText = points.text?.toString().orEmpty(),
                )
                val problem = when {
                    newFilm && title.isBlank() -> localized("请输入胶片名称。", "Enter a film name.")
                    parsed.error != null -> parsed.error
                    else -> null
                }
                if (problem != null) {
                    error.text = problem
                    return@setOnClickListener
                }
                onSaved(title.takeIf { newFilm }, parsed.method!!)
                dialog.dismiss()
            }
            if (canReset) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(context)
                        .setMessage(
                            if (hasOriginal) {
                                localized("恢复这款胶片的原始倒易率设定？", "Restore the original reciprocity data for this film?")
                            } else {
                                localized("清除这款自定义胶片的倒易率数据？", "Clear reciprocity data for this custom film?")
                            },
                        )
                        .setNegativeButton(localized("取消", "Cancel"), null)
                        .setPositiveButton(localized("确定", "OK")) { _, _ ->
                            onReset()
                            dialog.dismiss()
                        }
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun buildMethod(
        kind: EditorKind,
        threshold: Double?,
        parameterText: String,
        maximumText: String,
        pointsText: String,
    ): ParsedMethod {
        if (threshold == null || !threshold.isFinite() || threshold <= 0.0 || threshold >= DAY_SECONDS) {
            return ParsedMethod(error = localized("请输入有效的无需补偿上限（小于 24 小时）。", "Enter a valid no-correction limit below 24 hours."))
        }
        if (kind == EditorKind.POWER && abs(threshold - 1.0) > 1e-6) {
            return ParsedMethod(error = localized("幂函数的无需补偿上限必须为 1 秒；其他边界请使用离散点曲线。", "Power curves must use a 1-second boundary; use a point curve for another boundary."))
        }
        val parameter = when (kind) {
            EditorKind.POWER -> parameterText.toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..4.0 }
            EditorKind.FIXED_EV -> parameterText.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 }
            else -> null
        }
        if (kind in setOf(EditorKind.POWER, EditorKind.FIXED_EV) && parameter == null) {
            return ParsedMethod(error = localized("请输入有效参数：P 为 1～4，固定补偿为 0～10 EV。", "Enter a valid parameter: P from 1–4 or fixed compensation from 0–10 EV."))
        }
        val parsedPoints = if (kind == EditorKind.TABLE) parsePoints(pointsText, threshold) else ParsedPoints(emptyList())
        if (parsedPoints.error != null) return ParsedMethod(error = parsedPoints.error)
        val maximum = if (kind == EditorKind.BOUNDED_UNCHANGED) {
            threshold
        } else {
            maximumText.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
                ?: parsedPoints.points.lastOrNull()?.meteredSeconds
        }
        if (maximum != null && (!maximum.isFinite() || maximum < threshold || maximum >= DAY_SECONDS)) {
            return ParsedMethod(error = localized("精确数据上限必须不小于无补偿上限，并且小于 24 小时。", "The exact-data limit must be at least the no-correction limit and below 24 hours."))
        }
        if (parsedPoints.points.lastOrNull()?.meteredSeconds?.let { maximum != null && it > maximum + 1e-9 } == true) {
            return ParsedMethod(error = localized("精确数据上限不能小于最后一个离散点。", "The exact-data limit cannot be below the last point."))
        }
        val method = ReciprocityMethod(
            id = "user-draft",
            type = kind.type,
            parameter = parameter,
            noCompensationSeconds = threshold,
            officialMaximumSeconds = maximum,
            evidence = "USER",
            longExposureFilter = "",
            filterRule = "",
            warning = "用户录入的倒易率数据；建议通过实拍和包围曝光验证。",
            sourceUrl = "",
            points = parsedPoints.points,
        )
        return ParsedMethod(method = method)
    }

    private fun parsePoints(value: String, threshold: Double): ParsedPoints {
        val normalized = value.replace(';', '\n').replace('；', '\n')
        val lines = normalized.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) {
            return ParsedPoints(error = localized("请至少输入一个离散点。", "Enter at least one point."))
        }
        val result = mutableListOf<ReciprocityPoint>()
        for ((index, line) in lines.withIndex()) {
            val values = line
                .replace("->", " ")
                .replace("→", " ")
                .replace("=", " ")
                .replace(",", " ")
                .replace("，", " ")
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull(String::toDoubleOrNull)
            if (values.size != 2) {
                return ParsedPoints(error = localized("第 ${index + 1} 行格式错误，应为“测光秒 = 校正秒”。", "Line ${index + 1} must be ‘metered seconds = corrected seconds’."))
            }
            val metered = values[0]
            val corrected = values[1]
            if (!metered.isFinite() || !corrected.isFinite() || metered <= threshold || corrected < metered || corrected >= DAY_SECONDS) {
                return ParsedPoints(
                    error = localized(
                        "第 ${index + 1} 行无效：测光时间须大于无补偿上限，校正时间不得更短且须小于 24 小时。",
                        "Line ${index + 1} is invalid: metered time must exceed the limit, and corrected time must be no shorter and below 24 hours.",
                    ),
                )
            }
            result += ReciprocityPoint(metered, corrected, null)
        }
        val sorted = result.sortedBy(ReciprocityPoint::meteredSeconds)
        if (sorted.zipWithNext().any { (left, right) -> abs(left.meteredSeconds - right.meteredSeconds) <= 1e-9 }) {
            return ParsedPoints(error = localized("离散点的测光时间不能重复。", "Metered times must not be duplicated."))
        }
        if (sorted.zipWithNext().any { (left, right) -> right.correctedSeconds < left.correctedSeconds }) {
            return ParsedPoints(error = localized("校正时间必须随测光时间单调增加。", "Corrected time must increase monotonically."))
        }
        if (sorted.zipWithNext().any { (left, right) ->
                log2(right.correctedSeconds / right.meteredSeconds) + 1e-9 <
                    log2(left.correctedSeconds / left.meteredSeconds)
            }
        ) {
            return ParsedPoints(
                error = localized(
                    "补偿档数必须随测光时间保持不变或增加，不能出现凹陷节点。",
                    "Compensation stops must stay level or increase with metered time; dipping nodes are not allowed.",
                ),
            )
        }
        return ParsedPoints(points = sorted)
    }

    private fun field(hint: String, inputType: Int): EditText = EditText(context).apply {
        this.hint = hint
        this.inputType = inputType
        isSingleLine = true
    }

    private fun label(value: String): TextView = TextView(context).apply {
        text = value
        textSize = 12f
        setPadding(0, 10, 0, 0)
    }

    private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        "%.6f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private enum class EditorKind(val type: ReciprocityMethodType) {
        TABLE(ReciprocityMethodType.TABLE),
        POWER(ReciprocityMethodType.POWER),
        FIXED_EV(ReciprocityMethodType.FIXED_EV),
        BOUNDED_UNCHANGED(ReciprocityMethodType.BOUNDED_UNCHANGED);

        companion object {
            fun from(type: ReciprocityMethodType?): EditorKind = entries.firstOrNull { it.type == type } ?: TABLE
        }
    }

    private data class ParsedMethod(val method: ReciprocityMethod? = null, val error: String? = null)
    private data class ParsedPoints(val points: List<ReciprocityPoint> = emptyList(), val error: String? = null)

    private companion object {
        const val DAY_SECONDS = 24.0 * 60.0 * 60.0
        const val TEXT_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        const val NUMBER_INPUT = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }
}
