package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

private typealias VisibleViewport = ZoneVisibleViewport

/**
 * Tracks Zone markers from the displayed preview only; measured EV values still come exclusively
 * from the RAW/fallback metering pipeline. OpenCV supplies sparse pyramidal LK optical flow and a
 * RANSAC global camera-motion model. A local corner cluster around each marker corrects residual
 * parallax, while the gyroscope is only an initial prediction and never overrides valid vision.
 */
class OpenCvZoneMarkerTracker(
    private val textureView: TextureView,
    private val meterState: MeterState,
    private val tuning: ZoneTrackingTuning,
    private val callback: (Int, Float, Float, ZoneTrackingState) -> Unit,
) : ZoneMarkerTracker {
    private data class Track(
        val id: Int,
        // Always stored in the unzoomed 1x preview coordinate space.
        var baseX: Float,
        var baseY: Float,
        var misses: Int = 0,
        var trackingState: ZoneTrackingState = ZoneTrackingState.PENDING,
        var referenceAnchor: Point? = null,
        var referenceKeypoints: List<Point> = emptyList(),
        var referenceDescriptors: Mat? = null,
        var nextReferenceCaptureFrame: Long = 0L,
    )

    private data class Update(
        val id: Int,
        val baseX: Float,
        val baseY: Float,
        val state: ZoneTrackingState,
    )

    private data class ValidFlow(
        val previous: Point,
        val current: Point,
        val ownerId: Int,
        var inlier: Boolean = false,
    )

    private data class LocalEvidence(
        val dx: Double,
        val dy: Double,
        val support: Int,
        val dispersion: Double,
    )

    private data class ReentryFrameFeatures(
        val keypoints: Array<Point>,
        val descriptors: Mat,
    ) {
        fun release() = descriptors.release()
    }

    private data class ReentryMatch(
        val point: Point,
        val inlierCount: Int,
    )

    private data class SharedReentryCorrection(
        val dx: Double,
        val dy: Double,
        val support: Int,
    )

    private data class FrameCoordinateSpace(
        val displayOriented: Boolean,
        val displayRotationDegrees: Int,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zone-opencv-tracker").apply { isDaemon = true }
    }
    private val processing = AtomicBoolean(false)
    private val externalFrameReserved = AtomicBoolean(false)
    private val resetRequested = AtomicBoolean(true)
    private val redetectRequested = AtomicBoolean(true)
    private val forceReidentificationRequested = AtomicBoolean(false)
    private val meteringActive = AtomicBoolean(false)
    private val exposureRecoveryFramesRemaining = AtomicInteger(0)
    private val mappingRevision = AtomicInteger(0)
    private val stabilizationFramesRemaining = AtomicInteger(0)
    private val externalFramesSeen = AtomicBoolean(false)
    private val lock = Any()
    private val tracks = linkedMapOf<Int, Track>()
    // The factory guarantees that the native library is loaded before this constructor runs.
    private val reentryOrb = ORB.create(REENTRY_FEATURE_COUNT)
    private val reentryMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
    private val framePreprocessor = ZoneOpenCvFramePreprocessor(tuning.trackingLongEdge)
    private val gyroscopeMotion = ZoneGyroscopeMotion(textureView, meterState)

    @Volatile
    private var running = false
    private var captureBitmap: Bitmap? = null
    private var rgba = Mat()
    private var gray = Mat()
    private var previousGray = Mat()
    private var previousPoints = MatOfPoint2f()
    private var previousOwners = IntArray(0)
    private var framesUntilRedetect = 0
    private var statsStartedAtMs = 0L
    private var statsFrames = 0
    private var processedFrameNumber = 0L
    @Volatile
    private var lastExternalFrameAtMs = 0L
    @Volatile
    private var externalFramesDisabled = false
    private var emptyExternalFeatureFrames = 0
    private var externalFrameQualityWarningLogged = false
    @Volatile
    private var lastExternalFrameRotationDegrees = UNKNOWN_FRAME_ROTATION
    private var frameCoordinatesAreDisplayOriented = false
    private var processedFrameCoordinateSpace: FrameCoordinateSpace? = null
    private var lastFrameMeanLuma = Double.NaN
    @Volatile
    private var visibleViewport = VisibleViewport(0f, 0f, 1f, 1f)
    @Volatile
    private var trackedDisplayZoom = 1f

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            captureFrame()
            mainHandler.postDelayed(this, nextFrameIntervalMs())
        }
    }

    override fun start(markers: List<ZoneMarker>) {
        // A previous worker task may still be unwinding after an orientation or lifecycle stop.
        // Give this run a new generation so that task cannot update the freshly rebuilt tracks.
        mappingRevision.incrementAndGet()
        // This is a fresh run after the preview mapping is stable; the previous run's display
        // rotation must not be treated as a live frame-to-frame coordinate transition.
        processedFrameCoordinateSpace = null
        frameCoordinatesAreDisplayOriented = false
        trackedDisplayZoom = meterState.zoom.coerceAtLeast(1f)
        val viewport = visibleViewport
        synchronized(lock) {
            val previous = tracks.values.toList()
            tracks.clear()
            markers.forEach { marker ->
                val (baseX, baseY) = uiPreviewToBasePreview(
                    marker.normalizedX,
                    marker.normalizedY,
                    viewport,
                )
                tracks[marker.id] = Track(
                    marker.id,
                    baseX,
                    baseY,
                    trackingState = marker.trackingState,
                )
            }
            // A frame/orientation/viewport remap invalidates descriptor coordinates as well as
            // optical flow. Keeping the old references lets ORB re-identification rotate the
            // freshly re-anchored UI positions back into the previous display orientation.
            previous.forEach { it.releaseReference() }
        }
        running = true
        externalFramesSeen.set(false)
        lastExternalFrameAtMs = 0L
        externalFramesDisabled = false
        emptyExternalFeatureFrames = 0
        externalFrameQualityWarningLogged = false
        lastExternalFrameRotationDegrees = UNKNOWN_FRAME_ROTATION
        statsStartedAtMs = SystemClock.elapsedRealtime()
        statsFrames = 0
        Log.i(
            TAG,
            "start displayRotation=${displayRotationDegrees()} " +
                "landscape=${meterState.landscape} markers=${markers.size}",
        )
        resetRequested.set(true)
        redetectRequested.set(true)
        forceReidentificationRequested.set(false)
        meteringActive.set(false)
        exposureRecoveryFramesRemaining.set(0)
        lastFrameMeanLuma = Double.NaN
        gyroscopeMotion.start(mainHandler)
        mainHandler.removeCallbacks(tick)
        mainHandler.post(tick)
    }

    override fun stop() {
        running = false
        // Drop callbacks already queued on the main thread and invalidate in-flight frame work.
        mappingRevision.incrementAndGet()
        cancelFrameReservation()
        mainHandler.removeCallbacks(tick)
        gyroscopeMotion.stop()
        resetRequested.set(true)
        meteringActive.set(false)
        exposureRecoveryFramesRemaining.set(0)
        lastFrameMeanLuma = Double.NaN
    }

    override fun release() {
        stop()
        worker.execute {
            synchronized(lock) {
                tracks.values.forEach { it.releaseReference() }
                tracks.clear()
            }
            releaseOpenCvState()
            captureBitmap?.recycle()
            captureBitmap = null
        }
        worker.shutdown()
    }

    override fun addMarker(id: Int, normalizedX: Float, normalizedY: Float) {
        val (baseX, baseY) = uiPreviewToBasePreview(
            normalizedX,
            normalizedY,
            visibleViewport,
        )
        synchronized(lock) {
            tracks[id] = Track(id, baseX, baseY)
        }
        redetectRequested.set(true)
        captureReferenceFromLastProcessedFrame()
        // Take the closest possible preview sample before the RAW burst can stall rendering.
        // This makes the new marker refer to the scene at button-press time, rather than to the
        // first frame that happens to arrive after metering finishes.
        requestImmediateFrame()
    }

    override fun removeMarker(id: Int) {
        synchronized(lock) { tracks.remove(id)?.releaseReference() }
        redetectRequested.set(true)
    }

    override fun clearMarkers() {
        synchronized(lock) {
            tracks.values.forEach { it.releaseReference() }
            tracks.clear()
        }
        redetectRequested.set(true)
    }

    override fun onMeteringStateChanged(active: Boolean) {
        val changed = meteringActive.getAndSet(active) != active
        if (!changed) return
        resetRequested.set(true)
        redetectRequested.set(true)
        if (!active) {
            exposureRecoveryFramesRemaining.set(EXPOSURE_RECOVERY_FRAMES)
            Log.i(TAG, "metering ended; protecting marker constellation during ISP recovery")
        }
    }

    override fun resetMarker(id: Int, normalizedX: Float, normalizedY: Float) {
        val (baseX, baseY) = uiPreviewToBasePreview(
            normalizedX,
            normalizedY,
            visibleViewport,
        )
        synchronized(lock) {
            tracks[id]?.let { track ->
                track.baseX = baseX
                track.baseY = baseY
                track.misses = 0
                track.releaseReference()
            }
        }
        redetectRequested.set(true)
    }

    override fun reanchor(markers: List<ZoneMarker>) {
        mappingRevision.incrementAndGet()
        trackedDisplayZoom = meterState.zoom.coerceAtLeast(1f)
        val viewport = visibleViewport
        synchronized(lock) {
            val previous = tracks.toMap()
            tracks.clear()
            markers.forEach { marker ->
                val (baseX, baseY) = uiPreviewToBasePreview(
                    marker.normalizedX,
                    marker.normalizedY,
                    viewport,
                )
                val existing = previous[marker.id]
                tracks[marker.id] = Track(
                    marker.id,
                    baseX,
                    baseY,
                    trackingState = marker.trackingState,
                    referenceAnchor = existing?.referenceAnchor,
                    referenceKeypoints = existing?.referenceKeypoints ?: emptyList(),
                    referenceDescriptors = existing?.referenceDescriptors,
                )
            }
            previous.filterKeys { it !in tracks }.values.forEach { it.releaseReference() }
        }
        stabilizationFramesRemaining.set(tuning.mappingStabilizationFrames.coerceAtLeast(1))
        resetRequested.set(true)
        redetectRequested.set(true)
        gyroscopeMotion.resetAccumulation()
    }

    override fun setDisplayZoom(zoom: Float) {
        trackedDisplayZoom = zoom.coerceAtLeast(1f)
        publishCurrentPositions()
    }

    override fun tryReserveFrame(): Boolean {
        if (!running || externalFramesDisabled) return false
        if (!processing.compareAndSet(false, true)) return false
        externalFrameReserved.set(true)
        return true
    }

    override fun cancelFrameReservation() {
        if (externalFrameReserved.compareAndSet(true, false)) processing.set(false)
    }

    override fun offerFrame(frame: ZoneTrackingFrame) {
        // A reservation was made on the camera thread before it copied the Y plane. Consuming it
        // here transfers the single processing slot to the OpenCV worker.
        if (!externalFrameReserved.compareAndSet(true, false)) {
            frame.close()
            return
        }
        if (!running || frame.width <= 0 || frame.height <= 0 ||
            frame.luma.size < frame.width * frame.height
        ) {
            frame.close()
            processing.set(false)
            return
        }
        if (externalFramesDisabled) {
            frame.close()
            processing.set(false)
            return
        }
        val quality = ZoneFrameQualityEvaluator.evaluate(frame)
        if (!quality.usable) {
            if (!externalFrameQualityWarningLogged) {
                Log.w(
                    TAG,
                    "ignoring invalid tracking YUV range=${quality.range} " +
                        "stdDev=${"%.2f".format(quality.standardDeviation)}; " +
                        "using displayed preview fallback",
                )
                externalFrameQualityWarningLogged = true
            }
            frame.close()
            processing.set(false)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val previousExternalFrameAtMs = lastExternalFrameAtMs
        lastExternalFrameAtMs = now
        if (externalFramesSeen.get() && previousExternalFrameAtMs > 0L) {
            val frameGapMs = now - previousExternalFrameAtMs
            if (frameGapMs >= FORCE_REIDENTIFICATION_FRAME_GAP_MS &&
                synchronized(lock) { tracks.isNotEmpty() }
            ) {
                forceReidentificationRequested.set(true)
                Log.i(TAG, "tracking stream gap=${frameGapMs}ms; forcing marker reidentification")
            }
        }
        updateExternalFrameRotation(frame.clockwiseRotationDegrees)
        if (externalFramesSeen.compareAndSet(false, true)) {
            resetRequested.set(true)
            redetectRequested.set(true)
        }
        val rotated = frame.clockwiseRotationDegrees == 90 ||
            frame.clockwiseRotationDegrees == 270
        val orientedWidth = if (rotated) frame.height else frame.width
        val orientedHeight = if (rotated) frame.width else frame.height
        val (targetWidth, targetHeight) = framePreprocessor.targetSize(orientedWidth, orientedHeight)
        val prediction = gyroscopeMotion.consume(targetWidth, targetHeight, displayOriented = true)
        val revision = mappingRevision.get()
        try {
            worker.execute {
                try {
                    processLumaFrame(frame, prediction, revision)
                } catch (error: Throwable) {
                    Log.e(TAG, "OpenCV YUV tracking frame failed", error)
                    resetRequested.set(true)
                } finally {
                    frame.close()
                    processing.set(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            frame.close()
            processing.set(false)
        }
    }

    private fun updateExternalFrameRotation(clockwiseRotationDegrees: Int) {
        val normalizedRotation = ((clockwiseRotationDegrees % 360) + 360) % 360
        val previousRotation = lastExternalFrameRotationDegrees
        lastExternalFrameRotationDegrees = normalizedRotation
        if (previousRotation == UNKNOWN_FRAME_ROTATION || previousRotation == normalizedRotation) {
            return
        }
        val clockwiseDelta = (normalizedRotation - previousRotation + 360) % 360
        if (clockwiseDelta == 0) return

        mappingRevision.incrementAndGet()
        synchronized(lock) {
            tracks.values.forEach { track ->
                // ZoneOpenCvFramePreprocessor has already rotated YUV into display coordinates.
                // Preserve UI-relative anchors here; rotating them again caused the visible jump.
                track.trackingState = ZoneTrackingState.UNCERTAIN
                track.misses = 0
                track.releaseReference()
            }
        }
        resetRequested.set(true)
        redetectRequested.set(true)
        gyroscopeMotion.resetAccumulation()
        publishCurrentPositions()
        Log.i(
            TAG,
            "tracking frame rotation $previousRotation->$normalizedRotation " +
                "delta=$clockwiseDelta preservedDisplayAnchors=" +
                "${synchronized(lock) { tracks.size }}",
        )
    }

    override fun setVisibleViewport(left: Float, top: Float, right: Float, bottom: Float) {
        val updated = VisibleViewport(
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f),
        )
        val previous = visibleViewport
        if (abs(updated.left - previous.left) < 0.0001f &&
            abs(updated.top - previous.top) < 0.0001f &&
            abs(updated.right - previous.right) < 0.0001f &&
            abs(updated.bottom - previous.bottom) < 0.0001f
        ) {
            return
        }
        if (running) {
            synchronized(lock) {
                tracks.values.forEach { track ->
                    val textureX = previous.left + track.baseX * previous.width
                    val textureY = previous.top + track.baseY * previous.height
                    track.baseX = ((textureX - updated.left) / updated.width)
                        .coerceIn(MIN_VIRTUAL_COORDINATE, MAX_VIRTUAL_COORDINATE)
                    track.baseY = ((textureY - updated.top) / updated.height)
                        .coerceIn(MIN_VIRTUAL_COORDINATE, MAX_VIRTUAL_COORDINATE)
                }
            }
        }
        visibleViewport = updated
        resetRequested.set(true)
    }

    private fun requestImmediateFrame() {
        if (!running) return
        if (SystemClock.elapsedRealtime() - lastExternalFrameAtMs < EXTERNAL_FRAME_TIMEOUT_MS) return
        val capture = Runnable {
            if (!running) return@Runnable
            mainHandler.removeCallbacks(tick)
            captureFrame()
            mainHandler.postDelayed(tick, nextFrameIntervalMs())
        }
        if (Looper.myLooper() == Looper.getMainLooper()) capture.run() else mainHandler.post(capture)
    }

    private fun captureReferenceFromLastProcessedFrame() {
        if (!running) return
        try {
            worker.execute {
                if (running && !previousGray.empty()) {
                    captureMissingReentryReferences(previousGray)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Tracker is being released; no reference is needed anymore.
        }
    }

    private fun captureFrame() {
        if (SystemClock.elapsedRealtime() - lastExternalFrameAtMs < EXTERNAL_FRAME_TIMEOUT_MS) return
        if (!running || processing.getAndSet(true)) return
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (!textureView.isAvailable || viewWidth <= 0 || viewHeight <= 0) {
            processing.set(false)
            return
        }
        // Preserve the displayed TextureView aspect exactly. OpenCV therefore observes the same
        // already-oriented coordinate system in which the Zone markers are drawn.
        val (targetWidth, targetHeight) = framePreprocessor.targetSize(viewWidth, viewHeight)
        val bitmap = captureBitmap?.takeIf {
            it.width == targetWidth && it.height == targetHeight && !it.isRecycled
        } ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
            captureBitmap?.recycle()
            captureBitmap = it
            resetRequested.set(true)
        }
        val captured = try {
            textureView.getBitmap(bitmap)
        } catch (_: RuntimeException) {
            null
        }
        if (captured == null) {
            processing.set(false)
            return
        }
        val prediction = gyroscopeMotion.consume(targetWidth, targetHeight, displayOriented = false)
        val revision = mappingRevision.get()
        worker.execute {
            try {
                processBitmap(captured, prediction, revision)
            } catch (error: Throwable) {
                Log.e(TAG, "OpenCV tracking frame failed", error)
                resetRequested.set(true)
            } finally {
                processing.set(false)
            }
        }
    }

    private fun processBitmap(
        bitmap: Bitmap,
        prediction: MotionPrediction,
        revision: Int,
    ) {
        if (revision != mappingRevision.get()) return
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val coordinateRevision = prepareFrameCoordinateSpace(displayOriented = false)
        processGrayFrame(prediction, coordinateRevision)
    }

    private fun processLumaFrame(
        frame: ZoneTrackingFrame,
        prediction: MotionPrediction,
        revision: Int,
    ) {
        if (revision != mappingRevision.get()) return
        framePreprocessor.copyToGray(frame, gray)
        val coordinateRevision = prepareFrameCoordinateSpace(displayOriented = true)
        processGrayFrame(prediction, coordinateRevision)
    }

    /**
     * Prevent optical flow from comparing frames whose axes use different coordinate systems.
     * Only the transition frame is discarded; anchors stay in UI coordinates and all subsequent
     * tracking keeps the source's original, device-verified motion mapping.
     */
    private fun prepareFrameCoordinateSpace(displayOriented: Boolean): Int {
        val updated = FrameCoordinateSpace(
            displayOriented = displayOriented,
            displayRotationDegrees = if (displayOriented) 0 else displayRotationDegrees(),
        )
        val previous = processedFrameCoordinateSpace
        frameCoordinatesAreDisplayOriented = displayOriented
        processedFrameCoordinateSpace = updated
        if (previous == null || previous == updated) return mappingRevision.get()

        val revision = mappingRevision.incrementAndGet()
        synchronized(lock) {
            tracks.values.forEach { track ->
                track.trackingState = ZoneTrackingState.UNCERTAIN
                track.misses = 0
                track.releaseReference()
            }
        }
        resetRequested.set(true)
        redetectRequested.set(true)
        forceReidentificationRequested.set(false)
        gyroscopeMotion.resetAccumulation()
        Log.i(TAG, "analysis coordinates $previous->$updated; reset transition frame")
        return revision
    }

    private fun processGrayFrame(
        prediction: MotionPrediction,
        revision: Int,
    ) {
        processedFrameNumber += 1
        val frameMeanLuma = Core.mean(gray).`val`[0]
        val previousMeanLuma = lastFrameMeanLuma
        lastFrameMeanLuma = frameMeanLuma
        if (previousMeanLuma.isFinite()) {
            val lumaDelta = abs(frameMeanLuma - previousMeanLuma)
            val relativeDelta = lumaDelta / max(24.0, previousMeanLuma)
            if (lumaDelta >= EXPOSURE_JUMP_MIN_LUMA &&
                relativeDelta >= EXPOSURE_JUMP_MIN_RATIO
            ) {
                exposureRecoveryFramesRemaining.updateAndGet { remaining ->
                    max(remaining, EXPOSURE_RECOVERY_FRAMES)
                }
                resetRequested.set(true)
                redetectRequested.set(true)
                Log.i(
                    TAG,
                    "ISP exposure jump ${"%.1f".format(previousMeanLuma)}->" +
                        "${"%.1f".format(frameMeanLuma)}; holding marker constellation",
                )
            }
        }
        val recoveringExposure = exposureRecoveryFramesRemaining.getAndUpdate { remaining ->
            (remaining - 1).coerceAtLeast(0)
        } > 0
        val exposureUnstable = meteringActive.get() || recoveringExposure
        if (!exposureUnstable) captureMissingReentryReferences(gray)
        val forceRequested = forceReidentificationRequested.getAndSet(false)
        if (forceRequested && exposureUnstable) forceReidentificationRequested.set(true)
        val forceReidentification = forceRequested && !exposureUnstable
        if (forceReidentification && revision == mappingRevision.get()) {
            val recoveryMotion = gyroscopeMotion.affine(
                prediction,
                gray.cols(),
                gray.rows(),
                trustedTranslationOnly = true,
            )
            val reentryFeatures = detectReentryFrameFeatures(gray, force = true)
            val recoveryUpdates = try {
                updateMarkers(
                    emptyList(),
                    recoveryMotion,
                    prediction,
                    gray.cols(),
                    gray.rows(),
                    reentryFeatures,
                    forceReidentification = true,
                    exposureUnstable = false,
                )
            } finally {
                reentryFeatures?.release()
            }
            dispatchUpdates(recoveryUpdates, revision)
            reportTrackingStats(0, recoveryMotion, recoveryUpdates)
            resetFlowFrom(gray)
            return
        }
        val stabilizing = stabilizationFramesRemaining.getAndUpdate { remaining ->
            (remaining - 1).coerceAtLeast(0)
        } > 0
        if (revision != mappingRevision.get()) return
        if (resetRequested.getAndSet(false) || stabilizing ||
            previousGray.empty() ||
            previousGray.rows() != gray.rows() ||
            previousGray.cols() != gray.cols()
        ) {
            resetFlowFrom(gray)
            return
        }

        val oldPoints = previousPoints.toArray()
        if (oldPoints.size < MIN_FLOW_POINTS || previousOwners.size != oldPoints.size) {
            val fallbackMotion = gyroscopeMotion.affine(
                prediction,
                gray.cols(),
                gray.rows(),
                trustedTranslationOnly = true,
            )
            val reentryFeatures = if (exposureUnstable) {
                null
            } else {
                detectReentryFrameFeatures(gray)
            }
            val fallbackUpdates = try {
                updateMarkers(
                    emptyList(),
                    fallbackMotion,
                    prediction,
                    gray.cols(),
                    gray.rows(),
                    reentryFeatures,
                    exposureUnstable = exposureUnstable,
                )
            } finally {
                reentryFeatures?.release()
            }
            dispatchUpdates(fallbackUpdates, revision)
            reportTrackingStats(0, fallbackMotion, fallbackUpdates)
            resetFlowFrom(gray)
            return
        }

        val predicted = oldPoints.map { point ->
            gyroscopeMotion.map(point, prediction, gray.cols(), gray.rows())
        }
        val forwardPoints = MatOfPoint2f(*predicted.toTypedArray())
        val forwardStatus = MatOfByte()
        val forwardError = MatOfFloat()
        Video.calcOpticalFlowPyrLK(
            previousGray,
            gray,
            previousPoints,
            forwardPoints,
            forwardStatus,
            forwardError,
            LK_WINDOW,
            LK_LEVELS,
            LK_CRITERIA,
            Video.OPTFLOW_USE_INITIAL_FLOW,
            MIN_EIGEN_THRESHOLD,
        )

        val backwardPoints = MatOfPoint2f()
        val backwardStatus = MatOfByte()
        val backwardError = MatOfFloat()
        Video.calcOpticalFlowPyrLK(
            gray,
            previousGray,
            forwardPoints,
            backwardPoints,
            backwardStatus,
            backwardError,
            LK_WINDOW,
            LK_LEVELS,
            LK_CRITERIA,
            0,
            MIN_EIGEN_THRESHOLD,
        )

        val nextPoints = forwardPoints.toArray()
        val returnedPoints = backwardPoints.toArray()
        val forwardFlags = forwardStatus.toArray()
        val backwardFlags = backwardStatus.toArray()
        val valid = ArrayList<ValidFlow>(oldPoints.size)
        for (index in oldPoints.indices) {
            if (index >= nextPoints.size || index >= returnedPoints.size ||
                index >= forwardFlags.size || index >= backwardFlags.size ||
                forwardFlags[index].toInt() == 0 || backwardFlags[index].toInt() == 0
            ) {
                continue
            }
            val next = nextPoints[index]
            val back = returnedPoints[index]
            if (!next.inside(gray.cols(), gray.rows())) continue
            if (distance(oldPoints[index], back) > FORWARD_BACKWARD_LIMIT) continue
            valid += ValidFlow(oldPoints[index], next, previousOwners[index])
        }

        val affine = estimateMotion(valid, prediction, gray.cols(), gray.rows())
        if (revision != mappingRevision.get()) {
            releaseFlowMats(
                forwardPoints,
                forwardStatus,
                forwardError,
                backwardPoints,
                backwardStatus,
                backwardError,
            )
            resetRequested.set(true)
            return
        }
        val reentryFeatures = if (exposureUnstable) {
            null
        } else {
            detectReentryFrameFeatures(gray)
        }
        val updates = try {
            updateMarkers(
                valid,
                affine,
                prediction,
                gray.cols(),
                gray.rows(),
                reentryFeatures,
                exposureUnstable = exposureUnstable,
            )
        } finally {
            reentryFeatures?.release()
        }
        dispatchUpdates(updates, revision)
        reportTrackingStats(valid.size, affine, updates)

        framesUntilRedetect -= 1
        val globalFlowCount = valid.count { it.ownerId == GLOBAL_OWNER }
        val shouldRedetect = redetectRequested.getAndSet(false) ||
            framesUntilRedetect <= 0 || valid.size < REDTECT_WHEN_BELOW ||
            globalFlowCount < REDETECT_GLOBAL_WHEN_BELOW
        if (shouldRedetect) {
            detectFeatures(gray)
        } else {
            previousPoints.fromArray(*valid.map(ValidFlow::current).toTypedArray())
            previousOwners = valid.map(ValidFlow::ownerId).toIntArray()
        }
        gray.copyTo(previousGray)

        releaseFlowMats(
            forwardPoints,
            forwardStatus,
            forwardError,
            backwardPoints,
            backwardStatus,
            backwardError,
        )
    }

    private fun resetFlowFrom(currentGray: Mat) {
        detectFeatures(currentGray)
        currentGray.copyTo(previousGray)
        redetectRequested.set(false)
    }

    private fun Track.releaseReference() {
        referenceDescriptors?.release()
        referenceDescriptors = null
        referenceAnchor = null
        referenceKeypoints = emptyList()
        nextReferenceCaptureFrame = 0L
    }

    /**
     * Preserve an appearance signature near the marker. Optical flow cannot reconnect an object
     * after it has fully left the frame; this descriptor constellation gives the tracker an
     * identity to search for when the scene returns.
     */
    private fun captureMissingReentryReferences(image: Mat) {
        if (image.empty()) return
        val viewport = visibleViewport
        val candidates = synchronized(lock) {
            tracks.values.filter {
                it.referenceDescriptors == null &&
                    it.trackingState != ZoneTrackingState.LOST &&
                    processedFrameNumber >= it.nextReferenceCaptureFrame
            }.map { track ->
                track.nextReferenceCaptureFrame = processedFrameNumber + REENTRY_REFERENCE_RETRY_FRAMES
                track.id to (track.baseX to track.baseY)
            }
        }
        if (candidates.isEmpty()) return

        // ORB is the expensive part of reference capture. Detect it once for the frame and let
        // every missing marker retain only the nearby rows. The previous per-marker masked ORB
        // pass made adding many dots progressively slower and stored hundreds of redundant
        // descriptors for every marker.
        val frameKeypoints = MatOfKeyPoint()
        val frameDescriptors = Mat()
        val emptyMask = Mat()
        try {
            reentryOrb.detectAndCompute(image, emptyMask, frameKeypoints, frameDescriptors)
            val keypoints = frameKeypoints.toArray()
            if (frameDescriptors.empty() || frameDescriptors.rows() != keypoints.size) return
            candidates.forEach { (id, coordinates) ->
                val center = basePreviewToTexture(
                    coordinates.first,
                    coordinates.second,
                    image.cols(),
                    image.rows(),
                    viewport,
                )
                if (!center.insideWithMargin(
                        image.cols(),
                        image.rows(),
                        REENTRY_REFERENCE_RADIUS,
                    )
                ) {
                    return@forEach
                }
                val selected = keypoints.indices
                    .asSequence()
                    .filter { index -> distance(keypoints[index].pt, center) <= REENTRY_REFERENCE_RADIUS }
                    .sortedByDescending { index -> keypoints[index].response }
                    .take(MAX_REENTRY_REFERENCE_FEATURES)
                    .toList()
                if (selected.size < MIN_REENTRY_REFERENCE_FEATURES) return@forEach
                val storedDescriptors = Mat(
                    selected.size,
                    frameDescriptors.cols(),
                    frameDescriptors.type(),
                )
                val descriptorBytes = ByteArray(frameDescriptors.cols())
                selected.forEachIndexed { targetRow, sourceRow ->
                    frameDescriptors.get(sourceRow, 0, descriptorBytes)
                    storedDescriptors.put(targetRow, 0, descriptorBytes)
                }
                val storedPoints = selected.map { index -> keypoints[index].pt }
                synchronized(lock) {
                    val track = tracks[id]
                    if (track != null && track.referenceDescriptors == null) {
                        track.referenceAnchor = center
                        track.referenceKeypoints = storedPoints
                        track.referenceDescriptors = storedDescriptors
                        Log.i(
                            TAG,
                            "marker $id shared reference features=${storedPoints.size} " +
                                "frameFeatures=${keypoints.size}",
                        )
                    } else {
                        storedDescriptors.release()
                    }
                }
            }
        } finally {
            emptyMask.release()
            frameDescriptors.release()
            frameKeypoints.release()
        }
    }

    private fun detectReentryFrameFeatures(
        image: Mat,
        force: Boolean = false,
    ): ReentryFrameFeatures? {
        if (!force && processedFrameNumber % REENTRY_SEARCH_INTERVAL_FRAMES != 0L) return null
        val needed = synchronized(lock) {
            tracks.values.any {
                it.referenceDescriptors?.empty() == false &&
                    (force || it.trackingState == ZoneTrackingState.LOST)
            }
        }
        if (!needed) return null
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val emptyMask = Mat()
        return try {
            reentryOrb.detectAndCompute(image, emptyMask, keypoints, descriptors)
            val points = keypoints.toArray().map { it.pt }.toTypedArray()
            if (points.size < MIN_REENTRY_FRAME_FEATURES || descriptors.empty()) {
                descriptors.release()
                null
            } else {
                ReentryFrameFeatures(points, descriptors)
            }
        } finally {
            emptyMask.release()
            keypoints.release()
        }
    }

    private fun dispatchUpdates(updates: List<Update>, revision: Int = mappingRevision.get()) {
        if (updates.isEmpty()) return
        val publish = Runnable {
            if (!running || revision != mappingRevision.get()) return@Runnable
            val viewport = visibleViewport
            updates.forEach { update ->
                val (uiX, uiY) = basePreviewToUiPreview(
                    update.baseX,
                    update.baseY,
                    viewport,
                )
                callback(update.id, uiX, uiY, update.state)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) publish.run() else mainHandler.post(publish)
    }

    private fun publishCurrentPositions() {
        val updates = synchronized(lock) {
            tracks.values.map { track ->
                Update(track.id, track.baseX, track.baseY, track.trackingState)
            }
        }
        dispatchUpdates(updates)
    }

    private fun releaseFlowMats(vararg mats: Mat) {
        mats.forEach(Mat::release)
    }

    private fun reportTrackingStats(
        flowCount: Int,
        motion: AffineMotion,
        updates: List<Update>,
    ) {
        statsFrames += 1
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - statsStartedAtMs
        if (elapsed < TRACKING_STATS_INTERVAL_MS) return
        val fps = statsFrames * 1000f / elapsed.coerceAtLeast(1L)
        val tracked = updates.count { it.state == ZoneTrackingState.TRACKED }
        val uncertain = updates.count { it.state == ZoneTrackingState.UNCERTAIN }
        val lost = updates.count { it.state == ZoneTrackingState.LOST }
        val gyroCalibration = gyroscopeMotion.snapshot(gray.cols(), gray.rows())
        val gyroHorizontal = gyroCalibration.horizontalScale?.let { "%.3f".format(it) } ?: "-"
        val gyroVertical = gyroCalibration.verticalScale?.let { "%.3f".format(it) } ?: "-"
        Log.i(
            TAG,
            "fps=${"%.1f".format(fps)} flow=$flowCount inliers=${motion.inlierCount} " +
                "reliable=${motion.reliable} markers=${updates.size} " +
                "tracked=$tracked uncertain=$uncertain lost=$lost " +
                "gyroCal=x:$gyroHorizontal/${gyroCalibration.horizontalSamples} " +
                "y:$gyroVertical/${gyroCalibration.verticalSamples}",
        )
        statsStartedAtMs = now
        statsFrames = 0
    }

    private fun detectFeatures(image: Mat) {
        val points = ArrayList<Point>()
        val owners = ArrayList<Int>()
        val globalCorners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(
            image,
            globalCorners,
            tuning.globalFeatureCount,
            FEATURE_QUALITY,
            GLOBAL_FEATURE_DISTANCE,
        )
        globalCorners.toArray().forEach { point ->
            points += point
            owners += GLOBAL_OWNER
        }
        globalCorners.release()

        val viewport = visibleViewport
        val markerSnapshot = synchronized(lock) {
            tracks.values.filter { it.trackingState != ZoneTrackingState.LOST }.map { it.copy() }
        }
        val localFeaturesPerMarker = if (markerSnapshot.isEmpty()) {
            0
        } else {
            (MAX_TOTAL_LOCAL_FLOW_FEATURES / markerSnapshot.size)
                .coerceIn(MIN_LOCAL_FLOW_FEATURES_PER_MARKER, tuning.localFeaturesPerMarker)
        }
        markerSnapshot.forEach { track ->
            val center = basePreviewToTexture(track.baseX, track.baseY, image.cols(), image.rows(), viewport)
            if (!center.insideWithMargin(image.cols(), image.rows(), LOCAL_FEATURE_RADIUS)) {
                return@forEach
            }
            val mask = Mat.zeros(image.size(), CvType.CV_8UC1)
            Imgproc.circle(mask, center, LOCAL_FEATURE_RADIUS, Scalar(255.0), -1)
            val localCorners = MatOfPoint()
            Imgproc.goodFeaturesToTrack(
                image,
                localCorners,
                localFeaturesPerMarker,
                FEATURE_QUALITY,
                LOCAL_FEATURE_DISTANCE,
                mask,
                3,
                false,
                0.04,
            )
            localCorners.toArray().forEach { point ->
                points += point
                owners += track.id
            }
            localCorners.release()
            mask.release()
        }
        previousPoints.fromArray(*points.toTypedArray())
        previousOwners = owners.toIntArray()
        framesUntilRedetect = tuning.featureRefreshFrames
        if (frameCoordinatesAreDisplayOriented && points.size < MIN_FLOW_POINTS) {
            emptyExternalFeatureFrames += 1
            if (emptyExternalFeatureFrames >= MAX_EMPTY_EXTERNAL_FEATURE_FRAMES) {
                disableExternalFramesForSession(points.size)
            }
        } else {
            emptyExternalFeatureFrames = 0
        }
    }

    private fun disableExternalFramesForSession(featureCount: Int) {
        if (externalFramesDisabled) return
        externalFramesDisabled = true
        externalFramesSeen.set(false)
        lastExternalFrameAtMs = 0L
        resetRequested.set(true)
        Log.w(
            TAG,
            "physical tracking YUV produced $featureCount features for " +
                "$emptyExternalFeatureFrames frames; disabling it for this Zone session " +
                "and using displayed preview fallback",
        )
        requestImmediateFrame()
    }

    private fun estimateMotion(
        valid: MutableList<ValidFlow>,
        prediction: MotionPrediction,
        width: Int,
        height: Int,
    ): AffineMotion {
        // Marker-owned corners describe local parallax and moving subjects. Letting their count
        // grow with the marker count used to overwhelm the fixed global set and bend the camera
        // motion model toward whichever object had the most dots.
        val globalFlows = valid.filter { it.ownerId == GLOBAL_OWNER }
        val motionFlows = if (globalFlows.size >= MIN_RANSAC_POINTS) globalFlows else valid
        if (motionFlows.size < MIN_RANSAC_POINTS) {
            return gyroscopeMotion.affine(prediction, width, height, trustedTranslationOnly = true)
        }
        val source = MatOfPoint2f(*motionFlows.map(ValidFlow::previous).toTypedArray())
        val destination = MatOfPoint2f(*motionFlows.map(ValidFlow::current).toTypedArray())
        val inlierMask = Mat()
        val matrix = Calib3d.estimateAffinePartial2D(
            source,
            destination,
            inlierMask,
            Calib3d.RANSAC,
            RANSAC_REPROJECTION_ERROR,
            RANSAC_MAX_ITERATIONS,
            RANSAC_CONFIDENCE,
            RANSAC_REFINE_ITERATIONS,
        )
        val inlierBytes = ByteArray(inlierMask.total().toInt())
        if (inlierBytes.isNotEmpty()) inlierMask.get(0, 0, inlierBytes)
        var inlierCount = 0
        motionFlows.forEachIndexed { index, flow ->
            flow.inlier = index < inlierBytes.size && inlierBytes[index].toInt() != 0
            if (flow.inlier) inlierCount += 1
        }
        val reliable = !matrix.empty() &&
            inlierCount >= MIN_RANSAC_INLIERS &&
            inlierCount.toDouble() / motionFlows.size >= MIN_INLIER_RATIO
        val result = if (reliable) {
            AffineMotion(
                matrix.get(0, 0)[0], matrix.get(0, 1)[0], matrix.get(0, 2)[0],
                matrix.get(1, 0)[0], matrix.get(1, 1)[0], matrix.get(1, 2)[0],
                true,
                inlierCount,
            ).also { visualMotion ->
                gyroscopeMotion.updateCalibration(
                    visualMotion,
                    prediction,
                    width,
                    height,
                    minimumInliers = STRONG_GLOBAL_INLIERS,
                )
            }
        } else {
            gyroscopeMotion.affine(prediction, width, height, trustedTranslationOnly = true)
        }
        source.release()
        destination.release()
        inlierMask.release()
        matrix.release()
        return result
    }

    private fun updateMarkers(
        valid: List<ValidFlow>,
        motion: AffineMotion,
        prediction: MotionPrediction,
        width: Int,
        height: Int,
        reentryFeatures: ReentryFrameFeatures?,
        forceReidentification: Boolean = false,
        exposureUnstable: Boolean = false,
    ): List<Update> {
        val viewport = visibleViewport
        val updates = ArrayList<Update>()
        synchronized(lock) {
            val localSamples = valid.asSequence()
                .filter { it.ownerId != GLOBAL_OWNER }
                .groupBy(ValidFlow::ownerId)
            val localEvidence = tracks.values.mapNotNull { track ->
                val samples = localSamples[track.id].orEmpty()
                if (samples.size < MIN_LOCAL_SUPPORT) return@mapNotNull null
                val rawX = samples.map { it.current.x - motion.map(it.previous).x }
                val rawY = samples.map { it.current.y - motion.map(it.previous).y }
                val medianX = rawX.median()
                val medianY = rawY.median()
                val consistent = samples.indices.filter { index ->
                    kotlin.math.hypot(rawX[index] - medianX, rawY[index] - medianY) <=
                        LOCAL_RESIDUAL_LIMIT
                }
                if (consistent.size < MIN_LOCAL_SUPPORT) return@mapNotNull null
                val consistentDx = consistent.map(rawX::get)
                val consistentDy = consistent.map(rawY::get)
                val resolvedMedianX = consistentDx.median()
                val resolvedMedianY = consistentDy.median()
                track.id to LocalEvidence(
                    resolvedMedianX,
                    resolvedMedianY,
                    consistent.size,
                    consistent.indices.map { index ->
                        kotlin.math.hypot(
                            consistentDx[index] - resolvedMedianX,
                            consistentDy[index] - resolvedMedianY,
                        )
                    }.median(),
                )
            }.toMap()
            // Only stable, well-supported marker clusters may steer the constellation. A weak
            // point with two accidental corners must never pull itself away from the other dots.
            val constellationEvidence = localEvidence.values.filter { evidence ->
                evidence.support >= MIN_CONSTELLATION_SUPPORT &&
                    evidence.dispersion <= MAX_CONSTELLATION_DISPERSION
            }
            val sharedEvidence = constellationEvidence.takeIf { it.isNotEmpty() }?.let { evidence ->
                LocalEvidence(
                    evidence.map(LocalEvidence::dx).median(),
                    evidence.map(LocalEvidence::dy).median(),
                    evidence.sumOf(LocalEvidence::support),
                    evidence.map(LocalEvidence::dispersion).median(),
                )
            }
            val reentryMatches = if (!exposureUnstable && reentryFeatures != null) {
                val candidates = tracks.values
                    .filter { track ->
                        track.referenceDescriptors?.empty() == false &&
                            (forceReidentification || track.trackingState == ZoneTrackingState.LOST)
                    }
                    .sortedByDescending { it.referenceKeypoints.size }
                val selected = if (candidates.size <= REENTRY_MATCH_BUDGET_PER_FRAME) {
                    candidates
                } else if (forceReidentification) {
                    candidates.take(REENTRY_MATCH_BUDGET_PER_FRAME)
                } else {
                    val offset = ((processedFrameNumber / REENTRY_SEARCH_INTERVAL_FRAMES) %
                        candidates.size).toInt()
                    List(REENTRY_MATCH_BUDGET_PER_FRAME) { index ->
                        candidates[(offset + index) % candidates.size]
                    }
                }
                selected.mapNotNull { track ->
                    findReentryMatch(track, reentryFeatures, width, height)?.let { match ->
                        track.id to match
                    }
                }.toMap()
            } else {
                emptyMap()
            }
            val reentryResiduals = reentryMatches.mapNotNull { (id, match) ->
                val track = tracks[id] ?: return@mapNotNull null
                val oldPoint = basePreviewToTexture(
                    track.baseX,
                    track.baseY,
                    width,
                    height,
                    viewport,
                )
                val predictedPoint = motion.map(oldPoint)
                (match.point.x - predictedPoint.x) to (match.point.y - predictedPoint.y)
            }
            val sharedReentry = if (reentryResiduals.size >= MIN_SHARED_REENTRY_ANCHORS) {
                val medianX = reentryResiduals.map(Pair<Double, Double>::first).median()
                val medianY = reentryResiduals.map(Pair<Double, Double>::second).median()
                val consistent = reentryResiduals.filter { residual ->
                    kotlin.math.hypot(residual.first - medianX, residual.second - medianY) <=
                        SHARED_REENTRY_RESIDUAL_LIMIT
                }
                if (consistent.size >= MIN_SHARED_REENTRY_ANCHORS) {
                    SharedReentryCorrection(
                        dx = consistent.map(Pair<Double, Double>::first).median(),
                        dy = consistent.map(Pair<Double, Double>::second).median(),
                        support = consistent.size,
                    )
                } else {
                    null
                }
            } else {
                null
            }
            if (sharedReentry != null) {
                Log.i(
                    TAG,
                    "constellation reidentified anchors=${sharedReentry.support} " +
                        "markers=${tracks.size}",
                )
            }
            tracks.values.forEach { track ->
                val wasLost = track.trackingState == ZoneTrackingState.LOST
                val oldPoint = basePreviewToTexture(track.baseX, track.baseY, width, height, viewport)
                val motionMapped = motion.map(oldPoint)
                var mapped = motionMapped
                val candidateLocal = localEvidence[track.id]
                val local = candidateLocal?.takeIf { evidence ->
                    !exposureUnstable &&
                        evidence.support >= MIN_CONSTELLATION_SUPPORT &&
                        evidence.dispersion <= MAX_CONSTELLATION_DISPERSION &&
                        (sharedEvidence == null || kotlin.math.hypot(
                            evidence.dx - sharedEvidence.dx,
                            evidence.dy - sharedEvidence.dy,
                        ) <= MAX_LOCAL_CONSTELLATION_DEVIATION)
                }
                val stableSharedEvidence = sharedEvidence.takeUnless { exposureUnstable }
                val correction = when {
                    local != null && stableSharedEvidence != null -> LocalEvidence(
                        dx = local.dx * LOCAL_CORRECTION_WEIGHT +
                            stableSharedEvidence.dx * (1.0 - LOCAL_CORRECTION_WEIGHT),
                        dy = local.dy * LOCAL_CORRECTION_WEIGHT +
                            stableSharedEvidence.dy * (1.0 - LOCAL_CORRECTION_WEIGHT),
                        support = local.support,
                        dispersion = max(local.dispersion, stableSharedEvidence.dispersion),
                    )
                    local != null -> local
                    stableSharedEvidence != null -> stableSharedEvidence
                    else -> null
                }
                if (correction != null) {
                    mapped = Point(
                        mapped.x + correction.dx.coerceIn(-MAX_LOCAL_CORRECTION, MAX_LOCAL_CORRECTION),
                        mapped.y + correction.dy.coerceIn(-MAX_LOCAL_CORRECTION, MAX_LOCAL_CORRECTION),
                    )
                }
                val constellationRecovery = sharedReentry?.takeIf {
                    wasLost || forceReidentification
                }
                if (constellationRecovery != null) {
                    mapped = Point(
                        motionMapped.x + constellationRecovery.dx,
                        motionMapped.y + constellationRecovery.dy,
                    )
                }
                val reentry = reentryMatches[track.id]?.takeIf { match ->
                    val residualX = match.point.x - motionMapped.x
                    val residualY = match.point.y - motionMapped.y
                    sharedReentry == null || kotlin.math.hypot(
                        residualX - sharedReentry.dx,
                        residualY - sharedReentry.dy,
                    ) <= SHARED_REENTRY_RESIDUAL_LIMIT
                }
                if (reentry != null) {
                    mapped = reentry.point
                    redetectRequested.set(true)
                    Log.i(TAG, "marker ${track.id} reidentified inliers=${reentry.inlierCount}")
                }
                val (nextBaseX, nextBaseY) = textureToBasePreview(
                    mapped,
                    width,
                    height,
                    viewport,
                )
                val (nextUiX, nextUiY) = basePreviewToUiPreview(
                    nextBaseX,
                    nextBaseY,
                    viewport,
                )
                val insidePreview = nextUiX in 0f..1f && nextUiY in 0f..1f
                val visualSupport = motion.reliable || local != null ||
                    stableSharedEvidence != null || constellationRecovery != null
                val trustedGyroMotion =
                    (prediction.xTranslationTrusted && abs(prediction.dx) > 0.002f) ||
                        (prediction.yTranslationTrusted && abs(prediction.dy) > 0.002f) ||
                        abs(prediction.rollRadians) > 0.002f
                val trackingState = when {
                    !insidePreview -> ZoneTrackingState.LOST
                    reentry != null -> ZoneTrackingState.TRACKED
                    constellationRecovery != null &&
                        constellationRecovery.support >= STRONG_SHARED_REENTRY_ANCHORS ->
                        ZoneTrackingState.TRACKED
                    constellationRecovery != null -> ZoneTrackingState.UNCERTAIN
                    wasLost -> ZoneTrackingState.LOST
                    exposureUnstable -> ZoneTrackingState.UNCERTAIN
                    local != null -> ZoneTrackingState.TRACKED
                    motion.reliable && motion.inlierCount >= STRONG_GLOBAL_INLIERS ->
                        ZoneTrackingState.TRACKED
                    forceReidentification -> ZoneTrackingState.UNCERTAIN
                    visualSupport || trustedGyroMotion -> ZoneTrackingState.UNCERTAIN
                    else -> ZoneTrackingState.LOST
                }
                // Keep advancing a virtual off-screen position whenever any motion source is
                // available. Clamping here used to destroy the location and made re-entry
                // practically impossible.
                if (visualSupport || trustedGyroMotion || reentry != null) {
                    track.baseX = nextBaseX.coerceIn(MIN_VIRTUAL_COORDINATE, MAX_VIRTUAL_COORDINATE)
                    track.baseY = nextBaseY.coerceIn(MIN_VIRTUAL_COORDINATE, MAX_VIRTUAL_COORDINATE)
                }
                if (trackingState == ZoneTrackingState.LOST) {
                    track.misses += 1
                } else {
                    track.misses = 0
                }
                track.trackingState = trackingState
                updates += Update(track.id, track.baseX, track.baseY, trackingState)
            }
        }
        return updates
    }

    private fun findReentryMatch(
        track: Track,
        frame: ReentryFrameFeatures,
        width: Int,
        height: Int,
    ): ReentryMatch? {
        val referenceDescriptors = track.referenceDescriptors ?: return null
        val referenceAnchor = track.referenceAnchor ?: return null
        if (referenceDescriptors.empty() || track.referenceKeypoints.isEmpty() ||
            frame.descriptors.empty() || frame.keypoints.isEmpty()
        ) {
            return null
        }
        val neighborMatches = ArrayList<MatOfDMatch>()
        val goodMatches = ArrayList<org.opencv.core.DMatch>()
        try {
            reentryMatcher.knnMatch(referenceDescriptors, frame.descriptors, neighborMatches, 2)
            neighborMatches.forEach { neighbors ->
                val pair = neighbors.toArray()
                if (pair.size >= 2 && pair[0].distance < REENTRY_LOWE_RATIO * pair[1].distance) {
                    goodMatches += pair[0]
                }
            }
            if (goodMatches.size < MIN_REENTRY_GOOD_MATCHES) return null

            val sourcePoints = ArrayList<Point>(goodMatches.size)
            val destinationPoints = ArrayList<Point>(goodMatches.size)
            goodMatches.forEach { match ->
                val source = track.referenceKeypoints.getOrNull(match.queryIdx)
                val destination = frame.keypoints.getOrNull(match.trainIdx)
                if (source != null && destination != null) {
                    sourcePoints += source
                    destinationPoints += destination
                }
            }
            if (sourcePoints.size < MIN_REENTRY_GOOD_MATCHES) return null
            val source = MatOfPoint2f(*sourcePoints.toTypedArray())
            val destination = MatOfPoint2f(*destinationPoints.toTypedArray())
            val inlierMask = Mat()
            val matrix = Calib3d.estimateAffinePartial2D(
                source,
                destination,
                inlierMask,
                Calib3d.RANSAC,
                REENTRY_RANSAC_REPROJECTION_ERROR,
                RANSAC_MAX_ITERATIONS,
                RANSAC_CONFIDENCE,
                RANSAC_REFINE_ITERATIONS,
            )
            return try {
                if (matrix.empty()) return null
                val inlierBytes = ByteArray(inlierMask.total().toInt())
                if (inlierBytes.isNotEmpty()) inlierMask.get(0, 0, inlierBytes)
                val inlierCount = inlierBytes.count { it.toInt() != 0 }
                if (inlierCount < MIN_REENTRY_INLIERS ||
                    inlierCount.toDouble() / sourcePoints.size < MIN_REENTRY_INLIER_RATIO
                ) {
                    return null
                }
                val m00 = matrix.get(0, 0)[0]
                val m01 = matrix.get(0, 1)[0]
                val m10 = matrix.get(1, 0)[0]
                val m11 = matrix.get(1, 1)[0]
                val scale = kotlin.math.hypot(m00, m10)
                if (scale !in MIN_REENTRY_SCALE..MAX_REENTRY_SCALE) return null
                val mapped = Point(
                    m00 * referenceAnchor.x + m01 * referenceAnchor.y + matrix.get(0, 2)[0],
                    m10 * referenceAnchor.x + m11 * referenceAnchor.y + matrix.get(1, 2)[0],
                )
                if (!mapped.inside(width, height)) return null
                ReentryMatch(mapped, inlierCount)
            } finally {
                matrix.release()
                inlierMask.release()
                source.release()
                destination.release()
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "marker ${track.id} reidentification failed", error)
            return null
        } finally {
            neighborMatches.forEach(MatOfDMatch::release)
        }
    }

    private fun basePreviewToTexture(
        baseX: Float,
        baseY: Float,
        width: Int,
        height: Int,
        viewport: VisibleViewport,
    ): Point = ZoneCoordinateMapper.basePreviewToTexture(
        baseX,
        baseY,
        width,
        height,
        viewport,
        frameCoordinatesAreDisplayOriented,
        displayRotationDegrees(),
    )

    private fun textureToBasePreview(
        point: Point,
        width: Int,
        height: Int,
        viewport: VisibleViewport,
    ): Pair<Float, Float> = ZoneCoordinateMapper.textureToBasePreview(
        point,
        width,
        height,
        viewport,
        frameCoordinatesAreDisplayOriented,
        displayRotationDegrees(),
    )

    private fun uiPreviewToBasePreview(
        uiX: Float,
        uiY: Float,
        viewport: VisibleViewport,
    ): Pair<Float, Float> = ZoneCoordinateMapper.uiPreviewToBasePreview(
        uiX,
        uiY,
        viewport,
        trackedDisplayZoom,
    )

    private fun basePreviewToUiPreview(
        baseX: Float,
        baseY: Float,
        viewport: VisibleViewport,
    ): Pair<Float, Float> = ZoneCoordinateMapper.basePreviewToUiPreview(
        baseX,
        baseY,
        viewport,
        trackedDisplayZoom,
    )

    private fun displayRotationDegrees(): Int =
        when (textureView.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    private fun releaseOpenCvState() {
        rgba.release()
        gray.release()
        previousGray.release()
        previousPoints.release()
        rgba = Mat()
        gray = Mat()
        previousGray = Mat()
        previousPoints = MatOfPoint2f()
        previousOwners = IntArray(0)
    }

    private fun nextFrameIntervalMs(): Long {
        val markerCount = synchronized(lock) { tracks.size }
        return (tuning.frameIntervalMs + markerCount * tuning.perMarkerIntervalMs)
            .coerceAtMost(tuning.maxFrameIntervalMs)
    }

    private fun Point.inside(width: Int, height: Int): Boolean =
        x >= 1.0 && y >= 1.0 && x < width - 1.0 && y < height - 1.0

    private fun Point.insideWithMargin(width: Int, height: Int, margin: Int): Boolean =
        x >= -margin && y >= -margin && x < width + margin && y < height + margin

    private fun distance(first: Point, second: Point): Double =
        kotlin.math.hypot(first.x - second.x, first.y - second.y)

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    companion object {
        private const val TAG = "ZoneOpenCvTracker"
        private const val GLOBAL_OWNER = -1
        private const val LOCAL_FEATURE_RADIUS = 46
        private const val FEATURE_QUALITY = 0.012
        private const val GLOBAL_FEATURE_DISTANCE = 9.0
        private const val LOCAL_FEATURE_DISTANCE = 5.0
        private const val MIN_FLOW_POINTS = 8
        private const val REDTECT_WHEN_BELOW = 45
        private const val REDETECT_GLOBAL_WHEN_BELOW = 28
        private const val MIN_RANSAC_POINTS = 6
        private const val MIN_RANSAC_INLIERS = 6
        private const val STRONG_GLOBAL_INLIERS = 14
        private const val MIN_LOCAL_SUPPORT = 2
        private const val MIN_CONSTELLATION_SUPPORT = 3
        private const val MIN_LOCAL_FLOW_FEATURES_PER_MARKER = 6
        private const val MAX_TOTAL_LOCAL_FLOW_FEATURES = 240
        private const val LOCAL_RESIDUAL_LIMIT = 5.5
        private const val MAX_CONSTELLATION_DISPERSION = 2.8
        private const val MAX_LOCAL_CONSTELLATION_DEVIATION = 6.0
        private const val LOCAL_CORRECTION_WEIGHT = 0.72
        private const val MIN_INLIER_RATIO = 0.38
        private const val FORWARD_BACKWARD_LIMIT = 1.8
        private const val RANSAC_REPROJECTION_ERROR = 2.6
        private const val RANSAC_MAX_ITERATIONS = 1200L
        private const val RANSAC_CONFIDENCE = 0.995
        private const val RANSAC_REFINE_ITERATIONS = 10L
        private const val MIN_EIGEN_THRESHOLD = 0.0001
        private const val MAX_LOCAL_CORRECTION = 22.0
        private const val REENTRY_FEATURE_COUNT = 700
        private const val REENTRY_REFERENCE_RADIUS = 72
        private const val MIN_REENTRY_REFERENCE_FEATURES = 8
        private const val MAX_REENTRY_REFERENCE_FEATURES = 96
        private const val REENTRY_REFERENCE_RETRY_FRAMES = 15L
        private const val MIN_REENTRY_FRAME_FEATURES = 20
        private const val REENTRY_SEARCH_INTERVAL_FRAMES = 4L
        private const val REENTRY_LOWE_RATIO = 0.74f
        private const val MIN_REENTRY_GOOD_MATCHES = 6
        private const val MIN_REENTRY_INLIERS = 5
        private const val MIN_REENTRY_INLIER_RATIO = 0.55
        private const val REENTRY_MATCH_BUDGET_PER_FRAME = 8
        private const val MIN_SHARED_REENTRY_ANCHORS = 2
        private const val STRONG_SHARED_REENTRY_ANCHORS = 3
        private const val SHARED_REENTRY_RESIDUAL_LIMIT = 14.0
        private const val REENTRY_RANSAC_REPROJECTION_ERROR = 3.2
        private const val MIN_REENTRY_SCALE = 0.55
        private const val MAX_REENTRY_SCALE = 1.8
        private const val MIN_VIRTUAL_COORDINATE = -4f
        private const val MAX_VIRTUAL_COORDINATE = 5f
        private const val TRACKING_STATS_INTERVAL_MS = 2_000L
        private const val EXTERNAL_FRAME_TIMEOUT_MS = 500L
        private const val FORCE_REIDENTIFICATION_FRAME_GAP_MS = 140L
        private const val EXPOSURE_RECOVERY_FRAMES = 12
        private const val EXPOSURE_JUMP_MIN_LUMA = 10.0
        private const val EXPOSURE_JUMP_MIN_RATIO = 0.10
        private const val MAX_EMPTY_EXTERNAL_FEATURE_FRAMES = 4
        private const val UNKNOWN_FRAME_ROTATION = -1
        private const val LK_LEVELS = 3
        private val LK_WINDOW = Size(23.0, 23.0)
        private val LK_CRITERIA = TermCriteria(
            TermCriteria.COUNT or TermCriteria.EPS,
            24,
            0.01,
        )
    }
}
