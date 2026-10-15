package com.spamshield.android

import android.app.Activity
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Shared worker pool for database, content provider and network work. */
object Background {
    private const val TAG = "SpamShield"
    private val counter = AtomicInteger()

    val executor: ExecutorService = Executors.newFixedThreadPool(3, ThreadFactory { r ->
        Thread(r, "spamshield-bg-${counter.incrementAndGet()}").apply { isDaemon = true }
    })

    fun run(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "Background task failed", t)
            }
        }
    }
}

/**
 * Runs [work] off the main thread and delivers its result (or failure) to [onResult] on the UI
 * thread, unless the activity has been destroyed meanwhile.
 */
fun <T> Activity.runAsync(work: () -> T, onResult: (Result<T>) -> Unit) {
    Background.executor.execute {
        val result = runCatching(work)
        runOnUiThread { if (!isDestroyed) onResult(result) }
    }
}
