package com.lightmeter.rawmeter

import java.util.Locale

internal object EvaluativeMeteringPresentation {
    fun message(result: EvaluativeMeteringResult, language: MenuLanguage): String {
        val english = language == MenuLanguage.ENGLISH
        val ev = String.format(Locale.US, "%.1f", result.recommendedEv100)
        val base = if (english) "Global metering · EV $ev" else "全局测光 · EV $ev"
        val adjustment = if (result.exposureReductionEv > 0.01) {
            val stops = String.format(Locale.US, "%.1f", result.exposureReductionEv)
            if (english) " · highlight adjustment −$stops EV" else " · 亮部调整 −$stops EV"
        } else ""
        val quality = when {
            !result.highlightsConfirmed -> if (english) " · highlights unconfirmed" else " · 亮部未确认"
            result.highlightResidualEv > 0.05 -> if (english) " · high contrast" else " · 反差较大"
            else -> ""
        }
        return base + adjustment + quality
    }
}
