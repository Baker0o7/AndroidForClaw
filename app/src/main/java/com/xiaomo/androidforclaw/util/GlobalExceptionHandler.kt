/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.util

import com.xiaomo.androidforclaw.logging.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Thread.UncaughtExceptionHandler

class GlobalExceptionHandler : UncaughtExceptionHandler {

    private val defaultHandler: UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private const val TAG = "GlobalExceptionHandler"
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        // OOM special handling: don't report anything, crash immediately, avoid secondary allocation making it worse
        if (e is OutOfMemoryError) {
            try {
                Log.e(TAG, "========== OOM triggered, crash immediately ==========")
                Log.e(TAG, "Thread: ${t.name}")
            } catch (_: Throwable) {
                // Try to avoid reallocation
            }
            defaultHandler?.uncaughtException(t, e)
            return
        }

        // Non-OOM: Record log
        Log.e(TAG, "========== Global Exception caught ==========")
        Log.e(TAG, "Uncaught exception: ${e.message}", e)
        Log.e(TAG, "Thread: ${t.name}")

        // Generate error summary
        val errorSummary = generateErrorSummary(e)
        Log.e(TAG, "Error summary:\n$errorSummary")

        // Call default handler (will crash app)
        e.printStackTrace()
        defaultHandler?.uncaughtException(t, e)
    }

    /**
     * Generate error summary (extract key info)
     */
    private fun generateErrorSummary(e: Throwable): String {
        val summary = StringBuilder()

        // Exception type
        val exceptionType = e.javaClass.simpleName
        summary.append("Exception type: $exceptionType")

        // Exception message
        val message = e.message?.takeIf { it.isNotBlank() } ?: "No exception message"
        summary.append("\nException message: $message")

        // Key heap stack info (take first 3 rows, filter out system classes)
        val stackTrace = e.stackTrace
        val keyStackLines = stackTrace
            .filter {
                !it.className.startsWith("java.") &&
                !it.className.startsWith("android.") &&
                !it.className.startsWith("kotlin.")
            }
            .take(3)
            .joinToString("\n") {
                "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }

        if (keyStackLines.isNotEmpty()) {
            summary.append("\nKey heap stack:\n$keyStackLines")
        } else {
            // If no key heap stack found, use first 3 rows
            val fallbackStack = stackTrace.take(3).joinToString("\n") {
                "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }
            summary.append("\nHeap stack info:\n$fallbackStack")
        }

        return summary.toString()
    }
}