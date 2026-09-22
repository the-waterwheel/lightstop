package com.lightmeter.rawmeter

import android.graphics.SurfaceTexture
import android.os.Handler
import android.util.Log
import android.view.TextureView

/**
 * Owns preview bitmap health sampling and the state machine used to confirm a recovered route.
 * Camera/session mutations are supplied as actions so vendor-HAL recovery remains centralized.
 */
internal class CameraPreviewHealthCoordinator(
    private val mainHandler: Handler,
    private val cameraHandler: () -> Handler?,
    private val started: () -> Boolean,
    private val cameraGeneration: () -> Int,
    private val sessionTransitionActive: () -> Boolean,
    private val retryAtStandardFrameRate: () -> Boolean,
    private val confirmedRawWorkflow: () -> Boolean,
    private val advanceSystemCombination: () -> Boolean,
    private val tryNextCameraRoute: () -> Boolean,
    private val failSafePreview: () -> Unit,
    private val failPreview: () -> Unit,
    private val scheduleSafePreviewRecovery: () -> Unit,
    private val markPreviewStable: () -> Unit,
) {
    private val sampler = PreviewHealthSampler(
        onFailure = ::requestRecovery,
        onHealthyPreviewConfirmed = ::confirmRecovery,
    )

    @Volatile
    private var enabled = true

    @Volatile
    private var recoveryPending = false

    private val recoveryBudget = PreviewHealthRecoveryBudget(MAX_RECOVERY_ATTEMPTS)
    private var confirmationPending = false
    private var confirmationGeneration = NO_GENERATION

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        refreshMonitoring()
        cameraHandler()?.post {
            recoveryPending = false
            clearConfirmation()
        }
    }

    fun onTextureUpdated(
        textureView: TextureView?,
        surface: SurfaceTexture,
        samplingAllowed: Boolean,
    ) {
        if (enabled && samplingAllowed) {
            sampler.onTextureUpdated(textureView, surface, started())
        }
    }

    /** Clears route-scoped failure evidence after a user selection or recovery-policy reset. */
    fun resetRecoveryState() {
        recoveryBudget.reset()
        clearConfirmation()
    }

    fun clearConfirmation() {
        confirmationPending = false
        confirmationGeneration = NO_GENERATION
    }

    /** Associates a pending safe-preview confirmation with the newly submitted request stream. */
    fun armConfirmation(generation: Int) {
        if (confirmationPending) confirmationGeneration = generation
    }

    fun markLongRunningPreviewStable() {
        recoveryBudget.reset()
    }

    /** Re-evaluates UI-thread bitmap sampling after lifecycle or camera-session changes. */
    fun refreshMonitoring() {
        mainHandler.post(::resetMonitoring)
    }

    private fun requestRecovery(reason: PreviewHealthReason) {
        if (!enabled || recoveryPending) return
        recoveryPending = true
        val generation = cameraGeneration()
        cameraHandler()?.post {
            if (!enabled || !started() || generation != cameraGeneration()) return@post
            recoveryPending = false
            // Deliberate session replacements freeze TextureView briefly. Wait for fresh samples
            // from the restored session before classifying its output.
            if (sessionTransitionActive()) return@post
            when (
                CameraPreviewHealthRecoveryPolicy.decide(
                    confirmedRawWorkflow = confirmedRawWorkflow(),
                    retryAtStandardRate = retryAtStandardFrameRate(),
                )
            ) {
                PreviewHealthRecoveryDecision.RETRY_STANDARD_RATE -> {
                    Log.w(TAG, "Preview health failure at high FPS; retrying same workflow reason=$reason")
                    return@post
                }

                PreviewHealthRecoveryDecision.CONFIRMED_WARNING_ONLY -> {
                    // A confirmed RAW workflow is not replaced by a soft picture-quality heuristic.
                    // Real session/device faults are handled by the camera failure path, not here.
                    Log.w(
                        TAG,
                        "Confirmed RAW workflow: preview health warning only, no fallback reason=$reason",
                    )
                    return@post
                }

                PreviewHealthRecoveryDecision.CONTINUE -> Unit
            }
            if (advanceSystemCombination()) return@post
            if (confirmationPending && confirmationGeneration == generation) {
                Log.w(TAG, "Safe preview failed health confirmation reason=$reason")
                if (!tryNextCameraRoute()) {
                    clearConfirmation()
                    failSafePreview()
                }
                return@post
            }
            if (!recoveryBudget.canAttempt()) {
                if (!tryNextCameraRoute()) failPreview()
                return@post
            }
            recoveryBudget.recordAttempt()
            confirmationPending = true
            confirmationGeneration = NO_GENERATION
            scheduleSafePreviewRecovery()
            Log.w(TAG, "Recovering from preview health failure reason=$reason")
        }
    }

    /** Accepts a recovered route only after the sampler confirms three healthy preview frames. */
    private fun confirmRecovery() {
        val generation = cameraGeneration()
        cameraHandler()?.post {
            if (!started() || generation != cameraGeneration() || !confirmationPending ||
                confirmationGeneration != generation
            ) {
                return@post
            }
            clearConfirmation()
            recoveryBudget.reset()
            markPreviewStable()
            Log.i(TAG, "Safe preview health confirmation succeeded generation=$generation")
        }
    }

    /** Preview bitmap sampling and its monitor state are confined to the main/UI thread. */
    private fun resetMonitoring() {
        if (enabled && started()) {
            sampler.restartMonitoringWindow()
        } else {
            sampler.stopMonitoring()
        }
        recoveryPending = false
        if (!started()) clearConfirmation()
    }

    companion object {
        private const val TAG = "lightstop"
        private const val MAX_RECOVERY_ATTEMPTS = 1
        private const val NO_GENERATION = -1
    }
}
