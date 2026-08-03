package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.android.OpenCVLoader
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

    private data class MotionPrediction(
        val dx: Float,
        val dy: Float,
        val rollRadians: Float,
        val screenXRotation: Float,
        val screenYRotation: Float,
        val displayOriented: Boolean,
        val xTranslationTrusted: Boolean,
        val yTranslationTrusted: Boolean,
    )

    private class GyroAxisCalibration {
        val samples = ArrayDeque<Double>()

        fun estimate(): Double? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            } else {
                sorted[middle]
            }
        }
    }

    private data class GyroOrientationCalibration(
        val horizontal: GyroAxisCalibration = GyroAxisCalibration(),
        val vertical: GyroAxisCalibration = GyroAxisCalibration(),
    )

    private data class GyroCalibrationSnapshot(
        val horizontalScale: Double?,
        val horizontalSamples: Int,
        val verticalScale: Double?,
        val verticalSamples: Int,
    )

    private data class VisibleViewport(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0.0001f)
        val height: Float get() = (bottom - top).coerceAtLeast(0.0001f)
    }

    private data class AffineMotion(
        val m00: Double,
        val m01: Double,
        val m02: Double,
        val m10: Double,
        val m11: Double,
        val m12: Double,
        val reliable: Boolean,
        val inlierCount: Int,
    ) {
        fun map(point: Point): Point = Point(
            m00 * point.x + m01 * point.y + m02,
            m10 * point.x + m11 * point.y + m12,
        )
    }

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zone-opencv-tracker").apply { isDaemon = true }
    }
    private val processing = AtomicBoolean(false)
    private val resetRequested = AtomicBoolean(true)
    private val redetectRequested = AtomicBoolean(true)
    private val forceReidentificationRequested = AtomicBoolean(false)
    private val mappingRevision = AtomicInteger(0)
    private val stabilizationFramesRemaining = AtomicInteger(0)
    private val externalFramesSeen = AtomicBoolean(false)
    private val lock = Any()
    private val gyroLock = Any()
    private val gyroCalibrationLock = Any()
    private val tracks = linkedMapOf<Int, Track>()
    private val gyroCalibrations = mutableMapOf<Int, GyroOrientationCalibration>()
    private val sensorManager = textureView.context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val openCvReady = try {
        OpenCVLoader.initLocal().also { ready ->
            Log.i(TAG, "OpenCV ${OpenCVLoader.OPENCV_VERSION} initialized=$ready")
        }
    } catch (error: Throwable) {
        Log.e(TAG, "OpenCV initialization failed", error)
        false
    }
    // Native feature objects must be constructed only after OpenCVLoader has loaded the library.
    private val reentryOrb = ORB.create(REENTRY_FEATURE_COUNT)
    private val reentryMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

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
    private var gyroTimestampNs = 0L
    private var accumulatedScreenXRotation = 0f
    private var accumulatedScreenYRotation = 0f
    private var accumulatedScreenZRotation = 0f
    private var sensorRegistered = false
    @Volatile
    private var lastExternalFrameAtMs = 0L
    @Volatile
    private var lastExternalFrameRotationDegrees = UNKNOWN_FRAME_ROTATION
    private var frameCoordinatesAreDisplayOriented = false
    @Volatile
    private var visibleViewport = VisibleViewport(0f, 0f, 1f, 1f)
    @Volatile
    private var trackedDisplayZoom = 1f

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
            val previousTimestamp = gyroTimestampNs
            gyroTimestampNs = event.timestamp
            if (previousTimestamp == 0L) return
            val dt = ((event.timestamp - previousTimestamp) * 1e-9f).coerceIn(0f, 0.08f)
            if (dt <= 0f) return
            val deviceX = event.values[0]
            val deviceY = event.values[1]
            val (screenX, screenY) = when (textureView.display?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_90 -> deviceY to -deviceX
                Surface.ROTATION_180 -> -deviceX to -deviceY
                Surface.ROTATION_270 -> -deviceY to deviceX
                else -> deviceX to deviceY
            }
            synchronized(gyroLock) {
                accumulatedScreenXRotation += screenX * dt
                accumulatedScreenYRotation += screenY * dt
                accumulatedScreenZRotation += event.values[2] * dt
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            captureFrame()
            mainHandler.postDelayed(this, nextFrameIntervalMs())
        }
    }

    override fun start(markers: List<ZoneMarker>) {
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
        running = true
        externalFramesSeen.set(false)
        lastExternalFrameAtMs = 0L
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
        gyroTimestampNs = 0L
        synchronized(gyroLock) {
            accumulatedScreenXRotation = 0f
            accumulatedScreenYRotation = 0f
            accumulatedScreenZRotation = 0f
        }
        if (!sensorRegistered && gyroscope != null) {
            sensorRegistered = sensorManager?.registerListener(
                gyroListener,
                gyroscope,
                SensorManager.SENSOR_DELAY_GAME,
                mainHandler,
            ) == true
        }
        mainHandler.removeCallbacks(tick)
        mainHandler.post(tick)
    }

    override fun stop() {
        running = false
        mainHandler.removeCallbacks(tick)
        if (sensorRegistered) {
            sensorManager?.unregisterListener(gyroListener)
            sensorRegistered = false
        }
        gyroTimestampNs = 0L
        resetRequested.set(true)
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
        synchronized(gyroLock) {
            accumulatedScreenXRotation = 0f
            accumulatedScreenYRotation = 0f
            accumulatedScreenZRotation = 0f
        }
    }

    override fun setDisplayZoom(zoom: Float) {
        trackedDisplayZoom = zoom.coerceAtLeast(1f)
        publishCurrentPositions()
    }

    override fun offerFrame(frame: ZoneTrackingFrame) {
        if (!running || !openCvReady || frame.width <= 0 || frame.height <= 0 ||
            frame.luma.size < frame.width * frame.height
        ) {
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
        if (processing.getAndSet(true)) return
        val rotated = frame.clockwiseRotationDegrees == 90 ||
            frame.clockwiseRotationDegrees == 270
        val orientedWidth = if (rotated) frame.height else frame.width
        val orientedHeight = if (rotated) frame.width else frame.height
        val (targetWidth, targetHeight) = trackingSize(orientedWidth, orientedHeight)
        val prediction = consumeGyroPrediction(targetWidth, targetHeight, displayOriented = true)
        val revision = mappingRevision.get()
        worker.execute {
            try {
                processLumaFrame(frame, prediction, revision)
            } catch (error: Throwable) {
                Log.e(TAG, "OpenCV YUV tracking frame failed", error)
                resetRequested.set(true)
            } finally {
                processing.set(false)
            }
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
        val viewport = visibleViewport
        synchronized(lock) {
            tracks.values.forEach { track ->
                val oldDisplayX = viewport.left + track.baseX * viewport.width
                val oldDisplayY = viewport.top + track.baseY * viewport.height
                val (newDisplayX, newDisplayY) = rotateDisplayCoordinate(
                    oldDisplayX,
                    oldDisplayY,
                    clockwiseDelta,
                )
                track.baseX = ((newDisplayX - viewport.left) / viewport.width)
                    .coerceIn(MIN_VIRTUAL_COORDINATE, MAX_VIRTUAL_COORDINATE)
                track.baseY = ((newDisplayY - viewport.top) / viewport.height)
                    .coerceIn(MIN_VIRTUAL_COORDINATE, MAX_VIRTUAL_COORDINATE)
                track.trackingState = ZoneTrackingState.UNCERTAIN
                track.misses = 0
            }
        }
        resetRequested.set(true)
        redetectRequested.set(true)
        synchronized(gyroLock) {
            accumulatedScreenXRotation = 0f
            accumulatedScreenYRotation = 0f
            accumulatedScreenZRotation = 0f
        }
        publishCurrentPositions()
        Log.i(
            TAG,
            "tracking frame rotation $previousRotation->$normalizedRotation " +
                "delta=$clockwiseDelta markers=${synchronized(lock) { tracks.size }}",
        )
    }

    private fun rotateDisplayCoordinate(
        x: Float,
        y: Float,
        clockwiseDegrees: Int,
    ): Pair<Float, Float> = when (clockwiseDegrees) {
        90 -> (1f - y) to x
        180 -> (1f - x) to (1f - y)
        270 -> y to (1f - x)
        else -> x to y
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
        if (!running || !openCvReady || processing.getAndSet(true)) return
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (!textureView.isAvailable || viewWidth <= 0 || viewHeight <= 0) {
            processing.set(false)
            return
        }
        // Preserve the displayed TextureView aspect exactly. OpenCV therefore observes the same
        // already-oriented coordinate system in which the Zone markers are drawn.
        val (targetWidth, targetHeight) = trackingSize(viewWidth, viewHeight)
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
        val prediction = consumeGyroPrediction(targetWidth, targetHeight, displayOriented = false)
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

    private fun trackingSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> =
        if (sourceWidth >= sourceHeight) {
            tuning.trackingLongEdge to
                (tuning.trackingLongEdge * sourceHeight.toFloat() / sourceWidth)
                    .roundToInt()
                    .coerceAtLeast(1)
        } else {
            (tuning.trackingLongEdge * sourceWidth.toFloat() / sourceHeight)
                .roundToInt()
                .coerceAtLeast(1) to
                tuning.trackingLongEdge
        }

    private fun processBitmap(
        bitmap: Bitmap,
        prediction: MotionPrediction,
        revision: Int,
    ) {
        if (revision != mappingRevision.get()) return
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        frameCoordinatesAreDisplayOriented = false
        processGrayFrame(prediction, revision)
    }

    private fun processLumaFrame(
        frame: ZoneTrackingFrame,
        prediction: MotionPrediction,
        revision: Int,
    ) {
        if (revision != mappingRevision.get()) return
        val source = Mat(frame.height, frame.width, CvType.CV_8UC1)
        val oriented = Mat()
        try {
            source.put(0, 0, frame.luma)
            when (frame.clockwiseRotationDegrees) {
                90 -> Core.rotate(source, oriented, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(source, oriented, Core.ROTATE_180)
                270 -> Core.rotate(source, oriented, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> source.copyTo(oriented)
            }
            val (targetWidth, targetHeight) = trackingSize(oriented.cols(), oriented.rows())
            Imgproc.resize(oriented, gray, Size(targetWidth.toDouble(), targetHeight.toDouble()))
        } finally {
            source.release()
            oriented.release()
        }
        frameCoordinatesAreDisplayOriented = true
        processGrayFrame(prediction, revision)
    }

    private fun processGrayFrame(
        prediction: MotionPrediction,
        revision: Int,
    ) {
        processedFrameNumber += 1
        captureMissingReentryReferences(gray)
        val forceReidentification = forceReidentificationRequested.getAndSet(false)
        if (forceReidentification && revision == mappingRevision.get()) {
            val recoveryMotion = gyroAffine(
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
            val fallbackMotion = gyroAffine(
                prediction,
                gray.cols(),
                gray.rows(),
                trustedTranslationOnly = true,
            )
            val reentryFeatures = detectReentryFrameFeatures(gray)
            val fallbackUpdates = try {
                updateMarkers(
                    emptyList(),
                    fallbackMotion,
                    prediction,
                    gray.cols(),
                    gray.rows(),
                    reentryFeatures,
                )
            } finally {
                reentryFeatures?.release()
            }
            dispatchUpdates(fallbackUpdates, revision)
            reportTrackingStats(0, fallbackMotion, fallbackUpdates)
            resetFlowFrom(gray)
            return
        }

        val predicted = oldPoints.map { point -> gyroMap(point, prediction, gray.cols(), gray.rows()) }
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
        val reentryFeatures = detectReentryFrameFeatures(gray)
        val updates = try {
            updateMarkers(
                valid,
                affine,
                prediction,
                gray.cols(),
                gray.rows(),
                reentryFeatures,
            )
        } finally {
            reentryFeatures?.release()
        }
        dispatchUpdates(updates, revision)
        reportTrackingStats(valid.size, affine, updates)

        framesUntilRedetect -= 1
        val shouldRedetect = redetectRequested.getAndSet(false) ||
            framesUntilRedetect <= 0 || valid.size < REDTECT_WHEN_BELOW
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
        candidates.forEach { (id, coordinates) ->
            val center = basePreviewToTexture(
                coordinates.first,
                coordinates.second,
                image.cols(),
                image.rows(),
                viewport,
            )
            if (!center.insideWithMargin(image.cols(), image.rows(), REENTRY_REFERENCE_RADIUS)) {
                return@forEach
            }
            val mask = Mat.zeros(image.size(), CvType.CV_8UC1)
            Imgproc.circle(mask, center, REENTRY_REFERENCE_RADIUS, Scalar(255.0), -1)
            val keypoints = MatOfKeyPoint()
            val descriptors = Mat()
            try {
                reentryOrb.detectAndCompute(image, mask, keypoints, descriptors)
                val points = keypoints.toArray().map { it.pt }
                if (points.size < MIN_REENTRY_REFERENCE_FEATURES || descriptors.rows() != points.size) {
                    return@forEach
                }
                val storedDescriptors = descriptors.clone()
                synchronized(lock) {
                    val track = tracks[id]
                    if (track != null && track.referenceDescriptors == null) {
                        track.referenceAnchor = center
                        track.referenceKeypoints = points
                        track.referenceDescriptors = storedDescriptors
                        Log.i(TAG, "marker $id reference captured features=${points.size}")
                    } else {
                        storedDescriptors.release()
                    }
                }
            } finally {
                descriptors.release()
                keypoints.release()
                mask.release()
            }
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
        val gyroCalibration = gyroCalibrationSnapshot(gray.cols(), gray.rows())
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
        val markerSnapshot = synchronized(lock) { tracks.values.map { it.copy() } }
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
                tuning.localFeaturesPerMarker,
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
    }

    private fun estimateMotion(
        valid: MutableList<ValidFlow>,
        prediction: MotionPrediction,
        width: Int,
        height: Int,
    ): AffineMotion {
        if (valid.size < MIN_RANSAC_POINTS) {
            return gyroAffine(prediction, width, height, trustedTranslationOnly = true)
        }
        val source = MatOfPoint2f(*valid.map(ValidFlow::previous).toTypedArray())
        val destination = MatOfPoint2f(*valid.map(ValidFlow::current).toTypedArray())
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
        valid.forEachIndexed { index, flow ->
            flow.inlier = index < inlierBytes.size && inlierBytes[index].toInt() != 0
            if (flow.inlier) inlierCount += 1
        }
        val reliable = !matrix.empty() &&
            inlierCount >= MIN_RANSAC_INLIERS &&
            inlierCount.toDouble() / valid.size >= MIN_INLIER_RATIO
        val result = if (reliable) {
            AffineMotion(
                matrix.get(0, 0)[0], matrix.get(0, 1)[0], matrix.get(0, 2)[0],
                matrix.get(1, 0)[0], matrix.get(1, 1)[0], matrix.get(1, 2)[0],
                true,
                inlierCount,
            ).also { visualMotion ->
                updateGyroCalibration(visualMotion, prediction, width, height)
            }
        } else {
            gyroAffine(prediction, width, height, trustedTranslationOnly = true)
        }
        source.release()
        destination.release()
        inlierMask.release()
        matrix.release()
        return result
    }

    private fun updateGyroCalibration(
        visualMotion: AffineMotion,
        prediction: MotionPrediction,
        width: Int,
        height: Int,
    ) {
        if (visualMotion.inlierCount < STRONG_GLOBAL_INLIERS) return
        val affineScale = kotlin.math.hypot(visualMotion.m00, visualMotion.m10)
        if (abs(affineScale - 1.0) > MAX_CALIBRATION_AFFINE_SCALE_ERROR) return

        val center = Point(width / 2.0, height / 2.0)
        val mappedCenter = visualMotion.map(center)
        val analysisDeltaX = mappedCenter.x - center.x
        val analysisDeltaY = mappedCenter.y - center.y
        val displayDelta = analysisVectorToDisplay(
            analysisDeltaX,
            analysisDeltaY,
            prediction.displayOriented,
        )
        val orientationKey = gyroOrientationKey(width, height)
        synchronized(gyroCalibrationLock) {
            val calibration = gyroCalibrations.getOrPut(orientationKey) {
                GyroOrientationCalibration()
            }
            calibration.horizontal.addGyroScaleObservation(
                angleRadians = prediction.screenYRotation.toDouble(),
                pixelDelta = displayDelta.x,
                frameExtent = width,
            )
            calibration.vertical.addGyroScaleObservation(
                angleRadians = prediction.screenXRotation.toDouble(),
                pixelDelta = displayDelta.y,
                frameExtent = height,
            )
        }
    }

    private fun GyroAxisCalibration.addGyroScaleObservation(
        angleRadians: Double,
        pixelDelta: Double,
        frameExtent: Int,
    ) {
        if (abs(angleRadians) < MIN_GYRO_CALIBRATION_ANGLE ||
            abs(angleRadians) > MAX_GYRO_CALIBRATION_ANGLE ||
            abs(pixelDelta) < MIN_GYRO_CALIBRATION_PIXELS ||
            frameExtent <= 0
        ) {
            return
        }
        val candidate = pixelDelta / angleRadians / frameExtent
        if (!candidate.isFinite() ||
            candidate !in MIN_NORMALIZED_GYRO_SCALE..MAX_NORMALIZED_GYRO_SCALE
        ) {
            return
        }
        val current = estimate()
        if (current != null && samples.size >= MIN_GYRO_OUTLIER_FILTER_SAMPLES &&
            candidate !in
            (current * MIN_GYRO_OBSERVATION_RATIO)..(current * MAX_GYRO_OBSERVATION_RATIO)
        ) {
            return
        }
        samples.addLast(candidate)
        while (samples.size > MAX_GYRO_CALIBRATION_SAMPLES) samples.removeFirst()
    }

    private fun gyroCalibrationSnapshot(width: Int, height: Int): GyroCalibrationSnapshot =
        synchronized(gyroCalibrationLock) {
            val calibration = gyroCalibrations[gyroOrientationKey(width, height)]
            GyroCalibrationSnapshot(
                horizontalScale = calibration?.horizontal?.estimate(),
                horizontalSamples = calibration?.horizontal?.samples?.size ?: 0,
                verticalScale = calibration?.vertical?.estimate(),
                verticalSamples = calibration?.vertical?.samples?.size ?: 0,
            )
        }

    private fun gyroOrientationKey(width: Int, height: Int): Int =
        if (width >= height) GYRO_LANDSCAPE_KEY else GYRO_PORTRAIT_KEY

    private fun updateMarkers(
        valid: List<ValidFlow>,
        motion: AffineMotion,
        prediction: MotionPrediction,
        width: Int,
        height: Int,
        reentryFeatures: ReentryFrameFeatures?,
        forceReidentification: Boolean = false,
    ): List<Update> {
        val viewport = visibleViewport
        val updates = ArrayList<Update>()
        synchronized(lock) {
            val localEvidence = tracks.values.mapNotNull { track ->
                val samples = valid.filter { it.ownerId == track.id }
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
                track.id to LocalEvidence(
                    consistent.map(rawX::get).median(),
                    consistent.map(rawY::get).median(),
                    consistent.size,
                )
            }.toMap()
            // A robust shared residual lets a marker with weak texture follow the constellation
            // formed by the other visible markers instead of drifting independently.
            val sharedEvidence = localEvidence.values.takeIf { it.isNotEmpty() }?.let { evidence ->
                LocalEvidence(
                    evidence.map(LocalEvidence::dx).median(),
                    evidence.map(LocalEvidence::dy).median(),
                    evidence.sumOf(LocalEvidence::support),
                )
            }
            tracks.values.forEach { track ->
                val wasLost = track.trackingState == ZoneTrackingState.LOST
                val oldPoint = basePreviewToTexture(track.baseX, track.baseY, width, height, viewport)
                var mapped = motion.map(oldPoint)
                val local = localEvidence[track.id]
                val correction = when {
                    local != null && sharedEvidence != null -> LocalEvidence(
                        dx = local.dx * LOCAL_CORRECTION_WEIGHT +
                            sharedEvidence.dx * (1.0 - LOCAL_CORRECTION_WEIGHT),
                        dy = local.dy * LOCAL_CORRECTION_WEIGHT +
                            sharedEvidence.dy * (1.0 - LOCAL_CORRECTION_WEIGHT),
                        support = local.support,
                    )
                    local != null -> local
                    sharedEvidence != null -> sharedEvidence
                    else -> null
                }
                if (correction != null) {
                    mapped = Point(
                        mapped.x + correction.dx.coerceIn(-MAX_LOCAL_CORRECTION, MAX_LOCAL_CORRECTION),
                        mapped.y + correction.dy.coerceIn(-MAX_LOCAL_CORRECTION, MAX_LOCAL_CORRECTION),
                    )
                }
                val requiresReidentification =
                    (wasLost || forceReidentification) && track.referenceDescriptors != null
                val reentry = if (requiresReidentification && reentryFeatures != null) {
                    findReentryMatch(track, reentryFeatures, width, height)
                } else {
                    null
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
                val visualSupport = motion.reliable || local != null || sharedEvidence != null
                val trustedGyroMotion =
                    (prediction.xTranslationTrusted && abs(prediction.dx) > 0.002f) ||
                        (prediction.yTranslationTrusted && abs(prediction.dy) > 0.002f) ||
                        abs(prediction.rollRadians) > 0.002f
                val trackingState = when {
                    !insidePreview -> ZoneTrackingState.LOST
                    reentry != null -> ZoneTrackingState.TRACKED
                    requiresReidentification -> ZoneTrackingState.LOST
                    local != null -> ZoneTrackingState.TRACKED
                    motion.reliable && motion.inlierCount >= STRONG_GLOBAL_INLIERS ->
                        ZoneTrackingState.TRACKED
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

    private fun gyroAffine(
        prediction: MotionPrediction,
        width: Int,
        height: Int,
        trustedTranslationOnly: Boolean,
    ): AffineMotion {
        val angle = prediction.rollRadians.toDouble()
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        val centerX = width / 2.0
        val centerY = height / 2.0
        val dx = if (!trustedTranslationOnly || prediction.xTranslationTrusted) {
            prediction.dx.toDouble()
        } else {
            0.0
        }
        val dy = if (!trustedTranslationOnly || prediction.yTranslationTrusted) {
            prediction.dy.toDouble()
        } else {
            0.0
        }
        return AffineMotion(
            cos,
            -sin,
            dx + centerX - cos * centerX + sin * centerY,
            sin,
            cos,
            dy + centerY - sin * centerX - cos * centerY,
            false,
            0,
        )
    }

    private fun gyroMap(point: Point, prediction: MotionPrediction, width: Int, height: Int): Point =
        gyroAffine(prediction, width, height, trustedTranslationOnly = false).map(point)

    private fun basePreviewToTexture(
        baseX: Float,
        baseY: Float,
        width: Int,
        height: Int,
        viewport: VisibleViewport,
    ): Point {
        val baseDisplayX = viewport.left + baseX * viewport.width
        val baseDisplayY = viewport.top + baseY * viewport.height
        val analysis = if (frameCoordinatesAreDisplayOriented) {
            Point(baseDisplayX.toDouble(), baseDisplayY.toDouble())
        } else {
            displayToAnalysis(baseDisplayX.toDouble(), baseDisplayY.toDouble())
        }
        return Point(analysis.x * width, analysis.y * height)
    }

    private fun textureToBasePreview(
        point: Point,
        width: Int,
        height: Int,
        viewport: VisibleViewport,
    ): Pair<Float, Float> {
        val baseDisplay = if (frameCoordinatesAreDisplayOriented) {
            Point(point.x / width, point.y / height)
        } else {
            analysisToDisplay(point.x / width, point.y / height)
        }
        return ((baseDisplay.x - viewport.left) / viewport.width).toFloat() to
            ((baseDisplay.y - viewport.top) / viewport.height).toFloat()
    }

    private fun uiPreviewToBasePreview(
        uiX: Float,
        uiY: Float,
        viewport: VisibleViewport,
    ): Pair<Float, Float> {
        val zoom = trackedDisplayZoom.toDouble().coerceAtLeast(1.0)
        val zoomedDisplayX = viewport.left + uiX * viewport.width
        val zoomedDisplayY = viewport.top + uiY * viewport.height
        val baseDisplayX = 0.5 + (zoomedDisplayX - 0.5) / zoom
        val baseDisplayY = 0.5 + (zoomedDisplayY - 0.5) / zoom
        return ((baseDisplayX - viewport.left) / viewport.width).toFloat() to
            ((baseDisplayY - viewport.top) / viewport.height).toFloat()
    }

    private fun basePreviewToUiPreview(
        baseX: Float,
        baseY: Float,
        viewport: VisibleViewport,
    ): Pair<Float, Float> {
        val zoom = trackedDisplayZoom.toDouble().coerceAtLeast(1.0)
        val baseDisplayX = viewport.left + baseX * viewport.width
        val baseDisplayY = viewport.top + baseY * viewport.height
        val zoomedDisplayX = 0.5 + (baseDisplayX - 0.5) * zoom
        val zoomedDisplayY = 0.5 + (baseDisplayY - 0.5) * zoom
        return ((zoomedDisplayX - viewport.left) / viewport.width).toFloat() to
            ((zoomedDisplayY - viewport.top) / viewport.height).toFloat()
    }

    /**
     * TextureView bitmap capture on the tested Camera2 path remains in the device's natural
     * coordinate orientation. The preview UI, however, follows Display rotation. Keeping this
     * conversion in the tracker boundary prevents sensor axes from leaking into Zone UI state.
     */
    private fun displayToAnalysis(x: Double, y: Double): Point = when (displayRotationDegrees()) {
        90 -> Point(1.0 - y, x)
        180 -> Point(1.0 - x, 1.0 - y)
        270 -> Point(y, 1.0 - x)
        else -> Point(x, y)
    }

    private fun analysisToDisplay(x: Double, y: Double): Point = when (displayRotationDegrees()) {
        90 -> Point(y, 1.0 - x)
        180 -> Point(1.0 - x, 1.0 - y)
        270 -> Point(1.0 - y, x)
        else -> Point(x, y)
    }

    private fun displayVectorToAnalysis(
        x: Double,
        y: Double,
        displayOriented: Boolean,
    ): Point =
        if (displayOriented) {
            Point(x, y)
        } else when (displayRotationDegrees()) {
            90 -> Point(-y, x)
            180 -> Point(-x, -y)
            270 -> Point(y, -x)
            else -> Point(x, y)
        }

    private fun analysisVectorToDisplay(
        x: Double,
        y: Double,
        displayOriented: Boolean,
    ): Point =
        if (displayOriented) {
            Point(x, y)
        } else when (displayRotationDegrees()) {
            90 -> Point(y, -x)
            180 -> Point(-x, -y)
            270 -> Point(-y, x)
            else -> Point(x, y)
        }

    private fun displayRotationDegrees(): Int =
        when (textureView.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    private fun consumeGyroPrediction(
        width: Int,
        height: Int,
        displayOriented: Boolean,
    ): MotionPrediction {
        val rotations = synchronized(gyroLock) {
            val result = Triple(
                accumulatedScreenXRotation,
                accumulatedScreenYRotation,
                accumulatedScreenZRotation,
            )
            accumulatedScreenXRotation = 0f
            accumulatedScreenYRotation = 0f
            accumulatedScreenZRotation = 0f
            result
        }
        val screenXRotation = rotations.first
        val screenYRotation = rotations.second
        val screenZRotation = rotations.third

        val info = meterState.cameraInfo
        val screenAspect = width.toDouble() / height.coerceAtLeast(1).toDouble()
        var horizontalFov = Math.toRadians(64.0)
        var verticalFov = 2.0 * atan(kotlin.math.tan(horizontalFov / 2.0) / screenAspect)
        if (info.focalLengthMm > 0f && info.sensorWidthMm > 0f && info.sensorHeightMm > 0f) {
            val displayDegrees = displayRotationDegrees()
            val relativeRotation = (info.sensorOrientationDegrees - displayDegrees + 360) % 360
            val frameAspectInSensor = if (relativeRotation == 90 || relativeRotation == 270) {
                1.0 / screenAspect
            } else {
                screenAspect
            }
            val sensorAspect = info.sensorWidthMm.toDouble() / info.sensorHeightMm.toDouble()
            val cropWidth: Double
            val cropHeight: Double
            if (sensorAspect > frameAspectInSensor) {
                cropHeight = info.sensorHeightMm.toDouble()
                cropWidth = cropHeight * frameAspectInSensor
            } else {
                cropWidth = info.sensorWidthMm.toDouble()
                cropHeight = cropWidth / frameAspectInSensor
            }
            val physicalWidth = if (relativeRotation == 90 || relativeRotation == 270) cropHeight else cropWidth
            val physicalHeight = if (relativeRotation == 90 || relativeRotation == 270) cropWidth else cropHeight
            // Gyro predicts motion in the unzoomed SurfaceTexture sampled by OpenCV. The
            // base-to-UI mapping above applies electronic zoom exactly once afterwards.
            val baseFocalLength = info.focalLengthMm.toDouble()
            horizontalFov = 2.0 * atan(physicalWidth / (2.0 * baseFocalLength))
            verticalFov = 2.0 * atan(physicalHeight / (2.0 * baseFocalLength))
        }
        // Convert angular camera motion with a pinhole projection. The former width/FOV
        // approximation over-predicted motion on wide lenses even when metadata was correct.
        val metadataHorizontalScale = 1.0 /
            (2.0 * kotlin.math.tan(horizontalFov.coerceAtLeast(0.12) / 2.0))
        val metadataVerticalScale = 1.0 /
            (2.0 * kotlin.math.tan(verticalFov.coerceAtLeast(0.12) / 2.0))
        val learned = gyroCalibrationSnapshot(width, height)
        val horizontalScale = blendedGyroScale(
            metadataHorizontalScale,
            learned.horizontalScale,
            learned.horizontalSamples,
        )
        val verticalScale = blendedGyroScale(
            metadataVerticalScale,
            learned.verticalScale,
            learned.verticalSamples,
        )
        val horizontalTrusted = learned.horizontalSamples >= MIN_TRUSTED_GYRO_SAMPLES
        val verticalTrusted = learned.verticalSamples >= MIN_TRUSTED_GYRO_SAMPLES

        val displayDx = screenYRotation * horizontalScale
        // Positive rotation about the displayed horizontal axis tilts the rear camera upward;
        // scene content therefore moves toward positive bitmap Y. This sign must stay in
        // displayed coordinates, especially after a landscape rotation.
        val displayDy = screenXRotation * verticalScale
        val analysisDelta = displayVectorToAnalysis(displayDx, displayDy, displayOriented)
        val (xTrusted, yTrusted) = displayTrustToAnalysisTrust(
            horizontalTrusted,
            verticalTrusted,
            displayOriented,
        )
        val maxDxFraction = if (xTrusted) MAX_TRUSTED_GYRO_FRAME_FRACTION else MAX_SEED_FRAME_FRACTION
        val maxDyFraction = if (yTrusted) MAX_TRUSTED_GYRO_FRAME_FRACTION else MAX_SEED_FRAME_FRACTION
        val dx = (analysisDelta.x * width)
            .coerceIn(-width * maxDxFraction, width * maxDxFraction).toFloat()
        val dy = (analysisDelta.y * height)
            .coerceIn(-height * maxDyFraction, height * maxDyFraction).toFloat()
        return MotionPrediction(
            dx = dx,
            dy = dy,
            rollRadians = screenZRotation.coerceIn(-0.35f, 0.35f),
            screenXRotation = screenXRotation,
            screenYRotation = screenYRotation,
            displayOriented = displayOriented,
            xTranslationTrusted = xTrusted,
            yTranslationTrusted = yTrusted,
        )
    }

    private fun blendedGyroScale(
        metadataScale: Double,
        learnedScale: Double?,
        sampleCount: Int,
    ): Double {
        if (learnedScale == null || sampleCount < MIN_GYRO_BLEND_SAMPLES) return metadataScale
        val learnedWeight = ((sampleCount - MIN_GYRO_BLEND_SAMPLES + 1).toDouble() /
            GYRO_BLEND_FULL_CONFIDENCE_SAMPLES).coerceIn(0.0, 1.0)
        return metadataScale * (1.0 - learnedWeight) + learnedScale * learnedWeight
    }

    private fun displayTrustToAnalysisTrust(
        horizontalTrusted: Boolean,
        verticalTrusted: Boolean,
        displayOriented: Boolean,
    ): Pair<Boolean, Boolean> {
        if (displayOriented) return horizontalTrusted to verticalTrusted
        return when (displayRotationDegrees()) {
            90, 270 -> verticalTrusted to horizontalTrusted
            else -> horizontalTrusted to verticalTrusted
        }
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
        private const val MIN_RANSAC_POINTS = 6
        private const val MIN_RANSAC_INLIERS = 6
        private const val STRONG_GLOBAL_INLIERS = 14
        private const val MIN_LOCAL_SUPPORT = 2
        private const val LOCAL_RESIDUAL_LIMIT = 5.5
        private const val LOCAL_CORRECTION_WEIGHT = 0.72
        private const val MIN_INLIER_RATIO = 0.38
        private const val FORWARD_BACKWARD_LIMIT = 1.8
        private const val RANSAC_REPROJECTION_ERROR = 2.6
        private const val RANSAC_MAX_ITERATIONS = 1200L
        private const val RANSAC_CONFIDENCE = 0.995
        private const val RANSAC_REFINE_ITERATIONS = 10L
        private const val MIN_EIGEN_THRESHOLD = 0.0001
        private const val MAX_LOCAL_CORRECTION = 22.0
        private const val MAX_CALIBRATION_AFFINE_SCALE_ERROR = 0.06
        private const val MIN_GYRO_CALIBRATION_ANGLE = 0.0008
        private const val MAX_GYRO_CALIBRATION_ANGLE = 0.09
        private const val MIN_GYRO_CALIBRATION_PIXELS = 0.35
        private const val MIN_NORMALIZED_GYRO_SCALE = 0.18
        private const val MAX_NORMALIZED_GYRO_SCALE = 2.5
        private const val MIN_GYRO_OBSERVATION_RATIO = 0.58
        private const val MAX_GYRO_OBSERVATION_RATIO = 1.72
        private const val MIN_GYRO_OUTLIER_FILTER_SAMPLES = 4
        private const val MAX_GYRO_CALIBRATION_SAMPLES = 21
        private const val MIN_GYRO_BLEND_SAMPLES = 3
        private const val MIN_TRUSTED_GYRO_SAMPLES = 6
        private const val GYRO_BLEND_FULL_CONFIDENCE_SAMPLES = 8.0
        private const val MAX_SEED_FRAME_FRACTION = 0.16
        private const val MAX_TRUSTED_GYRO_FRAME_FRACTION = 0.42
        private const val GYRO_LANDSCAPE_KEY = 0
        private const val GYRO_PORTRAIT_KEY = 1
        private const val REENTRY_FEATURE_COUNT = 700
        private const val REENTRY_REFERENCE_RADIUS = 72
        private const val MIN_REENTRY_REFERENCE_FEATURES = 8
        private const val REENTRY_REFERENCE_RETRY_FRAMES = 15L
        private const val MIN_REENTRY_FRAME_FEATURES = 20
        private const val REENTRY_SEARCH_INTERVAL_FRAMES = 4L
        private const val REENTRY_LOWE_RATIO = 0.74f
        private const val MIN_REENTRY_GOOD_MATCHES = 6
        private const val MIN_REENTRY_INLIERS = 5
        private const val MIN_REENTRY_INLIER_RATIO = 0.55
        private const val REENTRY_RANSAC_REPROJECTION_ERROR = 3.2
        private const val MIN_REENTRY_SCALE = 0.55
        private const val MAX_REENTRY_SCALE = 1.8
        private const val MIN_VIRTUAL_COORDINATE = -4f
        private const val MAX_VIRTUAL_COORDINATE = 5f
        private const val TRACKING_STATS_INTERVAL_MS = 2_000L
        private const val EXTERNAL_FRAME_TIMEOUT_MS = 500L
        private const val FORCE_REIDENTIFICATION_FRAME_GAP_MS = 140L
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
