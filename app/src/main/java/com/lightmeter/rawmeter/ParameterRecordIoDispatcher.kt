package com.lightmeter.rawmeter

import android.os.Handler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Owns the single background thread that performs parameter-record compression, file copies and
 * index writes. All mutating repository work runs here so mutable record state has exactly one
 * owner; results are delivered back on the main handler.
 *
 * Callers hand immutable snapshots in and must not touch views from the background task.
 */
internal class ParameterRecordIoDispatcher(
    private val mainHandler: Handler,
    threadName: String = "parameter-record-io",
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    fun execute(task: () -> Unit): Boolean {
        try {
            executor.execute(task)
            return true
        } catch (_: RejectedExecutionException) {
            return false
        }
    }

    fun <T> submit(background: () -> T, onMain: (T) -> Unit): Boolean = execute {
        val result = background()
        mainHandler.post { onMain(result) }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
