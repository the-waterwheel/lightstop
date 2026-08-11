package com.lightmeter.rawmeter

import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.view.Surface

/**
 * Camera-thread boundary for Zone YUV frames.
 *
 * The order is intentional: acquire the latest Image, acquire one of three reusable slots, reserve
 * the OpenCV worker, then copy. Frames arriving while OpenCV is busy are closed before a single
 * luminance byte is copied. The newest accepted frame is retained separately for RAW touch-metering
 * alignment and released when replaced, disabled, or the camera closes.
 */
internal class ZoneCameraFramePipeline(
    private val reserveTracker: () -> Boolean,
    private val cancelTrackerReservation: () -> Unit,
    private val deliverToTracker: (ZoneTrackingFrame) -> Unit,
    private val bufferPool: ZoneLumaBufferPool = ZoneLumaBufferPool(),
) {
    @Volatile
    private var enabled = false
    @Volatile
    private var latestFrame: ZoneTrackingFrame? = null
    private var frameLogged = false
    private var copiedFrames = 0L
    private var busyDrops = 0L
    private var poolDrops = 0L

    fun setEnabled(value: Boolean, cameraHandler: Handler?) {
        val wasEnabled = enabled
        enabled = value
        if (value && !wasEnabled) {
            copiedFrames = 0L
            busyDrops = 0L
            poolDrops = 0L
        } else if (!value && wasEnabled) {
            Log.i(
                TAG,
                "Zone YUV summary copied=$copiedFrames busyDropped=$busyDrops " +
                    "poolDropped=$poolDrops buffersAllocated=${bufferPool.allocationCount}",
            )
        }
        if (!value) {
            val clearLatest = Runnable { replaceLatest(null) }
            cameraHandler?.post(clearLatest) ?: clearLatest.run()
        }
    }

    fun latestFrame(maxAgeNs: Long, nowNs: Long = System.nanoTime()): ZoneTrackingFrame? =
        latestFrame?.takeIf { nowNs - it.capturedAtNs <= maxAgeNs }

    fun onImageAvailable(
        reader: ImageReader,
        sensorOrientationDegrees: Int,
        displayRotation: Int,
    ) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            if (!enabled) return
            val width = image.width
            val height = image.height
            val lumaBuffer = bufferPool.tryAcquire(width * height) ?: run {
                poolDrops += 1
                return
            }
            if (!reserveTracker()) {
                busyDrops += 1
                lumaBuffer.close()
                return
            }
            val copied = try {
                ZoneYuvLumaCopier.copy(image, lumaBuffer.bytes)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to copy Zone YUV luminance plane", error)
                false
            }
            if (!copied) {
                cancelTrackerReservation()
                lumaBuffer.close()
                return
            }
            val displayDegrees = when (displayRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val rotation = (sensorOrientationDegrees - displayDegrees + 360) % 360
            if (!frameLogged) {
                Log.i(TAG, "Zone tracking YUV ${width}x$height rotation=$rotation display=$displayDegrees")
                frameLogged = true
            }
            val frame = ZoneTrackingFrame(width, height, lumaBuffer, rotation)
            copiedFrames += 1
            val latest = frame.retained()
            replaceLatest(latest)
            try {
                deliverToTracker(frame)
            } catch (error: Throwable) {
                if (latestFrame === latest) replaceLatest(null)
                frame.close()
                cancelTrackerReservation()
                Log.e(TAG, "Unable to deliver Zone tracking frame", error)
            }
        } finally {
            image.close()
        }
    }

    fun reset() {
        enabled = false
        replaceLatest(null)
        frameLogged = false
    }

    private fun replaceLatest(frame: ZoneTrackingFrame?) {
        val previous = latestFrame
        latestFrame = frame
        previous?.close()
    }

    private companion object {
        const val TAG = "ZoneCameraFrames"
    }
}
