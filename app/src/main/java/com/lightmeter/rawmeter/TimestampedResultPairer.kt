package com.lightmeter.rawmeter

import java.util.ArrayDeque

/** A camera payload and its capture metadata that share the same sensor timestamp. */
internal data class TimestampedResultPair<I : Any, R : Any>(
    val timestamp: Long,
    val image: I,
    val result: R,
)

/**
 * Matches camera images with capture results without leaking unmatched images.
 *
 * Camera2 delivers ImageReader and capture callbacks independently and in either order. This
 * class owns every image offered to it until that image is paired or [clear] is called. Callers
 * own a returned pair and must release its image after processing it.
 *
 * Camera2 requires the image and result timestamps of one frame to be identical, but a few
 * vendor HALs report small offsets. Matching is exact-first; the tolerance path only accepts
 * the nearest timestamp within [toleranceNs], which callers must bound to at most half a frame
 * period so an image can never pair with a neighboring frame's metadata.
 *
 * This class is intentionally not synchronized. It must be confined to CameraController's camera
 * handler, which also preserves callback order and keeps image ownership easy to audit.
 */
internal class TimestampedResultPairer<I : Any, R : Any>(
    private val releaseImage: (I) -> Unit,
    private val toleranceNs: Long = 0L,
) {
    private val images = mutableMapOf<Long, I>()
    private val results = mutableMapOf<Long, R>()
    private val rejectedTimestamps = ArrayDeque<Long>()
    private val rejectedSet = HashSet<Long>()

    fun offerImage(timestamp: Long, image: I): TimestampedResultPair<I, R>? {
        if (rejectedSet.remove(timestamp)) {
            rejectedTimestamps.remove(timestamp)
            releaseImage(image)
            return null
        }
        images.put(timestamp, image)?.let(releaseImage)
        val resultKey = matchingKey(results, timestamp) ?: return null
        val result = results.remove(resultKey) ?: return null
        images.remove(timestamp)
        return TimestampedResultPair(timestamp, image, result)
    }

    fun offerResult(timestamp: Long, result: R): TimestampedResultPair<I, R>? {
        if (rejectedSet.remove(timestamp)) {
            rejectedTimestamps.remove(timestamp)
            return null
        }
        results[timestamp] = result
        val imageKey = matchingKey(images, timestamp) ?: return null
        val image = images.remove(imageKey) ?: return null
        results.remove(timestamp)
        return TimestampedResultPair(imageKey, image, result)
    }

    /**
     * Rejects one frame's metadata so its image cannot pair with stale or foreign metadata.
     * A pending image with the same timestamp is released immediately, and a later image is closed
     * as soon as it arrives, keeping the single-frame pipeline from stalling on a bad result.
     */
    fun reject(timestamp: Long) {
        images.remove(timestamp)?.let(releaseImage)
        results.remove(timestamp)
        if (rejectedSet.add(timestamp)) {
            rejectedTimestamps.addLast(timestamp)
            while (rejectedTimestamps.size > MAX_REJECTED_TIMESTAMPS) {
                rejectedSet.remove(rejectedTimestamps.removeFirst())
            }
        }
    }

    /** Releases all images still owned by this pairer and forgets unmatched metadata. */
    fun clear() {
        images.values.forEach(releaseImage)
        images.clear()
        results.clear()
        rejectedTimestamps.clear()
        rejectedSet.clear()
    }

    internal val pendingImageCount: Int
        get() = images.size

    internal val pendingResultCount: Int
        get() = results.size

    private fun matchingKey(map: Map<Long, *>, timestamp: Long): Long? {
        if (map.containsKey(timestamp)) return timestamp
        if (toleranceNs <= 0L) return null
        var bestKey: Long? = null
        var bestDelta = Long.MAX_VALUE
        for (key in map.keys) {
            val delta = if (key >= timestamp) key - timestamp else timestamp - key
            if (delta <= toleranceNs && delta < bestDelta) {
                bestDelta = delta
                bestKey = key
            }
        }
        return bestKey
    }

    private companion object {
        /** Bounds the rejected-timestamp memory; callers reject at most a few frames per burst. */
        private const val MAX_REJECTED_TIMESTAMPS = 8
    }
}
