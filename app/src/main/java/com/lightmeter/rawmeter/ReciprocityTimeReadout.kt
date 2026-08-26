package com.lightmeter.rawmeter

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ReciprocityTimeReadout(
    val fields: List<String>,
    val units: List<String>,
    val reciprocal: String? = null,
) {
    val text: String get() = reciprocal ?: fields.joinToString(":")

    fun spoken(chinese: Boolean): String = when {
        reciprocal != null -> reciprocal
        fields.size == 3 && chinese -> "${fields[0]} 小时 ${fields[1]} 分 ${fields[2]} 秒"
        fields.size == 3 -> "${fields[0]} hours ${fields[1]} minutes ${fields[2]} seconds"
        chinese -> "${fields[0]} 分 ${fields[1]} 秒"
        else -> "${fields[0]} minutes ${fields[1]} seconds"
    }

    companion object {
        fun from(seconds: Double): ReciprocityTimeReadout {
            if (seconds > 0.0 && seconds < 1.0) {
                val inverse = 1.0 / seconds.coerceAtLeast(1e-9)
                val denominator = if (inverse < 10.0 && abs(inverse - inverse.roundToInt()) > 0.04) {
                    String.format(Locale.US, "%.1f", inverse)
                } else {
                    inverse.roundToInt().toString()
                }
                return ReciprocityTimeReadout(emptyList(), emptyList(), "1/$denominator")
            }
            val rounded = seconds.coerceAtLeast(0.0).roundToInt()
            val hours = rounded / 3600
            val minutes = (rounded % 3600) / 60
            val remainingSeconds = rounded % 60
            return if (hours > 0) {
                ReciprocityTimeReadout(
                    fields = listOf("%02d".format(Locale.US, hours), "%02d".format(Locale.US, minutes), "%02d".format(Locale.US, remainingSeconds)),
                    units = listOf("h", "min", "s"),
                )
            } else {
                ReciprocityTimeReadout(
                    fields = listOf("%02d".format(Locale.US, minutes), "%02d".format(Locale.US, remainingSeconds)),
                    units = listOf("min", "s"),
                )
            }
        }
    }
}
