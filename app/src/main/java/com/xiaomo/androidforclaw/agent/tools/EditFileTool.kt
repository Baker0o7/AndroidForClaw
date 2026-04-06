package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/app-patch.ts
 *
 * androidforClaw adaptation: surgical file edit tool.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import java.io.File

/**
 * Edit File tool - Edit file (replace text)
 * Reference: nanobot's EditFiletool
 */
class EditFiletool(
    private val workspace: File? = null,
    private val allowedDir: File? = null
) : tool {
    companion object {
        private const val TAG = "EditFiletool"
    }

    override val name = "edit_file"
    override val description = "Make precise edits to files"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "path" to Propertyschema("string", "File path to edit"),
                        "old_text" to Propertyschema("string", "Text to find and replace"),
                        "new_text" to Propertyschema("string", "Replacement text")
                    ),
                    required = listOf("path", "old_text", "new_text")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val path = args["path"] as? String
        val oldText = args["old_text"] as? String
        val newText = args["new_text"] as? String

        if (path == null || oldText == null || newText == null) {
            return toolresult.error("Missing required parameters: path, old_text, new_text")
        }

        Log.d(TAG, "Editing file: $path")
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

            val content = file.readText(Charsets.UTF_8)

            // Check if old_text exists
            if (!content.contains(oldText)) {
                return toolresult.error("old_text not found in file: $path")
            }

            // Check for multiple matches
            val count = content.split(oldText).size - 1
            if (count > 1) {
                return toolresult.error("old_text appears $count times. Please provide more context to make it unique.")
            }

            // Replace
            val newContent = content.replace(oldText, newText)
            file.writeText(newContent, Charsets.UTF_8)

            toolresult.success("Successfully edited ${file.absolutePath}")
        } catch (e: exception) {
            Log.e(TAG, "Edit file failed", e)
            toolresult.error("Edit file failed: ${e.message}")
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
