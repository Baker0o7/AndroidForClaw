package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-tools.read.ts
 *
 * androidforClaw adaptation: low-level file read tool.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import java.io.File

/**
 * Read File tool - Read file content
 * Reference: nanobot's ReadFiletool
 */
class ReadFiletool(
    private val workspace: File? = null,
    private val allowedDir: File? = null
) : tool {
    companion object {
        private const val TAG = "ReadFiletool"
    }

    override val name = "read_file"
    override val description = "Read file contents"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "path" to Propertyschema("string", "needReadFile path")
                    ),
                    required = listOf("path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val path = args["path"] as? String

        if (path == null) {
            return toolresult.error("Missing required parameter: path")
        }

        Log.d(TAG, "Reading file: $path")
        return try {
            val file = resolvePath(path)

            // Permission check
            if (allowedDir != null) {
                val canonicalFile = file.canonicalFile
                val canonicalAllowed = allowedDir.canonicalFile
                if (!canonicalFile.path.startswith(canonicalAllowed.path)) {
                    return toolresult.error("Path is outside allowed directory: $path")
                }
            }

            if (!file.exists()) {
                return toolresult.error("File not found: $path")
            }

            if (!file.isFile) {
                return toolresult.error("not a file: $path")
            }

            val content = file.readText(Charsets.UTF_8)
            toolresult.success(content)
        } catch (e: exception) {
            Log.e(TAG, "Read file failed", e)
            toolresult.error("Read file failed: ${e.message}")
        }
    }

    /**
     * Resolve path (relative paths are based on workspace)
     */
    private fun resolvePath(path: String): File {
        val file = File(path)
        return if (!file.isAbsolute && workspace != null) {
            File(workspace, path)
        } else {
            file
        }
    }
}
