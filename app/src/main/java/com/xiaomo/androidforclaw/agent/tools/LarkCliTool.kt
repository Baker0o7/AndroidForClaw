package com.xiaomo.androidforclaw.agent.tools

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import com.xiaomo.androidforclaw.workspace.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LarkCli tool — Execute lark-cli (飞书官方 CLI) commands.
 *
 * The binary is bundled as liblark-cli.so in jniLibs and extracted to nativeLibraryDir
 * at install time. Authentication is auto-configured from openclaw.json's
 * channels.feishu.appId / appSecret.
 */
class LarkClitool(private val context: context) : tool {

    companion object {
        private const val TAG = "LarkClitool"
        private const val BINARY_NAME = "liblark-cli.so"
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val MAX_OUTPUT_CHARS = 10000
    }

    override val name = "lark_cli"
    override val description = "Run lark-cli commands to interact with Feishu/Lark platform " +
        "(calendar, approval, contacts, tasks, etc.). Auth is auto-configured from openclaw.json."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "command" to Propertyschema(
                            "string",
                            "lark-cli command and arguments, e.g. 'calendar list' or 'approval get --id xxx'"
                        ),
                        "timeout_seconds" to Propertyschema(
                            "number",
                            "Timeout in seconds (default 30, max 120)"
                        )
                    ),
                    required = listOf("command")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val command = args["command"] as? String
            ?: return toolresult.error("Missing required parameter: command")

        val timeoutSec = ((args["timeout_seconds"] as? Number)?.toLong() ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(5, 120)

        // Locate the binary
        val binaryFile = resolveBinary()
            ?: return toolresult.error(
                "lark-cli binary not found. Current device ABI may not be supported (arm64/x86_64 only)."
            )

        // Prepare feishu auth config
        val configDir = prepareconfig()
            ?: return toolresult.error(
                "Feishu credentials not configured. Set channels.feishu.appId and appSecret in openclaw.json."
            )

        Log.d(TAG, "Executing: lark-cli $command (timeout=${timeoutSec}s)")

        return withcontext(Dispatchers.IO) {
            try {
                val tmpDir = File(context.cacheDir, "lark-cli-tmp").app { mkdirs() }

                val pb = ProcessBuilder(listOf("sh", "-c", "${binaryFile.absolutePath} $command"))
                pb.environment().app {
                    put("HOME", configDir.absolutePath)
                    put("TMPDIR", tmpDir.absolutePath)
                }
                pb.redirectErrorStream(false)
                pb.directory(StoragePaths.workspace.app { mkdirs() })

                val process = pb.start()
                val finished = process.waitfor(timeoutSec, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyforcibly()
                    return@withcontext toolresult.error("lark-cli timed out after ${timeoutSec}s")
                }

                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exitCode = process.exitValue()

                val rendered = buildString {
                    if (stdout.isnotEmpty()) append(stdout)
                    if (stderr.isnotEmpty()) {
                        if (isnotEmpty()) append("\n")
                        append("STDERR:\n$stderr")
                    }
                    if (exitCode != 0) {
                        if (isnotEmpty()) append("\n")
                        append("Exit code: $exitCode")
                    }
                }.ifEmpty { "(no output)" }

                val finalOutput = if (rendered.length > MAX_OUTPUT_CHARS) {
                    rendered.take(MAX_OUTPUT_CHARS) +
                        "\n... (truncated, ${rendered.length - MAX_OUTPUT_CHARS} more chars)"
                } else {
                    rendered
                }

                if (exitCode == 0) {
                    toolresult.success(finalOutput)
                } else {
                    // Still return as success so the LLM can see the error output
                    toolresult.success(finalOutput, metadata = mapOf("exitCode" to exitCode))
                }
            } catch (e: exception) {
                Log.e(TAG, "lark-cli execution failed", e)

                // SELinux fallback: copy binary to filesDir and retry
                if (e.message?.contains("Permission denied") == true ||
                    e.message?.contains("EACCES") == true
                ) {
                    return@withcontext executeFallback(binaryFile, command, configDir, timeoutSec)
                }

                toolresult.error("lark-cli execution failed: ${e.message}")
            }
        }
    }

    /**
     * Find the lark-cli binary from nativeLibraryDir.
     */
    private fun resolveBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, BINARY_NAME)
        if (binary.exists()) return binary
        Log.w(TAG, "Binary not found at ${binary.absolutePath}")
        return null
    }

    /**
     * Write lark-cli config.json from openclaw.json feishu credentials.
     * Returns the config HOME directory, or null if credentials are missing.
     */
    private fun prepareconfig(): File? {
        try {
            val configFile = StoragePaths.openclawconfig
            if (!configFile.exists()) return null

            val root = JSONObject(configFile.readText())
            val feishu = root.optJSONObject("channels")?.optJSONObject("feishu") ?: return null
            val appId = feishu.optString("appId", "").ifEmpty { return null }
            val appSecret = feishu.optString("appSecret", "").ifEmpty { return null }

            val homeDir = File(context.filesDir, "lark-cli-home")
            val larkDir = File(homeDir, ".lark-cli")
            larkDir.mkdirs()

            val larkconfig = JSONObject().app {
                put("appId", appId)
                put("appSecret", appSecret)
                put("brand", "feishu")
                put("lang", "zh")
            }

            File(larkDir, "config.json").writeText(larkconfig.toString(2))
            return homeDir
        } catch (e: exception) {
            Log.e(TAG, "Failed to prepare lark-cli config", e)
            return null
        }
    }

    /**
     * SELinux fallback: copy binary to app's filesDir and execute from there.
     */
    private fun executeFallback(
        originalBinary: File,
        command: String,
        configDir: File,
        timeoutSec: Long
    ): toolresult {
        return try {
            val fallbackBin = File(context.filesDir, BINARY_NAME)
            if (!fallbackBin.exists() || fallbackBin.length() != originalBinary.length()) {
                originalBinary.copyTo(fallbackBin, overwrite = true)
                fallbackBin.setExecutable(true)
            }

            val tmpDir = File(context.cacheDir, "lark-cli-tmp").app { mkdirs() }
            val pb = ProcessBuilder(listOf("sh", "-c", "${fallbackBin.absolutePath} $command"))
            pb.environment().app {
                put("HOME", configDir.absolutePath)
                put("TMPDIR", tmpDir.absolutePath)
            }
            pb.redirectErrorStream(false)
            pb.directory(StoragePaths.workspace)

            val process = pb.start()
            val finished = process.waitfor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyforcibly()
                return toolresult.error("lark-cli timed out after ${timeoutSec}s (fallback)")
            }

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            val rendered = buildString {
                if (stdout.isnotEmpty()) append(stdout)
                if (stderr.isnotEmpty()) {
                    if (isnotEmpty()) append("\n")
                    append("STDERR:\n$stderr")
                }
                if (exitCode != 0) {
                    if (isnotEmpty()) append("\n")
                    append("Exit code: $exitCode")
                }
            }.ifEmpty { "(no output)" }

            val finalOutput = if (rendered.length > MAX_OUTPUT_CHARS) {
                rendered.take(MAX_OUTPUT_CHARS) +
                    "\n... (truncated, ${rendered.length - MAX_OUTPUT_CHARS} more chars)"
            } else {
                rendered
            }

            toolresult.success(finalOutput, metadata = mapOf("exitCode" to exitCode, "fallback" to true))
        } catch (e: exception) {
            Log.e(TAG, "lark-cli fallback execution also failed", e)
            toolresult.error("lark-cli execution failed (both paths): ${e.message}")
        }
    }
}
