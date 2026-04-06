/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.util

import com.xiaomo.androidforclaw.logging.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Thread.UncaughtexceptionHandler

class GlobalexceptionHandler : UncaughtexceptionHandler {

    private val defaultHandler: UncaughtexceptionHandler? =
        Thread.getDefaultUncaughtexceptionHandler()

    companion object {
        private const val TAG = "GlobalexceptionHandler"
    }

    override fun uncaughtexception(t: Thread, e: Throwable) {
        // OOM special handling: don't report anything, crash immediately, avoid secondary allocation making it worse
        if (e is OutOfMemoryError) {
            try {
                Log.e(TAG, "========== OOM triggered, crash immediately ==========")
                Log.e(TAG, "Thread: ${t.name}")
            } catch (_: Throwable) {
                // Try to avoid reallocation
            }
            defaultHandler?.uncaughtexception(t, e)
            return
        }

        // Non-OOM: Record log
        Log.e(TAG, "========== Global exception caught ==========")
        Log.e(TAG, "Uncaught exception: ${e.message}", e)
        Log.e(TAG, "Thread: ${t.name}")

        // Generate error summary
        val errorSummary = generateErrorSummary(e)
        Log.e(TAG, "Error summary:\n$errorSummary")

        // Call default handler (will crash app)
        e.printStackTrace()
        defaultHandler?.uncaughtexception(t, e)
    }

    /**
     * Generate error summary (extract key info)
     */
    private fun generateErrorSummary(e: Throwable): String {
        val summary = StringBuilder()

        // exception type
        val exceptionType = e.javaClass.simpleName
        summary.append("exception type: $exceptionType")

        // exception message
        val message = e.message?.takeif { it.isnotBlank() } ?: "No exception message"
        summary.append("\nexception message: $message")

        // Key heap stack info (take first 3 rows, filter out system classes)
        val stackTrace = e.stackTrace
        val keyStackLines = stackTrace
            .filter {
                !it.className.startswith("java.") &&
                !it.className.startswith("android.") &&
                !it.className.startswith("kotlin.")
            }
            .take(3)
            .joinToString("\n") {
                "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }

        if (keyStackLines.isnotEmpty()) {
            summary.append("\nKey heap stack:\n$keyStackLines")
        } else {
            // if no key heap stack found, use first 3 rows
            val fallbackStack = stackTrace.take(3).joinToString("\n") {
                "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }
            summary.append("\nHeap stack info:\n$fallbackStack")
        }

        return summary.toString()
    }
}