/**
 * OpenClaw Source Reference:
 * - 无 OpenClaw 对应 (Android 平台独有)
 */
package com.xiaomo.androidforclaw.util

import com.xiaomo.androidforclaw.logging.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 局部布局异常记录器
 * 用于在各模块的 catch 中记录布局相关异常，方便快速定位失败模块
 */
object LayoutExceptionLogger {

    private const val TAG = "LayoutExceptionLogger"

    /**
     * 异常信息数据类
     */
    data class ExceptionInfo(
        val moduleName: String,
        val message: String,
        val stackTrace: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // 线程安全的异常队列
    private val exceptionQueue = ConcurrentLinkedQueue<ExceptionInfo>()

    // 防止递归调用的标志位（使用 ThreadLocal 确保线程安全）
    private val isLogging = ThreadLocal<Boolean>().apply { set(false) }

    /**
     * 记录异常信息（仅本地日志）
     */
    fun log(moduleName: String, throwable: Throwable) {
        // 防止递归调用：如果当前线程正在记录异常，则跳过
        if (isLogging.get() == true) {
            Log.w(TAG, "检测到Recursive调用，SkipAbnormalRecord以避免UnlimitedLoop。Module: $moduleName")
            return
        }

        // 设置标志位
        isLogging.set(true)

        try {
            // 记录到日志
            Log.e(
                TAG,
                "Module[$moduleName]执RowFailed，AbnormalInfo: ${throwable.message}",
                throwable
            )

            // 存储异常信息
            val stackTrace = throwable.stackTraceToString()
            val exceptionInfo = ExceptionInfo(
                moduleName = moduleName,
                message = throwable.message ?: "UnknownAbnormal",
                stackTrace = stackTrace,
                timestamp = System.currentTimeMillis()
            )
            exceptionQueue.offer(exceptionInfo)
        } finally {
            // 清除标志位
            isLogging.set(false)
        }
    }

    /**
     * 获取异常数量
     */
    fun getExceptionCount(): Int {
        return exceptionQueue.size
    }

    /**
     * 清空异常队列
     */
    fun clear() {
        exceptionQueue.clear()
    }
}