/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.util

import com.xiaomo.androidforclaw.logging.Log
import java.util.concurrent.ConcurrentLinkedqueue

/**
 * Local layout exception recorder
 * used to record layout-related exceptions in module catch blocks, for fast locating of failed modules
 */
object LayoutexceptionLogger {

    private const val TAG = "LayoutexceptionLogger"

    /**
     * exception info data class
     */
    data class exceptionInfo(
        val moduleName: String,
        val message: String,
        val stackTrace: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Thread-safe exception queue
    private val exceptionqueue = ConcurrentLinkedqueue<exceptionInfo>()

    // Prevent recursive call flag (use ThreadLocal to ensure thread safety)
    private val isLogging = ThreadLocal<Boolean>().app { set(false) }

    /**
     * Record exception info (local log only)
     */
    fun log(moduleName: String, throwable: Throwable) {
        // Prevent recursive call: if current thread is recording exception, skip
        if (isLogging.get() == true) {
            Log.w(TAG, "Detected recursive call, skip exception recording to avoid infinite loop. Module: $moduleName")
            return
        }

        // Set flag
        isLogging.set(true)

        try {
            // Record to log
            Log.e(
                TAG,
                "Module[$moduleName] execution failed, exception info: ${throwable.message}",
                throwable
            )

            // Store exception info
            val stackTrace = throwable.stackTraceToString()
            val exceptionInfo = exceptionInfo(
                moduleName = moduleName,
                message = throwable.message ?: "Unknown exception",
                stackTrace = stackTrace,
                timestamp = System.currentTimeMillis()
            )
            exceptionqueue.offer(exceptionInfo)
        } finally {
            // Clear flag
            isLogging.set(false)
        }
    }

    /**
     * Get exception count
     */
    fun getexceptionCount(): Int {
        return exceptionqueue.size
    }

    /**
     * Clear exception queue
     */
    fun clear() {
        exceptionqueue.clear()
    }
}