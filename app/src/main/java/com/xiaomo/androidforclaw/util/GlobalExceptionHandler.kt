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
        // OOM 特殊Process: 不做任何Up报, 立刻崩溃, 避免二次分配导致雪Up加霜
        if (e is OutOfMemoryError) {
            try {
                Log.e(TAG, "========== OOM 触发, 直接崩溃 ==========")
                Log.e(TAG, "Thread: ${t.name}")
            } catch (_: Throwable) {
                // 尽量避免再分配
            }
            defaultHandler?.uncaughtException(t, e)
            return
        }

        // 非 OOM: RecordLog
        Log.e(TAG, "========== GlobalException捕获 ==========")
        Log.e(TAG, "未捕获的Exception: ${e.message}", e)
        Log.e(TAG, "Thread: ${t.name}")

        // 生成Errorsummarize
        val errorSummary = generateErrorSummary(e)
        Log.e(TAG, "Errorsummarize:\n$errorSummary")

        // callDefaultProcess器(会导致apply崩溃)
        e.printStackTrace()
        defaultHandler?.uncaughtException(t, e)
    }

    /**
     * 生成Errorsummarize(提取关KeyInfo)
     */
    private fun generateErrorSummary(e: Throwable): String {
        val summary = StringBuilder()

        // ExceptionType
        val exceptionType = e.javaClass.simpleName
        summary.append("ExceptionType: $exceptionType")

        // ExceptionMessage
        val message = e.message?.takeIf { it.isNotBlank() } ?: "No exception message"
        summary.append("\nExceptionMessage: $message")

        // 关KeyHeapStackInfo(取Front3Row, Filter掉系统Class)
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
            summary.append("\n关KeyHeapStack:\n$keyStackLines")
        } else {
            // ifNone找到关KeyHeapStack, useFront3Row
            val fallbackStack = stackTrace.take(3).joinToString("\n") {
                "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }
            summary.append("\nHeapStackInfo:\n$fallbackStack")
        }

        return summary.toString()
    }
}

