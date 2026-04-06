/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.util

import com.xiaomo.androidforclaw.logging.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Local layout exception recorder
 * Used to record layout-related exceptions in module catch blocks, for fast locating of failed modules
 */
object LayoutExceptionLogger {

    private const val TAG = "LayoutExceptionLogger"

    /**
     * Exception info data class
     */
    data class ExceptionInfo(
        val moduleName: String,
        val message: String,
        val stackTrace: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Thread-safe exception queue
    private val exceptionQueue = ConcurrentLinkedQueue<ExceptionInfo>()

    // Prevent recursive call flag (use ThreadLocal to ensure thread safety)
    private val isLogging = ThreadLocal<Boolean>().apply { set(false) }

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
                "Module[$moduleName] execution failed, Exception info: ${throwable.message}",
                throwable
            )

            // Store exception info
            val stackTrace = throwable.stackTraceToString()
            val exceptionInfo = ExceptionInfo(
                moduleName = moduleName,
                message = throwable.message ?: "Unknown exception",
                stackTrace = stackTrace,
                timestamp = System.currentTimeMillis()
            )
            exceptionQueue.offer(exceptionInfo)
        } finally {
            // Clear flag
            isLogging.set(false)
        }
    }

    /**
     * Get exception count
     */
    fun getExceptionCount(): Int {
        return exceptionQueue.size
    }

    /**
     * Clear exception queue
     */
    fun clear() {
        exceptionQueue.clear()
    }
}