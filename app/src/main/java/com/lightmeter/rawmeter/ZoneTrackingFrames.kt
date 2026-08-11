package com.lightmeter.rawmeter

import android.media.Image
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Immutable metadata plus a reference-counted luminance buffer for one tracking frame.
 *
 * CameraController retains the newest frame briefly for touch-metering alignment while the
 * OpenCV worker owns the original reference. The backing slot is returned to the three-buffer
 * pool only after both readers close their handles.
 */
class ZoneTrackingFrame private constructor(
    val width: Int,
    val height: Int,
    private val buffer: ZoneLumaBufferHandle,
    /** Clockwise rotation that makes the camera buffer upright in the current display. */
    val clockwiseRotationDegrees: Int,
    val capturedAtNs: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val luma: ByteArray
        get() = buffer.bytes

    /** Convenience constructor for tests and non-pooled producers. */
    constructor(
        width: Int,
        height: Int,
        luma: ByteArray,
        clockwiseRotationDegrees: Int,
        capturedAtNs: Long = System.nanoTime(),
    ) : this(
        width,
        height,
        StandaloneLumaBufferHandle(luma),
        clockwiseRotationDegrees,
        capturedAtNs,
    )

    internal constructor(
        width: Int,
        height: Int,
        buffer: ZoneLumaBufferLease,
        clockwiseRotationDegrees: Int,
        capturedAtNs: Long = System.nanoTime(),
    ) : this(width, height, buffer as ZoneLumaBufferHandle, clockwiseRotationDegrees, capturedAtNs)

    /** Create another independently closeable view of the same bytes. */
    internal fun retained(): ZoneTrackingFrame {
        check(!closed.get()) { "Cannot retain a closed Zone tracking frame" }
        buffer.retain()
        return ZoneTrackingFrame(
            width,
            height,
            buffer,
            clockwiseRotationDegrees,
            capturedAtNs,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) buffer.release()
    }
}

internal interface ZoneLumaBufferHandle {
    val bytes: ByteArray
    fun retain()
    fun release()
}

private class StandaloneLumaBufferHandle(
    override val bytes: ByteArray,
) : ZoneLumaBufferHandle {
    override fun retain() = Unit
    override fun release() = Unit
}

internal class ZoneLumaBufferSlot(
    var bytes: ByteArray = ByteArray(0),
    var inUse: Boolean = false,
)

/** Fixed-capacity pool; changing resolution reallocates each slot at most once. */
internal class ZoneLumaBufferPool(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val slots = Array(capacity.coerceAtLeast(1)) { ZoneLumaBufferSlot() }

    @Volatile
    internal var allocationCount: Int = 0
        private set

    @Synchronized
    fun tryAcquire(requiredBytes: Int): ZoneLumaBufferLease? {
        if (requiredBytes <= 0) return null
        val slot = slots.firstOrNull { !it.inUse } ?: return null
        slot.inUse = true
        if (slot.bytes.size != requiredBytes) {
            slot.bytes = ByteArray(requiredBytes)
            allocationCount += 1
        }
        return ZoneLumaBufferLease(this, slot)
    }

    @Synchronized
    internal fun release(slot: ZoneLumaBufferSlot) {
        check(slot.inUse) { "Zone luminance buffer released more than once" }
        slot.inUse = false
    }

    @Synchronized
    internal fun availableCount(): Int = slots.count { !it.inUse }

    companion object {
        const val DEFAULT_CAPACITY = 3
    }
}

internal class ZoneLumaBufferLease(
    private val owner: ZoneLumaBufferPool,
    private val slot: ZoneLumaBufferSlot,
) : ZoneLumaBufferHandle, AutoCloseable {
    private val references = AtomicInteger(1)

    override val bytes: ByteArray
        get() = slot.bytes

    override fun retain() {
        while (true) {
            val current = references.get()
            check(current > 0) { "Cannot retain a released Zone luminance buffer" }
            if (references.compareAndSet(current, current + 1)) return
        }
    }

    override fun release() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "Zone luminance buffer released more than retained" }
        if (remaining == 0) owner.release(slot)
    }

    override fun close() = release()
}

/** Copies only the Y plane and supports vendor row/pixel padding without temporary arrays. */
internal object ZoneYuvLumaCopier {
    fun copy(image: Image, destination: ByteArray): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        return copy(
            width = image.width,
            height = image.height,
            source = plane.buffer,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            destination = destination,
        )
    }

    internal fun copy(
        width: Int,
        height: Int,
        source: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        destination: ByteArray,
    ): Boolean {
        val requiredBytes = width * height
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0 ||
            destination.size < requiredBytes
        ) {
            return false
        }
        val input = source.duplicate()
        val start = input.position()
        if (pixelStride == 1 && rowStride == width && input.remaining() >= requiredBytes) {
            input.get(destination, 0, requiredBytes)
            return true
        }
        for (row in 0 until height) {
            val rowOffset = start + row * rowStride
            val destinationOffset = row * width
            for (column in 0 until width) {
                val sourceOffset = rowOffset + column * pixelStride
                if (sourceOffset >= input.limit()) return false
                destination[destinationOffset + column] = input.get(sourceOffset)
            }
        }
        return true
    }
}
