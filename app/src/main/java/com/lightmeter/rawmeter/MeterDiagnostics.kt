package com.lightmeter.rawmeter

import android.util.Log

/**
 * Central gate for verbose, high-frequency diagnostics such as per-frame RAW statistics.
 *
 * Normal operation keeps this disabled so large strings are never built and privacy-sensitive
 * details (location, notes, image content) are never logged. A debug build or an explicit local
 * diagnostics toggle may enable it; only redacted summaries are ever emitted.
 */
object MeterDiagnostics {
    @Volatile
    var enabled: Boolean = false

    private const val MIN_INTERVAL_MS = 1_000L
    private val lastLogAtMs = HashMap<String, Long>()

    /** Emits a redacted summary, rate-limited per tag and skipped entirely when disabled. */
    fun log(tag: String, message: () -> String) {
        if (!enabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastLogAtMs) {
            val previous = lastLogAtMs[tag] ?: 0L
            if (now - previous < MIN_INTERVAL_MS) return
            lastLogAtMs[tag] = now
        }
        Log.d(tag, message())
    }
}
