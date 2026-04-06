package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-tools.schema.ts
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition
import java.io.File

/**
 * List Directory Tool - List directory contents
 * Reference: nanobot's ListDirTool
 */
class ListDirTool(
    private val workspace: File? = null,
    private val allowedDir: File? = null
) : Tool {
    companion object {
        private const val TAG = "ListDirTool"
    }

    override val name = "list_dir"
    override val description = "List directory contents"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "path" to PropertySchema("string", "要List的目录Path")
                    ),
                    required = listOf("path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        val path = args["path"] as? String

        if (path == null) {
            return Toolresult.error("Missing required parameter: path")
        }

        Log.d(TAG, "Listing directory: $path")
        return try {
            val dir = resolvePath(path)

            // Permission check
            if (allowedDir != null) {
                val canonicalDir = dir.canonicalFile
                val canonicalAllowed = allowedDir.canonicalFile
                if (!canonicalDir.path.startsWith(canonicalAllowed.path)) {
                    return Toolresult.error("Path is outside allowed directory: $path")
                }
            }

            if (!dir.exists()) {
                return Toolresult.error("Directory not found: $path")
            }

            if (!dir.isDirectory) {
                return Toolresult.error("Not a directory: $path")
            }

            val items = dir.listFiles()?.sortedBy { it.name } ?: emptyList()

            if (items.isEmpty()) {
                return Toolresult.success("Directory $path is empty")
            }

            val listing = items.joinToString("\n") { item ->
                val prefix = if (item.isDirectory) "📁 " else "📄 "
                "$prefix${item.name}"
            }

            Toolresult.success(listing, mapOf("count" to items.size))
        } catch (e: Exception) {
            Log.e(TAG, "List directory failed", e)
            Toolresult.error("List directory failed: ${e.message}")
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
