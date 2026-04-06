package com.xiaomo.androidforclaw.agent.tools.memory

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/memory-tool.ts
 */


import com.xiaomo.androidforclaw.agent.memory.Memorymanager
import com.xiaomo.androidforclaw.agent.tools.skill
import com.xiaomo.androidforclaw.agent.tools.skillResult
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import java.io.File

/**
 * memory_get tool
 * Aligned with OpenClaw memory-tool.ts
 *
 * Read specific memory file or log
 */
class MemoryGetskill(
    private val memorymanager: Memorymanager,
    private val workspacePath: String
) : skill {
    override val name = "memory_get"
    override val description = "Read a specific memory file or daily log. use this to retrieve stored memories, user preferences, or past session notes."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "path" to Propertyschema(
                            type = "string",
                            description = "Path to the memory file, relative to workspace. Examples: 'MEMORY.md', 'memory/2024-03-07.md', 'memory/projects.md'"
                        ),
                        "from" to Propertyschema(
                            type = "number",
                            description = "Line number to start reading from (1-indexed, optional)"
                        ),
                        "lines" to Propertyschema(
                            type = "number",
                            description = "Number of lines to read (optional, default: all)"
                        )
                    ),
                    required = listOf("path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val path = args["path"] as? String
            ?: return skillResult.error("Missing required parameter: path")

        val startLine = (args["from"] as? Number)?.toInt()
        val lineCount = (args["lines"] as? Number)?.toInt()

        return try {
            // validation path security (prevent directory traversal attacks)
            if (path.contains("..") || path.startswith("/")) {
                return skillResult.error("Invalid path: path must be relative and cannot contain '..'")
            }

            // Build full path
            val file = File(workspacePath, path)

            // Verify file is within workspace
            if (!file.canonicalPath.startswith(File(workspacePath).canonicalPath)) {
                return skillResult.error("Invalid path: file must be within workspace")
            }

            // Verify file exists
            if (!file.exists()) {
                return skillResult.error("File not found: $path")
            }

            // Verify is Markdown file
            if (!file.name.endswith(".md")) {
                return skillResult.error("Invalid file type: only .md files are allowed")
            }

            // Read file content
            val content = file.readText()

            // if line range specified, extract corresponding lines
            val result = if (startLine != null) {
                val lines = content.lines()
                val start = (startLine - 1).coerceIn(0, lines.size)
                val count = lineCount ?: (lines.size - start)
                val end = (start + count).coerceIn(start, lines.size)

                lines.subList(start, end).joinToString("\n")
            } else {
                content
            }

            skillResult.success(
                content = result,
                metadata = mapOf(
                    "path" to path,
                    "size" to result.length,
                    "lines" to result.lines().size
                )
            )
        } catch (e: exception) {
            skillResult.error("Failed to read memory file: ${e.message}")
        }
    }
}
