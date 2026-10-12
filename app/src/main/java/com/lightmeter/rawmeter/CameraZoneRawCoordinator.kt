package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.util.Log

/** Camera2 operations only. The host does not expose writable transaction state. */
internal interface CameraZoneRawHost {
    val rawSessionReady: Boolean
    val started: Boolean
    val cameraInfo: CameraUiInfo
    val characteristics: CameraCharacteristics?
    val rawHardwareAvailable: Boolean
    val confirmedRawWorkflow: Boolean
    fun resetCaptureState()
    fun clearPendingResidentSession()
    fun desiredResidentProfile(): CameraSessionProfile
    fun restoreCharacteristics(characteristics: CameraCharacteristics?)
    fun reconfigureSession(profile: CameraSessionProfile): Boolean
    fun startMeasurement(plan: MeteringPlan)
    fun postInfo(info: CameraUiInfo)
    fun finishOperation(rawSucceeded: Boolean)
    fun fallbackToCompatible(failure: ZoneRawFailure)
    fun postMeterError(message: String)
    fun recoverSafePreview()
}

internal interface CameraZoneRawListener {
    fun onRestoreStateChanged(restoring: Boolean)
    fun onReading(reading: MeterReading)
    fun onBatchReading(results: List<ZoneMeteringResult>)
}

/**
 * Owns the complete transient RAW transaction: frozen inputs, physical-camera evidence,
 * single result delivery, resident restoration and failure routing. Call on the camera handler;
 * only listener delivery crosses to mainHandler. The operation stays locked until restoration.
 *
 * Maintenance boundary: new transient RAW state belongs here, not in CameraController. Keep
 * Camera2 resources in the host and metering math in MeteringAnalysis/RawLightMeter. This owner
 * preserves the existing RAW callback contract; upstream request/session ownership is separate.
 */
internal class CameraZoneRawCoordinator(
    private val mainHandler: Handler,
    private val host: CameraZoneRawHost,
    private val listener: CameraZoneRawListener,
    private val latestReferenceFrame: () -> ZoneTrackingFrame?,
    private val localized: (String, String) -> String,
) {
    private val transactions = ZoneRawTransactionCoordinator()
    val isActive: Boolean get() = transactions.active != null
    val isRestoring: Boolean get() = transactions.active?.sessionState?.phase == ZoneRawSessionPhase.RESTORING

    /** Freeze references before replacing preview + YUV with one short RAW-only session. */
    fun begin(plan: MeteringPlan) {
        if (!host.rawSessionReady) {
            host.finishOperation(rawSucceeded = false)
            host.postMeterError(localized("RAW 流尚未就绪", "The RAW stream is not ready"))
            return
        }
        val transaction = transactions.begin(
            plan = freezeReference(plan),
            expectedPhysicalCameraId = host.cameraInfo.activePhysicalCameraId,
            residentCameraInfo = host.cameraInfo,
            residentCharacteristics = host.characteristics,
        )
        host.clearPendingResidentSession()
        host.resetCaptureState()
        host.postInfo(
            host.cameraInfo.copy(
                rawAvailable = false,
                status = localized("正在切换到 RAW 测光", "Switching to RAW metering"),
            ),
        )
        if (!host.reconfigureSession(CameraSessionProfile.RAW_ISOLATED)) {
            transaction.failure = failureFor(plan, localized("无法启动 RAW 测光，请重试", "Unable to start RAW metering"))
            restore(transaction)
        }
    }

    /** True means this configured session was consumed by transient RAW metering. */
    fun onRawSessionConfigured(profile: CameraSessionProfile?): Boolean {
        val transaction = transactions.active ?: return false
        if (transaction.sessionState.phase != ZoneRawSessionPhase.SWITCHING_TO_RAW ||
            profile != CameraSessionProfile.RAW_ISOLATED
        ) return false
        if (!transaction.sessionState.markRawSessionConfigured()) return true
        transaction.rawSessionConfiguredAtNs = System.nanoTime()
        host.postInfo(host.cameraInfo.copy(rawAvailable = true, status = localized("正在读取 RAW", "Reading RAW")))
        host.startMeasurement(transaction.plan)
        return true
    }

    fun observePhysicalCamera(activePhysicalCameraId: String?) {
        val transaction = transactions.active ?: return
        val expected = transaction.expectedPhysicalCameraId
        if (ZoneSessionPolicy.physicalCameraChanged(expected, activePhysicalCameraId)) {
            transaction.physicalCameraChanged = true
            Log.w(TAG, "Zone RAW changed physical camera expected=$expected " +
                "actual=$activePhysicalCameraId; the RAW reading will be discarded")
        }
    }

    fun onReading(reading: MeterReading): Boolean {
        val transaction = meteringTransaction() ?: return false
        if (transaction.physicalCameraChanged) {
            transaction.failure = failureFor(transaction.plan, localized(
                "RAW 流切换了物理镜头，已改用当前预览流测光",
                "RAW switched physical lenses; using the current preview stream",
            ))
        } else {
            transaction.reading = reading
            deliver(transaction) { listener.onReading(reading) }
        }
        restore(transaction)
        return true
    }

    fun onBatchReading(results: List<ZoneMeteringResult>): Boolean {
        val transaction = meteringTransaction() ?: return false
        if (transaction.physicalCameraChanged) {
            transaction.failure = failureFor(transaction.plan, localized(
                "RAW 流切换了物理镜头，本次批量测光未应用",
                "RAW switched physical lenses; this batch was not applied",
            )).copy(target = null)
        } else {
            transaction.batchResults = results
            deliver(transaction) { listener.onBatchReading(results) }
        }
        restore(transaction)
        return true
    }

    fun onMeteringError(failure: ZoneRawFailure): Boolean {
        val transaction = meteringTransaction() ?: return false
        transaction.failure = failure
        restore(transaction)
        return true
    }

    /** Called only after the resident preview request has started successfully. */
    fun onResidentSessionConfigured() {
        val transaction = transactions.active ?: return
        if (!transaction.sessionState.markResidentSessionConfigured()) return
        transaction.restoreCompletedAtNs = System.nanoTime()
        if (!transactions.clear(transaction)) return
        if (transaction.resultDelivered) {
            host.finishOperation(rawSucceeded = true)
            logLatency(transaction)
            mainHandler.post { listener.onRestoreStateChanged(false) }
            return
        }
        val failure = transaction.failure ?: failureFor(transaction.plan,
            localized("RAW 测光失败，请重试", "RAW metering failed. Please try again"))
        if (transaction.plan.zoneBatchTargets.isNotEmpty() || host.confirmedRawWorkflow) {
            host.finishOperation(rawSucceeded = false)
            host.postMeterError(failure.message)
        } else {
            host.fallbackToCompatible(failure)
        }
    }

    fun onSessionFailure(error: Exception?): Boolean {
        val transaction = transactions.active ?: return false
        return when (transaction.sessionState.phase) {
            ZoneRawSessionPhase.SWITCHING_TO_RAW -> {
                transaction.failure = transaction.failure ?: failureFor(transaction.plan, localized(
                    "当前设备无法启动独立 RAW 流，已恢复预览流测光",
                    "This device could not start isolated RAW; preview metering was restored",
                ))
                Log.w(TAG, "Isolated Zone RAW session failed; restoring resident session", error)
                restore(transaction)
                true
            }
            ZoneRawSessionPhase.RESTORING -> {
                Log.e(TAG, "Unable to restore resident session after Zone RAW", error)
                failRestore(transaction)
                true
            }
            ZoneRawSessionPhase.METERING, ZoneRawSessionPhase.COMPLETE -> false
        }
    }

    /** Camera close owns operation unlock and UI interruption; discard only our transaction. */
    fun reset() = transactions.reset()

    private fun meteringTransaction(): ZoneRawTransaction? = transactions.active?.takeIf {
        it.sessionState.phase == ZoneRawSessionPhase.METERING
    }

    private fun restore(transaction: ZoneRawTransaction) {
        if (!transactions.isActive(transaction) || !transaction.sessionState.beginRestore()) return
        transaction.restoreStartedAtNs = System.nanoTime()
        host.resetCaptureState()
        // Resolve live resident policy: Zone/film/calibration state may have changed during RAW.
        val profile = host.desiredResidentProfile()
        host.restoreCharacteristics(transaction.residentCharacteristics)
        host.postInfo(transaction.residentCameraInfo.copy(
            rawAvailable = profile.usesRaw && host.rawHardwareAvailable,
            status = localized("正在恢复预览", "Restoring preview"),
        ))
        if (!host.reconfigureSession(profile)) failRestore(transaction)
    }

    private fun failRestore(transaction: ZoneRawTransaction) {
        if (!transactions.clear(transaction)) return
        host.clearPendingResidentSession()
        host.finishOperation(rawSucceeded = false)
        if (transaction.resultDelivered) {
            logLatency(transaction)
            mainHandler.post { listener.onRestoreStateChanged(false) }
        }
        host.postMeterError(transaction.failure?.message ?: localized(
            "无法恢复相机预览，本次测光已中止", "Unable to restore preview; metering stopped",
        ))
        if (host.started) host.recoverSafePreview()
    }

    private fun deliver(transaction: ZoneRawTransaction, delivery: () -> Unit) {
        if (transaction.resultDelivered || !transactions.isActive(transaction)) return
        transaction.resultDelivered = true
        transaction.resultReadyAtNs = System.nanoTime()
        mainHandler.post {
            listener.onRestoreStateChanged(true)
            delivery()
        }
    }

    private fun freezeReference(plan: MeteringPlan): MeteringPlan {
        // Borrow synchronously on the camera handler; the frame pipeline retains/closes this frame.
        val frame = latestReferenceFrame() ?: return plan
        fun reference(target: ZoneMeteringTarget) = MeteringAnalysis.createPreviewReference(
            frame = frame, frameAspect = plan.screenAspect, zoom = plan.displayZoom, target = target,
        )
        if (plan.zoneBatchTargets.isNotEmpty()) {
            return plan.copy(zoneBatchTargets = plan.zoneBatchTargets.map {
                it.copy(previewReference = reference(it.target) ?: it.previewReference)
            })
        }
        val target = plan.target ?: return plan
        return plan.copy(displayedPreviewReference = reference(target) ?: plan.displayedPreviewReference)
    }

    private fun failureFor(plan: MeteringPlan, message: String) = ZoneRawFailure(
        message, plan.meteringMode, plan.target, plan.meteringRoiFraction,
    )

    private fun logLatency(transaction: ZoneRawTransaction) {
        val requested = transaction.plan.requestedAtNs
        val rawConfigured = transaction.rawSessionConfiguredAtNs
        val resultReady = transaction.resultReadyAtNs
        val restoreStarted = transaction.restoreStartedAtNs
        val restoreCompleted = transaction.restoreCompletedAtNs
        fun elapsed(from: Long?, to: Long?): Long? =
            if (from == null || to == null || to < from) null else (to - from) / 1_000_000L
        Log.i(TAG, "Zone RAW latency tapToRawConfigMs=${elapsed(requested, rawConfigured)} " +
            "tapToResultMs=${elapsed(requested, resultReady)} " +
            "rawResultToRestoreMs=${elapsed(resultReady, restoreStarted)} " +
            "restoreMs=${elapsed(restoreStarted, restoreCompleted)} " +
            "tapToReadyMs=${elapsed(requested, restoreCompleted)} " +
            "resultDelivered=${transaction.resultDelivered}")
    }

    private companion object { const val TAG = "lightstop" }
}
