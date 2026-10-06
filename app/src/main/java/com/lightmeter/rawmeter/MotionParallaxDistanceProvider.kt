package com.lightmeter.rawmeter

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.os.Handler
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * Opportunistic, bounded visual/inertial ranging on the existing YUV stream. Never opens a
 * camera/session, changes stabilisation, borrows AF scale, or asks the user to perform a gesture.
 * No image or IMU history escapes memory. Missing calibrated geometry simply disables this source.
 */
internal class MotionParallaxDistanceProvider(
    context: Context,
    private val onEstimate: (DistanceContext, DistanceEstimate) -> Unit,
) {
    private data class Frame(
        val width: Int, val height: Int, val bytes: ByteArray,
        val timestampNs: Long, val receivedAtNs: Long,
        val sampleStep: Int,
    )
    private data class Metadata(val result: CaptureResult, val chars: CameraCharacteristics)
    private val sensors = DistanceMotionSensors(context)
    private val frames = LinkedHashMap<Long, Frame>()
    private val metadata = LinkedHashMap<Long, Metadata>()
    private val epoch = AtomicInteger()
    private val busy = AtomicBoolean()
    private val workerDelegate = lazy { ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "distance-parallax").apply { isDaemon = true }
    } }
    private val worker by workerDelegate
    private var currentContext: DistanceContext? = null
    private var cameraHandler: Handler? = null
    private var lastCopiedNs = 0L
    var running = false
        private set
    fun focusReliability(timestampNs: Long) = sensors.focusReliability(timestampNs)
    // Accessed only by the worker, including disposal.
    private var vision: Vision? = null
    private var visionEpoch = -1
    private var nativeFailed = false

    fun start(context: DistanceContext, handler: Handler) {
        if (running && currentContext == context) return
        stop()
        currentContext = context
        cameraHandler = handler
        running = sensors.start(handler)
    }

    fun stop() {
        epoch.incrementAndGet()
        running = false
        currentContext = null
        sensors.stop()
        frames.clear(); metadata.clear(); lastCopiedNs = 0L
        if (workerDelegate.isInitialized()) worker.execute { vision?.close(); vision = null }
    }

    fun onCaptureResult(timestampNs: Long, result: CaptureResult, chars: CameraCharacteristics) {
        if (!running) return
        metadata[timestampNs] = Metadata(result, chars)
        while (metadata.size > 16) metadata.remove(metadata.keys.first())
        dispatch(timestampNs)
    }

    /** Copies a small luminance image before its owner's Image.close(); never retains the Image. */
    fun onImage(image: Image) {
        if (!running || busy.get() || image.timestamp - lastCopiedNs < 90_000_000L) return
        lastCopiedNs = image.timestamp
        val crop = image.cropRect
        if (crop.left != 0 || crop.top != 0 || crop.right != image.width || crop.bottom != image.height) return
        val step = ((max(image.width, image.height) + 511) / 512).coerceAtLeast(1)
        val width = image.width / step
        val height = image.height / step
        if (width < 64 || height < 64 || image.width % step != 0 || image.height % step != 0) return
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer.duplicate()
        val start = buffer.position()
        val bytes = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = start + y * step * plane.rowStride + x * step * plane.pixelStride
            if (offset >= buffer.limit()) return
            bytes[y * width + x] = buffer.get(offset)
        }
        frames[image.timestamp] = Frame(width, height, bytes, image.timestamp, System.nanoTime(), step)
        while (frames.size > 2) frames.remove(frames.keys.first())
        dispatch(image.timestamp)
    }

    private fun dispatch(timestampNs: Long) {
        val frame = frames[timestampNs] ?: return
        val meta = metadata[timestampNs] ?: return
        frames.remove(timestampNs); metadata.remove(timestampNs)
        val context = currentContext ?: return
        val handler = cameraHandler ?: return
        val geometry = MotionDistanceGeometry.from(meta.chars, meta.result, frame.width, frame.height, frame.sampleStep) ?: return
        val exposure = meta.result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val skew = meta.result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: return
        if (skew !in 0..30_000_000L) return
        val imageTime = timestampNs + exposure / 2 + skew / 2
        if (!busy.compareAndSet(false, true)) return
        val capturedEpoch = epoch.get()
        worker.execute {
            try {
                if (epoch.get() != capturedEpoch || nativeFailed) return@execute
                if (vision == null) {
                    if (!OpenCVLoader.initLocal()) { nativeFailed = true; return@execute }
                    vision = Vision(sensors)
                }
                if (visionEpoch != capturedEpoch) { vision?.reset(); visionEpoch = capturedEpoch }
                val result = vision?.process(frame.copy(timestampNs = imageTime), geometry, context)
                if (result != null) handler.post {
                    if (running && epoch.get() == capturedEpoch && currentContext == context)
                        onEstimate(context, result)
                }
            } catch (error: Exception) {
                Log.w("DistanceParallax", "Parallax sample rejected", error)
                vision?.reset()
            } catch (error: LinkageError) {
                nativeFailed = true
                Log.w("DistanceParallax", "Native parallax unavailable", error)
            } finally { busy.set(false) }
        }
    }

    private class Vision(private val sensors: DistanceMotionSensors) : AutoCloseable {
        private data class Matches(val pairs: List<Pair<Point, Point>>, val detectedCount: Int) {
            val retention: Double get() = pairs.size.toDouble() / detectedCount.coerceAtLeast(1)
            fun coversFrame(width: Int, height: Int): Boolean = pairs.map { pair ->
                (pair.second.x * 3 / width).toInt().coerceIn(0, 2) to
                    (pair.second.y * 3 / height).toInt().coerceIn(0, 2)
            }.distinct().size >= 6
        }
        private var previous: Mat? = null
        private var anchor: Mat? = null
        private var previousGeometry: MotionDistanceGeometry? = null
        private var anchorGeometry: MotionDistanceGeometry? = null
        private var previousTime = 0L
        private var anchorTime = 0L
        private var quietFrames = 0
        private var moved = false

        fun process(frame: Frame, geometry: MotionDistanceGeometry, context: DistanceContext): DistanceEstimate? {
            val image = Mat(frame.height, frame.width, CvType.CV_8UC1)
            image.put(0, 0, frame.bytes)
            try {
                if (previous?.let { it.cols() != frame.width || it.rows() != frame.height } == true ||
                    previousGeometry?.compatible(geometry) == false ||
                    anchorGeometry?.compatible(geometry) == false ||
                    frame.timestampNs - previousTime !in 1..300_000_000L) reset()
                val last = previous
                val flow = last?.let { matches(it, image) } ?: Matches(emptyList(), 0)
                val quiet = flow.pairs.size >= 40 && flow.retention >= 0.70 && flow.coversFrame(frame.width, frame.height) &&
                    flow.pairs.count { hypot(it.first.x - it.second.x, it.first.y - it.second.y) < 0.35 } >= flow.pairs.size * 0.90 &&
                    sensors.quietAt(frame.timestampNs)
                quietFrames = if (quiet) quietFrames + 1 else 0
                var estimate: DistanceEstimate? = null
                val origin = anchor
                if (origin != null && !quiet) moved = true
                if (origin != null && frame.timestampNs - anchorTime > 1_500_000_000L) clearAnchor()
                else if (origin != null && moved && quietFrames >= 2) {
                    val motion = MotionDistanceMath.baseline(sensors.between(anchorTime, frame.timestampNs),
                        geometry.sensorToCamera, stationaryEndpoints = true)
                    if (motion != null) {
                        val matches = matches(origin, image)
                        val pairs = matches.pairs
                        val oldGeometry = anchorGeometry!!
                        val angularError = 0.7 / minOf(geometry.fx, geometry.fy) + 0.0005
                        val depths = pairs.map { pair ->
                            pair to MotionDistanceMath.triangulate(MotionDistanceMath.RayMatch(
                                oldGeometry.ray(pair.first.x, pair.first.y), geometry.ray(pair.second.x, pair.second.y)),
                                motion, angularError)
                        }
                        val validCount = depths.count { it.second != null }
                        // Global static-scene consistency rejects many independently moving targets;
                        // it is evidence, not proof that every object in the scene is stationary.
                        if (pairs.size >= 40 && matches.retention >= 0.70 &&
                            matches.coversFrame(frame.width, frame.height) && validCount >= pairs.size * 0.75) {
                            val local = depths.filter { (pair, _) ->
                                abs(pair.second.x / frame.width - context.target.x) <= 0.0625 &&
                                    abs(pair.second.y / frame.height - context.target.y) <= 0.0625
                            }
                            estimate = MotionDistanceMath.estimate(local.mapNotNull { it.second }, context,
                                frame.timestampNs, frame.receivedAtNs,
                                matches.retention * local.count { it.second != null }.toDouble() / local.size.coerceAtLeast(1))
                        }
                    }
                    clearAnchor()
                }
                if (quietFrames >= 2 && !moved) {
                    clearAnchor()
                    anchor = image.clone(); anchorTime = frame.timestampNs; anchorGeometry = geometry
                }
                previous?.release(); previous = image.clone()
                previousTime = frame.timestampNs; previousGeometry = geometry
                return estimate
            } finally { image.release() }
        }

        private fun matches(from: Mat, to: Mat): Matches {
            val corners = MatOfPoint()
            val localCorners = MatOfPoint()
            val mask = Mat.zeros(from.rows(), from.cols(), CvType.CV_8UC1)
            val p = MatOfPoint2f(); val q = MatOfPoint2f(); val back = MatOfPoint2f()
            val status = MatOfByte(); val reverseStatus = MatOfByte()
            val error = MatOfFloat(); val reverseError = MatOfFloat()
            try {
                Imgproc.goodFeaturesToTrack(from, corners, 160, 0.015, 6.0)
                Imgproc.rectangle(mask, Point(from.cols()*0.3, from.rows()*0.3),
                    Point(from.cols()*0.7, from.rows()*0.7), Scalar(255.0), -1)
                Imgproc.goodFeaturesToTrack(from, localCorners, 100, 0.01, 3.0, mask)
                val points = (corners.toList() + localCorners.toList()).distinctBy { it.x to it.y }
                if (points.size < 20) return Matches(emptyList(), points.size)
                p.fromList(points)
                Video.calcOpticalFlowPyrLK(from, to, p, q, status, error, Size(21.0, 21.0), 3)
                Video.calcOpticalFlowPyrLK(to, from, q, back, reverseStatus, reverseError, Size(21.0, 21.0), 3)
                val destinations = q.toArray(); val backwards = back.toArray()
                val valid = status.toArray(); val reverseValid = reverseStatus.toArray(); val errors = error.toArray()
                val pairs = points.indices.mapNotNull { i ->
                    val dst = destinations[i]
                    if (valid[i].toInt() == 0 || reverseValid[i].toInt() == 0 || errors[i] > 15f ||
                        dst.x !in 2.0..(to.cols()-3.0) || dst.y !in 2.0..(to.rows()-3.0) ||
                        hypot(points[i].x - backwards[i].x, points[i].y - backwards[i].y) > 0.6) null
                    else points[i] to dst
                }
                return Matches(pairs, points.size)
            } finally {
                listOf(corners, localCorners, mask, p, q, back, status, reverseStatus, error, reverseError).forEach { it.release() }
            }
        }

        private fun clearAnchor() {
            anchor?.release(); anchor = null; anchorGeometry = null; anchorTime = 0L; moved = false
        }
        fun reset() {
            previous?.release(); previous = null; previousGeometry = null; previousTime = 0L
            clearAnchor(); quietFrames = 0
        }
        override fun close() = reset()
    }
}
