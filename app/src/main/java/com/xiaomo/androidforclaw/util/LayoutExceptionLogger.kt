/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.util

import com.xiaomo.androidforclaw.logging.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Local布局ExceptionRecord器
 * 用于在各Module的 catch 中Record布局相关Exception, 方便Fast定位FailedModule
 */
object LayoutExceptionLogger {

    private const val TAG = "LayoutExceptionLogger"

    /**
     * ExceptionInfoDataClass
     */
    data class ExceptionInfo(
        val moduleName: String,
        val message: String,
        val stackTrace: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ThreadSafe的ExceptionQueue
    private val exceptionQueue = ConcurrentLinkedQueue<ExceptionInfo>()

    // PreventRecursecall的标志位(use ThreadLocal EnsureThreadSafe)
    private val isLogging = ThreadLocal<Boolean>().apply { set(false) }

    /**
     * RecordExceptionInfo(仅本地Log)
     */
    fun log(moduleName: String, throwable: Throwable) {
        // PreventRecursecall: if当FrontThread正在RecordException, 则Skip
        if (isLogging.get() == true) {
            Log.w(TAG, "DetectedRecursecall, SkipExceptionRecord以避免None限Loop. Module: $moduleName")
            return
        }

        // Settings标志位
        isLogging.set(true)

        try {
            // Record到Log
            Log.e(
                TAG,
                "Module[$moduleName]执RowFailed, ExceptionInfo: ${throwable.message}",
                throwable
            )

            // StorageExceptionInfo
            val stackTrace = throwable.stackTraceToString()
            val exceptionInfo = ExceptionInfo(
                moduleName = moduleName,
                message = throwable.message ?: "Unknown exception",
                stackTrace = stackTrace,
                timestamp = System.currentTimeMillis()
            )
            exceptionQueue.offer(exceptionInfo)
        } finally {
            // clear标志位
            isLogging.set(false)
        }
    }

    /**
     * GetException数量
     */
    fun getExceptionCount(): Int {
        return exceptionQueue.size
    }

    /**
     * 清NullExceptionQueue
     */
    fun clear() {
        exceptionQueue.clear()
    }
}