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
 * This class is intentionally not synchronized. It must be confined to CameraController's camera
 * handler, which also preserves callback order and keeps image ownership easy to audit.
 */
internal class TimestampedResultPairer<I : Any, R : Any>(
    private val releaseImage: (I) -> Unit,
) {
    private val images = mutableMapOf<Long, I>()
    private val results = mutableMapOf<Long, R>()

    fun offerImage(timestamp: Long, image: I): TimestampedResultPair<I, R>? {
        images.put(timestamp, image)?.let(releaseImage)
        return removePair(timestamp)
    }

    fun offerResult(timestamp: Long, result: R): TimestampedResultPair<I, R>? {
        results[timestamp] = result
        return removePair(timestamp)
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

    private fun removePair(timestamp: Long): TimestampedResultPair<I, R>? {
        val image = images[timestamp] ?: return null
        val result = results[timestamp] ?: return null
        images.remove(timestamp)
        results.remove(timestamp)
        return TimestampedResultPair(timestamp, image, result)
    }
}
