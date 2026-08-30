package com.lightmeter.rawmeter

internal enum class CompatibleFrameDecision {
    COMPLETE,
    RETRY,
    USE_PREVIEW,
}

/** Device-independent limits for the fast, ISP-processed compatibility path. */
internal object CompatibleMeteringPolicy {
    const val FRAME_COUNT = 1
    // Missing exposure fields in one or a few startup results are transient on some vendor HALs.
    // Retry for the bounded timeout window instead of permanently judging YUV from one frame.
    const val MAX_YUV_ATTEMPTS = 30
    // A calibration session starts with preview-only targets and enables YUV immediately after
    // Camera2 reports the session configured. Some vendor HALs need several repeating frames
    // before the first timestamp-paired YUV image arrives, so 250 ms was too short in practice.
    const val YUV_TIMEOUT_MS = 1_500L
    const val DISPLAY_CAPTURE_ATTEMPTS = 48
    const val DISPLAY_CAPTURE_RETRY_DELAY_MS = 32L

    fun decide(attemptedFrames: Int, frameValid: Boolean): CompatibleFrameDecision = when {
        frameValid -> CompatibleFrameDecision.COMPLETE
        attemptedFrames >= MAX_YUV_ATTEMPTS -> CompatibleFrameDecision.USE_PREVIEW
        else -> CompatibleFrameDecision.RETRY
    }
}
