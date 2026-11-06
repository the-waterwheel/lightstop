package com.lightmeter.rawmeter

import java.util.Locale

/** Range of the preview actuator, independent of whether a metering reading is valid. */
data class ExposurePreviewLimit(
    val requestedEv: Double,
    val availableEv: Double,
    val manual: Boolean,
) {
    internal fun title(language: MenuLanguage): String = if (language == MenuLanguage.ENGLISH)
        "Preview range limited · Use metered settings" else "预览范围受限 · 以测光参数为准"

    internal fun detail(language: MenuLanguage): String {
        val unit = if (manual) "EV100" else "EV"
        val format = if (manual) "%.1f" else "%+.1f"
        val requested = String.format(Locale.US, format, requestedEv)
        val available = String.format(Locale.US, format, availableEv)
        return if (language == MenuLanguage.ENGLISH) "Target $requested / Limit $available $unit"
            else "目标 $requested / 可用 $available $unit"
    }

    companion object {
        fun manual(selection: ExposurePreviewSelection, exposure: ExposurePreviewManualExposure): ExposurePreviewLimit? {
            val target = ExposurePreviewMath.targetCameraEv100(selection)
            return if (exposure.clamped && target.isFinite() && exposure.appliedCameraEv100.isFinite())
                ExposurePreviewLimit(target, exposure.appliedCameraEv100, manual = true) else null
        }

        fun compensation(compensation: ExposurePreviewCompensation): ExposurePreviewLimit? =
            if (compensation.supported && compensation.clamped && compensation.requestedEv.isFinite() &&
                compensation.appliedEv.isFinite())
                ExposurePreviewLimit(compensation.requestedEv, compensation.appliedEv, manual = false) else null
    }
}
