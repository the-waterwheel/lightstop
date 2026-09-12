package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface

/** One real-session traversal of every distinct stage in a camera workflow. */
internal class CameraCombinationWorkflowProbe(
    val plan: CameraCombinationPlan,
    val completion: (Result<Unit>) -> Unit,
) {
    val stages: List<CameraSessionProfile> = stagesFor(plan)
    var stageIndex: Int = 0
        private set

    val currentStage: CameraSessionProfile?
        get() = stages.getOrNull(stageIndex)

    /** Moves to the next distinct stage, or returns null after the restored resident preview. */
    fun advance(): CameraSessionProfile? {
        stageIndex += 1
        return currentStage
    }

    companion object {
        fun stagesFor(plan: CameraCombinationPlan): List<CameraSessionProfile> = buildList {
            fun addDistinct(profile: CameraSessionProfile) {
                if (lastOrNull() != profile) add(profile)
            }
            addDistinct(plan.normalResidentProfile)
            addDistinct(plan.normalMeteringProfile)
            addDistinct(plan.zoneResidentProfile)
            addDistinct(plan.zoneMeteringProfile)
            // The user must judge the workflow after its ordinary preview has been restored.
            addDistinct(plan.normalResidentProfile)
        }
    }
}

/** Executes a workflow probe while CameraController remains responsible for session policy. */
internal class CameraCombinationWorkflowProbeRunner(
    private val mainHandler: Handler,
    private val cameraHandler: () -> Handler?,
    private val currentGeneration: () -> Int,
    private val currentProfile: () -> CameraSessionProfile?,
    private val rawSurface: () -> Surface?,
    private val rawCharacteristics: () -> CameraCharacteristics?,
    private val expectedRawSize: () -> Size?,
    private val configureAutoFocus: (CaptureRequest.Builder) -> Unit,
    private val startPreview: (
        CameraDevice,
        CameraCaptureSession,
        Surface,
        Int,
    ) -> Boolean,
    private val reconfigure: (CameraSessionProfile) -> Boolean,
    private val onStageConfigured: () -> Unit,
) {
    @Volatile
    var active: CameraCombinationWorkflowProbe? = null
        private set

    val isActive: Boolean
        get() = active != null

    private val rawFramePairer = TimestampedResultPairer<Image, CaptureResult>(Image::close)
    private var rawStageRevision = 0
    private var rawStageGeneration = -1
    private var rawStageTimeout: Runnable? = null

    fun begin(
        plan: CameraCombinationPlan,
        completion: (Result<Unit>) -> Unit,
    ): CameraCombinationWorkflowProbe {
        check(active == null) { "A camera combination probe is already active" }
        return CameraCombinationWorkflowProbe(plan, completion).also { active = it }
    }

    fun onSessionConfigured(
        device: CameraDevice,
        session: CameraCaptureSession,
        previewSurface: Surface?,
        generation: Int,
    ): Boolean {
        val probe = active ?: return false
        if (generation != currentGeneration()) return true
        val expected = probe.currentStage
        if (expected == null || expected != currentProfile()) {
            fail(IllegalStateException("Unexpected probe profile=${currentProfile()}"))
            return true
        }
        onStageConfigured()
        if (expected.usesPreview) {
            val preview = previewSurface
            if (preview == null) {
                fail(IllegalStateException("Probe preview Surface is missing"))
                return true
            }
            if (!startPreview(device, session, preview, generation)) {
                fail(IllegalStateException("Unable to start the probe preview request"))
                return true
            }
        }
        if (expected.usesRaw) {
            captureRawFrame(probe, device, session, generation)
        } else {
            scheduleAdvance(probe, PROBE_STAGE_DELAY_MS)
        }
        return true
    }

    fun cancel(error: Throwable): Boolean = fail(error)

    /**
     * Owns ImageReader output while a workflow probe is active. A RAW stage cannot pass until a
     * real Image is exactly paired with its CaptureResult and passes hard O(1) usability checks.
     */
    fun onRawImageAvailable(reader: ImageReader): Boolean {
        val probe = active ?: return false
        val image = try {
            reader.acquireNextImage()
        } catch (error: IllegalStateException) {
            if (probe.currentStage?.usesRaw == true) fail(error)
            return true
        } ?: return true
        if (probe.currentStage?.usesRaw != true || rawStageGeneration != currentGeneration()) {
            image.close()
            return true
        }
        val revision = rawStageRevision
        rawFramePairer.offerImage(image.timestamp, image)?.let { pair ->
            finishRawFrame(probe, pair, rawStageGeneration, revision)
        }
        return true
    }

    /** Drops an internal system probe whose owning camera generation has already been reset. */
    fun abandon() {
        clearRawStage()
        active = null
    }

    private fun captureRawFrame(
        probe: CameraCombinationWorkflowProbe,
        device: CameraDevice,
        session: CameraCaptureSession,
        generation: Int,
    ) {
        val handler = cameraHandler()
        val surface = rawSurface()
        if (handler == null || surface == null) {
            fail(IllegalStateException("Probe RAW Surface is missing"))
            return
        }
        clearRawStage()
        val stageRevision = ++rawStageRevision
        rawStageGeneration = generation
        scheduleRawStageTimeout(probe, generation, stageRevision, handler)
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                configureAutoFocus(this)
            }
            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        if (generation == currentGeneration() && active === probe) {
                            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                            if (timestamp == null || timestamp <= 0L) {
                                fail(IllegalStateException("Probe RAW result has no sensor timestamp"))
                                return
                            }
                            rawFramePairer.offerResult(timestamp, result)?.let { pair ->
                                finishRawFrame(probe, pair, generation, stageRevision)
                            }
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        if (generation == currentGeneration() && active === probe) {
                            fail(
                                IllegalStateException(
                                    "Manual RAW probe failed: ${failure.reason}",
                                ),
                            )
                        }
                    }

                    override fun onCaptureSequenceAborted(
                        session: CameraCaptureSession,
                        sequenceId: Int,
                    ) {
                        if (generation == currentGeneration() && active === probe) {
                            fail(IllegalStateException("Manual RAW probe sequence was aborted"))
                        }
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun finishRawFrame(
        probe: CameraCombinationWorkflowProbe,
        pair: TimestampedResultPair<Image, CaptureResult>,
        generation: Int,
        stageRevision: Int,
    ) {
        if (active !== probe || generation != currentGeneration() ||
            stageRevision != rawStageRevision
        ) {
            pair.image.close()
            return
        }
        val characteristics = rawCharacteristics()
        if (characteristics == null) {
            pair.image.close()
            fail(IllegalStateException("Probe RAW characteristics are missing"))
            return
        }
        val health = try {
            RawProbeFrameHealthPolicy.evaluate(
                image = pair.image,
                result = pair.result,
                characteristics = characteristics,
                expectedSize = expectedRawSize(),
            )
        } catch (error: Exception) {
            fail(error)
            return
        } finally {
            // No strategy work may retain a full-size RAW buffer after the O(1) validation.
            pair.image.close()
        }
        if (!health.usable) {
            fail(IllegalStateException("Probe RAW frame is unusable: ${health.reason}"))
            return
        }
        clearRawStage()
        scheduleAdvance(probe, PROBE_RAW_STAGE_SETTLE_DELAY_MS)
    }

    private fun scheduleRawStageTimeout(
        probe: CameraCombinationWorkflowProbe,
        generation: Int,
        stageRevision: Int,
        handler: Handler,
    ) {
        lateinit var task: Runnable
        task = Runnable {
            if (rawStageTimeout !== task) return@Runnable
            rawStageTimeout = null
            if (active === probe && generation == currentGeneration() &&
                stageRevision == rawStageRevision
            ) {
                fail(IllegalStateException("Timed out waiting for a paired RAW probe frame"))
            }
        }
        rawStageTimeout = task
        if (!handler.postDelayed(task, PROBE_RAW_FRAME_TIMEOUT_MS)) {
            rawStageTimeout = null
            fail(IllegalStateException("Camera handler rejected the RAW probe timeout"))
        }
    }

    private fun clearRawStage() {
        rawStageRevision += 1
        rawStageGeneration = -1
        rawStageTimeout?.let { cameraHandler()?.removeCallbacks(it) }
        rawStageTimeout = null
        rawFramePairer.clear()
    }

    private fun scheduleAdvance(
        probe: CameraCombinationWorkflowProbe,
        delayMs: Long,
    ) {
        val handler = cameraHandler()
        if (handler == null || !handler.postDelayed({ advance(probe) }, delayMs)) {
            fail(IllegalStateException("Camera handler rejected the probe stage task"))
        }
    }

    private fun advance(probe: CameraCombinationWorkflowProbe) {
        if (active !== probe) return
        clearRawStage()
        val next = probe.advance()
        if (next == null) {
            active = null
            mainHandler.post { probe.completion(Result.success(Unit)) }
            return
        }
        if (!reconfigure(next)) {
            fail(IllegalStateException("Unable to configure probe profile=$next"))
        }
    }

    private fun fail(error: Throwable): Boolean {
        val probe = active ?: return false
        clearRawStage()
        active = null
        Log.w(TAG, "Camera combination probe failed plan=${probe.plan.id}", error)
        mainHandler.post { probe.completion(Result.failure(error)) }
        return true
    }

    private companion object {
        private const val TAG = "CombinationProbe"
        private const val PROBE_STAGE_DELAY_MS = 650L
        private const val PROBE_RAW_STAGE_SETTLE_DELAY_MS = 100L
        private const val PROBE_RAW_FRAME_TIMEOUT_MS = 3_000L
    }
}
