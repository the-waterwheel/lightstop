package com.lightmeter.rawmeter

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

    fun offerImage(timestamp: Long, image: I): TimestampedResultPair<I, R>? {
        images.put(timestamp, image)?.let(releaseImage)
        val resultKey = matchingKey(results, timestamp) ?: return null
        val result = results.remove(resultKey) ?: return null
        images.remove(timestamp)
        return TimestampedResultPair(timestamp, image, result)
    }

    fun offerResult(timestamp: Long, result: R): TimestampedResultPair<I, R>? {
        results[timestamp] = result
        val imageKey = matchingKey(images, timestamp) ?: return null
        val image = images.remove(imageKey) ?: return null
        results.remove(timestamp)
        return TimestampedResultPair(imageKey, image, result)
    }

    /** Releases all images still owned by this pairer and forgets unmatched metadata. */
    fun clear() {
        images.values.forEach(releaseImage)
        images.clear()
        results.clear()
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
}
