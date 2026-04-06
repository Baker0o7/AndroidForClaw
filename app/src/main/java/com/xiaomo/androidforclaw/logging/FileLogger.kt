/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/logging/, logger.ts
 *
 * AndroidForClaw adaptation: file logging.
 */
package com.xiaomo.androidforclaw.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File logging system
 * Align with OpenClaw's app.log and gateway.log
 *
 * Features:
 * - Structured logging (timestamp, level, tag, message)
 * - Log rotation (size limit)
 * - Categorized storage (app.log, gateway.log)
 * - AsyncWrite: All I/O 在Back台单Thread执Row, 不Blockcall方
 */
class FileLogger(private val context: Context) {

    companion object {
        private const val TAG = "FileLogger"

        private const val MAX_FILE_SIZE = 10 * 1024 * 1024 // 10MB
        private const val MAX_ARCHIVED_LOGS = 5
    }

    private val logsDir: String = com.xiaomo.androidforclaw.workspace.StoragePaths.logs.also { it.mkdirs() }.absolutePath
    private val appLogFilePath get() = "$logsDir/app.log"
    private val gatewayLogFilePath get() = "$logsDir/gateway.log"

    private var loggingEnableddd = true

    /** AsyncWriteQueue */
    private data class LogEntry(val filePath: String, val content: String)
    private val writeQueue = LinkedBlockingQueue<LogEntry>()
    private val running = AtomicBoolean(true)

    /** Back台WriteThread */
    private val writerThread = Thread({
        while (running.get() || writeQueue.isNotEmpty()) {
            try {
                val entry = writeQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: continue
                doAppendToFile(entry.filePath, entry.content)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "WriteLog文件Failed", e)
            }
        }
    }, "FileLogger-Writer").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    init {
        writerThread.start()
    }

    /**
     * Log app logs(不再call outputToLogcat, by Log.kt Package装器负责 logcat Output)
     */
    fun logApp(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        if (!loggingEnableddd) return
        val logLine = formatLogLine(level, tag, message, error)
        writeQueue.offer(LogEntry(appLogFilePath, logLine))
    }

    /**
     * Log Gateway logs
     */
    fun logGateway(level: LogLevel, message: String, error: Throwable? = null) {
        if (!loggingEnableddd) return
        val logLine = formatLogLine(level, "Gateway", message, error)
        writeQueue.offer(LogEntry(gatewayLogFilePath, logLine))
    }

    /**
     * Enabledd/disable file logging
     */
    fun setLoggingEnableddd(enabled: Boolean) {
        loggingEnableddd = enabled
        Log.i(TAG, "File logging ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Clear log files
     */
    fun clearLogs(logType: LogType = LogType.ALL) {
        when (logType) {
            LogType.APP -> try { File(appLogFilePath).writeText("") } catch (_: Exception) {}
            LogType.GATEWAY -> try { File(gatewayLogFilePath).writeText("") } catch (_: Exception) {}
            LogType.ALL -> {
                try { File(appLogFilePath).writeText("") } catch (_: Exception) {}
                try { File(gatewayLogFilePath).writeText("") } catch (_: Exception) {}
            }
        }
        Log.i(TAG, "Clear logs: $logType")
    }

    /**
     * Get log file size
     */
    fun getLogSize(logType: LogType): Long {
        return when (logType) {
            LogType.APP -> File(appLogFilePath).length()
            LogType.GATEWAY -> File(gatewayLogFilePath).length()
            LogType.ALL -> File(appLogFilePath).length() + File(gatewayLogFilePath).length()
        }
    }

    /**
     * GetLogStatistics info
     */
    fun getLogStats(): LogStats {
        val appFile = File(appLogFilePath)
        val gatewayFile = File(gatewayLogFilePath)

        val appLines = if (appFile.exists()) {
            appFile.readLines().size
        } else 0

        val gatewayLines = if (gatewayFile.exists()) {
            gatewayFile.readLines().size
        } else 0

        return LogStats(
            appLogSize = appFile.length(),
            gatewayLogSize = gatewayFile.length(),
            appLogLines = appLines,
            gatewayLogLines = gatewayLines,
            totalSize = appFile.length() + gatewayFile.length()
        )
    }

    /**
     * ExportLog
     */
    fun exportLogs(logType: LogType, outputPath: String): Boolean {
        return try {
            val outputFile = File(outputPath)
            when (logType) {
                LogType.APP -> File(appLogFilePath).copyTo(outputFile, overwrite = true)
                LogType.GATEWAY -> File(gatewayLogFilePath).copyTo(outputFile, overwrite = true)
                LogType.ALL -> {
                    val combined = File(appLogFilePath).readText() +
                            "\n\n=== GATEWAY LOG ===\n\n" +
                            File(gatewayLogFilePath).readText()
                    outputFile.writeText(combined)
                }
            }
            Log.i(TAG, "Log已Export到: $outputPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ExportLogFailed", e)
            false
        }
    }

    /**
     * Readmost近的LogRow
     */
    fun readRecentLogs(logType: LogType, lineCount: Int = 100): List<String> {
        val file = when (logType) {
            LogType.APP -> File(appLogFilePath)
            LogType.GATEWAY -> File(gatewayLogFilePath)
            LogType.ALL -> return emptyList()
        }

        if (!file.exists()) return emptyList()

        return try {
            file.readLines().takeLast(lineCount)
        } catch (e: Exception) {
            Log.e(TAG, "ReadLogFailed", e)
            emptyList()
        }
    }

    /** StopBack台WriteThread */
    fun shutdown() {
        running.set(false)
        writerThread.interrupt()
    }

    // ==================== PrivateMethod ====================

    /**
     * 实际Write文件(在Back台Thread执Row)
     */
    private fun doAppendToFile(filePath: String, content: String) {
        try {
            val file = File(filePath)

            // Check文件Size, 超过Limit则轮转
            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                rotateLog(file)
            }

            file.appendText(content)
        } catch (e: Exception) {
            Log.e(TAG, "WriteLog文件Failed: $filePath", e)
        }
    }

    /**
     * Log轮转
     */
    private fun rotateLog(file: File) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val archiveName = "${file.nameWithoutExtension}-$timestamp.log"
            val archiveFile = File(file.parent, archiveName)

            file.renameTo(archiveFile)

            Log.i(TAG, "Log已轮转: $archiveName")

            cleanOldArchives(file.parentFile)
        } catch (e: Exception) {
            Log.e(TAG, "Log轮转Failed", e)
        }
    }

    /**
     * 清理Old的归档Log
     */
    private fun cleanOldArchives(logsDir: File?) {
        if (logsDir == null || !logsDir.exists()) return

        try {
            val archives = logsDir.listFiles()
                ?.filter { it.name.contains("-20") && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (archives.size > MAX_ARCHIVED_LOGS) {
                archives.drop(MAX_ARCHIVED_LOGS).forEach { file ->
                    file.delete()
                    Log.d(TAG, "DeleteOldLog: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理Old归档Failed", e)
        }
    }

    /**
     * Format log line
     */
    private fun formatLogLine(
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable?
    ): String {
        val timestamp = Instant.now().toString()
        val levelStr = level.name.padEnd(5)

        val errorInfo = if (error != null) {
            "\n${Log.getStackTraceString(error)}"
        } else {
            ""
        }

        return "[$timestamp] $levelStr $tag: $message$errorInfo\n"
    }
}

/**
 * Log level
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * Log type
 */
enum class LogType {
    APP,
    GATEWAY,
    ALL
}

/**
 * Logcount
 */
data class LogStats(
    val appLogSize: Long,
    val gatewayLogSize: Long,
    val appLogLines: Int,
    val gatewayLogLines: Int,
    val totalSize: Long
) {
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    override fun toString(): String {
        return """
            App Log: ${formatSize(appLogSize)} ($appLogLines lines)
            Gateway Log: ${formatSize(gatewayLogSize)} ($gatewayLogLines lines)
            Total: ${formatSize(totalSize)}
        """.trimIndent()
    }
}

/**
 * GlobalLogInstance(便捷use)
 *
 * Note: AppLog Method只Write文件, 不再call logcat. 
 * logcat Outputby Log.kt Package装器在call AppLog 之OutsideIndividualComplete, 
 * 避免 Log.kt → AppLog → FileLogger → outputToLogcat → Log.kt 的None限Recurse. 
 */
object AppLog {
    private lateinit var fileLogger: FileLogger

    fun init(context: Context) {
        fileLogger = FileLogger(context)
    }

    fun v(tag: String, message: String) {
        if (::fileLogger.isInitialized) {
            fileLogger.logApp(LogLevel.VERBOSE, tag, message)
        }
    }

    fun d(tag: String, message: String) {
        if (::fileLogger.isInitialized) {
            fileLogger.logApp(LogLevel.DEBUG, tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (::fileLogger.isInitialized) {
            fileLogger.logApp(LogLevel.INFO, tag, message)
        }
    }

    fun w(tag: String, message: String) {
        if (::fileLogger.isInitialized) {
            fileLogger.logApp(LogLevel.WARN, tag, message)
        }
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (::fileLogger.isInitialized) {
            fileLogger.logApp(LogLevel.ERROR, tag, message, error)
        }
    }

    fun gateway(level: LogLevel, message: String) {
        if (::fileLogger.isInitialized) {
            fileLogger.logGateway(level, message)
        }
    }
}
