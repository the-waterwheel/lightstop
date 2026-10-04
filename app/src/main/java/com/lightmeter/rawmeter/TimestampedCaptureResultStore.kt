package com.lightmeter.rawmeter

/**
 * Bounded camera-thread cache for metadata that can be paired with a display-buffer timestamp.
 * It intentionally provides exact lookup only: a neighboring frame may have different exposure
 * or ISO, so a nearest-timestamp fallback is not acceptable for a metering reading.
 */
internal class TimestampedCaptureResultStore<R : Any>(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val results = LinkedHashMap<Long, R>()

    fun put(timestamp: Long, result: R) {
        if (timestamp <= 0L) return
        results[timestamp] = result
        while (results.size > capacity) {
            results.entries.iterator().next().also { results.remove(it.key) }
        }
    }

    fun takeExact(timestamp: Long): R? = results.remove(timestamp)

    fun clear() = results.clear()

    internal val size: Int
        get() = results.size

    private companion object {
        private const val DEFAULT_CAPACITY = 12
    }
}
