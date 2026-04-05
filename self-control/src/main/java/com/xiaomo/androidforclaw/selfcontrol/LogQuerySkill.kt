package com.xiaomo.androidforclaw.selfcontrol

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.util.Log
import com.xiaomo.androidforclaw.selfcontrol.Skill
import com.xiaomo.androidforclaw.selfcontrol.SkillResult
import com.xiaomo.androidforclaw.selfcontrol.FunctionDefinition
import com.xiaomo.androidforclaw.selfcontrol.ParametersSchema
import com.xiaomo.androidforclaw.selfcontrol.PropertySchema
import com.xiaomo.androidforclaw.selfcontrol.ToolDefinition
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Self-Control Log Query Skill
 *
 * Query PhoneForClaw runtime logs, enabling AI Agent to:
 * - Read application logs (logcat)
 * - Query errors and exceptions
 * - Analyze runtime status
 * - Self-diagnose problems
 *
 * Use cases:
 * - Debug failed operations
 * - Analyze crash causes
 * - Performance monitoring
 * - Automated problem diagnosis
 */
class LogQuerySkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "LogQuerySkill"
        private const val MAX_LOG_LINES = 200

        object LogLevel {
            const val VERBOSE = "V"
            const val DEBUG = "D"
            const val INFO = "I"
            const val WARN = "W"
            const val ERROR = "E"
            const val FATAL = "F"
        }
    }

    override val name = "query_logs"

    override val description = """
        Query PhoneForClaw application runtime logs.

        Supported parameters:
        - level: Log level (V/D/I/W/E/F), default I (Info and above)
        - filter: Filter keyword (TAG or message content)
        - lines: Number of lines to return, default 100 (max 200)
        - source: Log source (logcat/file), default logcat

        Log levels:
        - V: Verbose (verbose)
        - D: Debug (debug)
        - I: Info (info)
        - W: Warning (warning)
        - E: Error (error)
        - F: Fatal (fatal)

        Examples:
        - View recent errors: {"level": "E", "lines": 50}
        - Search specific TAG: {"filter": "AgentLoop", "lines": 100}
        - View all logs: {"level": "V", "lines": 200}

        Note: Requires READ_LOGS permission (System UID).
    """.trimIndent()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "level" to PropertySchema(
                            type = "string",
                            description = "Log level",
                            enum = listOf(
                                LogLevel.VERBOSE,
                                LogLevel.DEBUG,
                                LogLevel.INFO,
                                LogLevel.WARN,
                                LogLevel.ERROR,
                                LogLevel.FATAL
                            )
                        ),
                        "filter" to PropertySchema(
                            type = "string",
                            description = "Filter keyword (TAG or message content)"
                        ),
                        "lines" to PropertySchema(
                            type = "integer",
                            description = "Number of lines to return (1-200)"
                        ),
                        "source" to PropertySchema(
                            type = "string",
                            description = "Log source",
                            enum = listOf("logcat", "file")
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val level = args["level"] as? String ?: LogLevel.INFO
        val filter = args["filter"] as? String
        val lines = ((args["lines"] as? Number)?.toInt() ?: 100).coerceIn(1, MAX_LOG_LINES)
        val source = args["source"] as? String ?: "logcat"

        return try {
            when (source) {
                "logcat" -> queryLogcat(level, filter, lines)
                "file" -> queryLogFile(filter, lines)
                else -> SkillResult.error("Unknown log source: $source")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query logs", e)
            SkillResult.error("Log query failed: ${e.message}")
        }
    }

    private fun queryLogcat(level: String, filter: String?, lines: Int): SkillResult {
        return try {
            val packageName = context.packageName
            val levelFilter = when (level) {
                LogLevel.VERBOSE -> "*:V"
                LogLevel.DEBUG -> "*:D"
                LogLevel.INFO -> "*:I"
                LogLevel.WARN -> "*:W"
                LogLevel.ERROR -> "*:E"
                LogLevel.FATAL -> "*:F"
                else -> "*:I"
            }

            // Build logcat command
            val command = mutableListOf(
                "logcat",
                "-d",                           // dump mode
                "-t", lines.toString(),         // last N lines
                levelFilter,                    // log level
                "--pid=${android.os.Process.myPid()}"  // only this process
            )

            // Execute command
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val logLines = mutableListOf<String>()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                // Apply filter
                if (filter != null && !currentLine.contains(filter, ignoreCase = true)) {
                    continue
                }

                logLines.add(currentLine)
            }

            reader.close()
            process.waitFor()

            if (logLines.isEmpty()) {
                return SkillResult.success(
                    "No matching logs found${if (filter != null) " (filter: $filter)" else ""}",
                    mapOf("count" to 0)
                )
            }

            val summary = buildString {
                appendLine("[Log query result]")
                appendLine("Level: $level")
                if (filter != null) {
                    appendLine("Filter: $filter")
                }
                appendLine("Lines: ${logLines.size}")
                appendLine()
                appendLine("--- Log content ---")
                logLines.takeLast(lines).forEach { appendLine(it) }
            }

            SkillResult.success(
                summary,
                mapOf(
                    "level" to level,
                    "filter" to (filter ?: "none"),
                    "count" to logLines.size,
                    "lines" to logLines.takeLast(lines)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query logcat", e)
            SkillResult.error("Logcat query failed: ${e.message}")
        }
    }

    private fun queryLogFile(filter: String?, lines: Int): SkillResult {
        return try {
            // Try to read application log file (if exists)
            val logDir = File(context.getExternalFilesDir(null), "logs")

            if (!logDir.exists() || !logDir.isDirectory) {
                return SkillResult.error("Log directory does not exist: ${logDir.absolutePath}")
            }

            val logFiles = logDir.listFiles { file ->
                file.isFile && file.extension == "log"
            }?.sortedByDescending { it.lastModified() }

            if (logFiles.isNullOrEmpty()) {
                return SkillResult.error("No log files found")
            }

            // Read the latest log file
            val latestLog = logFiles.first()
            val logLines = mutableListOf<String>()

            latestLog.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    if (filter == null || line.contains(filter, ignoreCase = true)) {
                        logLines.add(line)
                    }
                }
            }

            if (logLines.isEmpty()) {
                return SkillResult.success(
                    "No matching content found in log file${if (filter != null) " (filter: $filter)" else ""}",
                    mapOf("file" to latestLog.name, "count" to 0)
                )
            }

            val summary = buildString {
                appendLine("[Log file query result]")
                appendLine("File: ${latestLog.name}")
                appendLine("Size: ${latestLog.length() / 1024} KB")
                appendLine("Modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(latestLog.lastModified())}")
                if (filter != null) {
                    appendLine("Filter: $filter")
                }
                appendLine("Matching lines: ${logLines.size}")
                appendLine()
                appendLine("--- Log content (last $lines lines) ---")
                logLines.takeLast(lines).forEach { appendLine(it) }
            }

            SkillResult.success(
                summary,
                mapOf(
                    "file" to latestLog.name,
                    "filter" to (filter ?: "none"),
                    "count" to logLines.size,
                    "lines" to logLines.takeLast(lines)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query log file", e)
            SkillResult.error("Log file query failed: ${e.message}")
        }
    }
}
