package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/bash-tools.exec.ts
 *
 * androidforClaw adaptation: agent tool implementation.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Exec tool - Execute shell commands
 * Reference: nanobot's Exectool
 */
class Exectool(
    private val timeout: Long = 60000L, // 60 seconds
    private val workingDir: String? = null
) : tool {
    companion object {
        private const val TAG = "Exectool"

        // Dangerous commands blacklist
        private val DENY_PATTERNS = listOf(
            Regex("""\brm\s+-[rf]{1,2}\b"""),           // rm -r, rm -rf
            Regex("""\bformat\b"""),                    // format
            Regex("""\b(shutdown|reboot|poweroff)\b"""), // system power
            Regex("""\b\s+if="""),                    //  command
        )
    }

    override val name = "exec"
    override val description = "Run shell commands (android built-in only: ls, cat, grep, find, getprop)"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "command" to Propertyschema("string", "Shell command to execute"),
                        "working_dir" to Propertyschema("string", "Optional working directory")
                    ),
                    required = listOf("command")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val command = args["command"] as? String
        val workDir = args["working_dir"] as? String ?: workingDir

        if (command == null) {
            return toolresult.error("Missing required parameter: command")
        }

        // Safety check
        val guardError = guardCommand(command)
        if (guardError != null) {
            return toolresult.error(guardError)
        }

        Log.d(TAG, "Executing command: $command")
        return withcontext(Dispatchers.IO) {
            try {
                val processBuilder = ProcessBuilder()
                if (workDir != null) {
                    processBuilder.directory(java.io.File(workDir))
                }

                // Split command (simple implementation, doesn't handle complex quotes)
                val cmdArray = if (command.contains(" ")) {
                    listOf("sh", "-c", command)
                } else {
                    command.split(" ")
                }

                processBuilder.command(cmdArray)
                processBuilder.redirectErrorStream(false)

                val process = processBuilder.start()

                // Wait with real process timeout (blocking IO immune to coroutine cancellation)
                val timeoutSec = (timeout / 1000).coerceAtLeast(5)
                val finished = process.waitfor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyforcibly()
                    return@withcontext toolresult.error("Command timed out after ${timeoutSec}s")
                }

                val result = run {
                    val stdout = process.inputStream.bufferedReader().readText()
                    val stderr = process.errorStream.bufferedReader().readText()

                    val exitCode = process.exitValue()

                    val rendered = buildString {
                        if (stdout.isnotEmpty()) {
                            append(stdout)
                        }
                        if (stderr.isnotEmpty()) {
                            if (isnotEmpty()) append("\n")
                            append("STDERR:\n$stderr")
                        }
                        if (exitCode != 0) {
                            if (isnotEmpty()) append("\n")
                            append("Exit code: $exitCode")
                        }
                    }.ifEmpty { "(no output)" }

                    mapOf(
                        "rendered" to rendered,
                        "stdout" to stdout,
                        "stderr" to stderr,
                        "exitCode" to exitCode
                    )
                }

                @Suppress("UNCHECKED_CAST")
                val rendered = result["rendered"] as String
                val stdout = result["stdout"] as String
                val stderr = result["stderr"] as String
                val exitCode = result["exitCode"] as Int

                // Truncate overly long output
                val maxLen = 10000
                val finalresult = if (rendered.length > maxLen) {
                    rendered.take(maxLen) + "\n... (truncated, ${rendered.length - maxLen} more chars)"
                } else {
                    rendered
                }

                toolresult.success(
                    finalresult,
                    metadata = mapOf(
                        "backend" to "android-internal",
                        "stdout" to stdout,
                        "stderr" to stderr,
                        "exitCode" to exitCode,
                        "working_dir" to (workDir ?: ""),
                        "command" to command
                    )
                )
            } catch (e: kotlinx.coroutines.Timeoutcancellationexception) {
                toolresult.error("Command timed out after ${timeout}ms")
            } catch (e: exception) {
                Log.e(TAG, "Command execution failed", e)
                toolresult.error("Command execution failed: ${e.message}")
            }
        }
    }

    /**
     * Safety check: Block dangerous commands
     */
    private fun guardCommand(command: String): String? {
        val lower = command.lowercase()

        for (pattern in DENY_PATTERNS) {
            if (pattern.containsMatchIn(lower)) {
                return "Command blocked by safety guard (dangerous pattern detected)"
            }
        }

        return null
    }
}
