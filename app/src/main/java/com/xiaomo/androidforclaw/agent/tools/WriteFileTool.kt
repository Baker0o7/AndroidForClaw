package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/app-patch.ts
 *
 * androidforClaw adaptation: low-level file write tool.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import java.io.File

/**
 * Write File tool - Write to file
 * Reference: nanobot's WriteFiletool
 */
class WriteFiletool(
    private val workspace: File? = null,
    private val allowedDir: File? = null
) : tool {
    companion object {
        private const val TAG = "WriteFiletool"
    }

    override val name = "write_file"
    override val description = "Create or overwrite files"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "path" to Propertyschema("string", "needWriteFile path"),
                        "content" to Propertyschema("string", "needWritecontent")
                    ),
                    required = listOf("path", "content")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val path = args["path"] as? String
        val content = args["content"] as? String

        if (path == null || content == null) {
            return toolresult.error("Missing required parameters: path, content")
        }

        Log.d(TAG, "Writing file: $path (${content.length} bytes)")
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

            // Create parent directory
            file.parentFile?.mkdirs()

            // Write file
            file.writeText(content, Charsets.UTF_8)

            toolresult.success("Successfully wrote ${content.length} bytes to ${file.absolutePath}")
        } catch (e: exception) {
            Log.e(TAG, "Write file failed", e)
            toolresult.error("Write file failed: ${e.message}")
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
