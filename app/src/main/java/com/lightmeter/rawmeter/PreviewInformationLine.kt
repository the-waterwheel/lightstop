package com.lightmeter.rawmeter

internal object PreviewInformationLine {
    fun text(state: MeterState): String {
        if (state.previewInformationBarMode != PreviewInformationBarMode.ON) return ""
        val info = state.cameraInfo
        val aspect = PreviewOutputGeometry.label(state.currentPreviewLandscapeAspect())
        val source = when (state.lastReading?.source) {
            MeteringSource.RAW -> "RAW"
            MeteringSource.YUV_PREVIEW -> "YUV"
            MeteringSource.ISP_PREVIEW -> "ISP"
            null -> if (info.rawAvailable) "RAW" else "ISP/YUV"
        }
        val fps = if (info.actualPreviewFps > 0.1f) {
            "%.1f fps".format(info.actualPreviewFps)
        } else if (info.previewFpsLower > 0 && info.previewFpsUpper > 0 &&
            info.previewFpsLower != info.previewFpsUpper
        ) {
            "${info.previewFpsLower}–${info.previewFpsUpper} fps"
        } else {
            "${info.previewFpsUpper.coerceAtLeast(info.previewFps)} fps"
        }
        val status = state.transientMessage ?: info.status
        return listOf(aspect, source, fps, status).filter { it.isNotBlank() }.joinToString("  ·  ")
    }
}
