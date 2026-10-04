package com.lightmeter.rawmeter

/** Immutable counters for one RAW burst, separating valid samples from submitted requests. */
internal data class RawBurstState(
    val baseFrames: Int,
    val maxFrames: Int,
    val expectedFrames: Int,
    val completedFrames: Int,
    val validFrames: Int,
)

internal sealed interface RawBurstAction {
    /** Submit the next request and set the target to [nextExpectedFrames]. */
    data class Continue(val nextExpectedFrames: Int) : RawBurstAction

    /** Enough valid frames were fused. */
    data object FinishSuccess : RawBurstAction

    /** The request budget is exhausted before the base valid-frame requirement was met. */
    data object FinishError : RawBurstAction
}

/**
 * Pure RAW burst progress rule.
 *
 * The valid-frame target and the request budget are distinct: a valid first frame at high ISO must
 * not shrink the base target, an invalid frame consumes only the request budget, and the adaptive
 * append stays within [RawBurstState.maxFrames].
 */
internal object RawBurstProgressPolicy {
    fun afterFrame(state: RawBurstState, appendRecommended: Boolean): RawBurstAction {
        if (state.validFrames >= state.expectedFrames) {
            if (appendRecommended && state.validFrames < state.maxFrames) {
                return RawBurstAction.Continue(state.validFrames + 1)
            }
            return finish(state)
        }
        if (state.completedFrames >= state.maxFrames) return finish(state)
        return RawBurstAction.Continue(
            minOf(state.maxFrames, maxOf(state.expectedFrames, state.completedFrames + 1)),
        )
    }

    private fun finish(state: RawBurstState): RawBurstAction =
        if (state.validFrames >= state.baseFrames) {
            RawBurstAction.FinishSuccess
        } else {
            RawBurstAction.FinishError
        }
}
