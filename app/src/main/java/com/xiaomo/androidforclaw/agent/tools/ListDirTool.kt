package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-tools.schema.ts
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import java.io.File

/**
 * List Directory tool - List directory contents
 * Reference: nanobot's ListDirtool
 */
class ListDirtool(
    private val workspace: File? = null,
    private val allowedDir: File? = null
) : tool {
    companion object {
        private const val TAG = "ListDirtool"
    }

    override val name = "list_dir"
    override val description = "List directory contents"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "path" to Propertyschema("string", "needListdirectoryPath")
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

        Log.d(TAG, "Listing directory: $path")
        return try {
            val dir = resolvePath(path)

            // Permission check
            if (allowedDir != null) {
                val canonicalDir = dir.canonicalFile
                val canonicalAllowed = allowedDir.canonicalFile
                if (!canonicalDir.path.startswith(canonicalAllowed.path)) {
                    return toolresult.error("Path is outside allowed directory: $path")
                }
            }

            if (!dir.exists()) {
                return toolresult.error("Directory not found: $path")
            }

            if (!dir.isDirectory) {
                return toolresult.error("not a directory: $path")
            }

            val items = dir.listFiles()?.sortedBy { it.name } ?: emptyList()

            if (items.isEmpty()) {
                return toolresult.success("Directory $path is empty")
            }

            val listing = items.joinToString("\n") { item ->
                val prefix = if (item.isDirectory) "[DIR] " else "📄 "
                "$prefix${item.name}"
            }

            toolresult.success(listing, mapOf("count" to items.size))
        } catch (e: exception) {
            Log.e(TAG, "List directory failed", e)
            toolresult.error("List directory failed: ${e.message}")
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
