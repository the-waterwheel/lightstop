package com.lightmeter.rawmeter

internal enum class CompatibleFrameDecision {
    COMPLETE,
    RETRY,
    USE_PREVIEW,
}

/** Device-independent limits for the fast, ISP-processed compatibility path. */
internal object CompatibleMeteringPolicy {
    const val FRAME_COUNT = 1
    const val MAX_YUV_ATTEMPTS = 3
    const val YUV_TIMEOUT_MS = 250L

    fun decide(attemptedFrames: Int, frameValid: Boolean): CompatibleFrameDecision = when {
        frameValid -> CompatibleFrameDecision.COMPLETE
        attemptedFrames >= MAX_YUV_ATTEMPTS -> CompatibleFrameDecision.USE_PREVIEW
        else -> CompatibleFrameDecision.RETRY
    }
}
