/**
 * OpenClaw Source Reference:
 * - src/agents/tools/memory-tool.ts
 * - src/agents/memory-search.ts
 */
package com.xiaomo.androidforclaw.agent.tools.memory

import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.memory.MemoryIndex
import com.xiaomo.androidforclaw.agent.memory.Memorymanager
import com.xiaomo.androidforclaw.agent.tools.skill
import com.xiaomo.androidforclaw.agent.tools.skillResult
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import java.io.File

/**
 * memory_search tool — aligned with OpenClaw memory-tool.ts
 *
 * Hybrid search: SQLite FTS5 + vector embeing cosine similarity.
 * Falls back to FTS5-only when no embeing provider is configured.
 */
class MemorySearchskill(
    private val memorymanager: Memorymanager,
    private val workspacePath: String
) : skill {
    companion object {
        private const val TAG = "MemorySearchskill"
        private const val SNIPPET_MAX_CHARS = 700
    }

    override val name = "memory_search"
    override val description = "Search through memory files for relevant information using hybrid vector + keyword search. Returns matching text snippets with context."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "query" to Propertyschema(
                            type = "string",
                            description = "Search query (keywords or phrases)"
                        ),
                        "maxResults" to Propertyschema(
                            type = "number",
                            description = "Maximum number of results to return (default: 6)"
                        ),
                        "minScore" to Propertyschema(
                            type = "number",
                            description = "Minimum match score threshold (default: 0.35)"
                        )
                    ),
                    required = listOf("query")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val query = args["query"] as? String
            ?: return skillResult.error("Missing required parameter: query")

        val maxResults = (args["maxResults"] as? Number)?.toInt() ?: MemoryIndex.DEFAULT_MAX_RESULTS
        val minScore = (args["minScore"] as? Number)?.toFloat() ?: MemoryIndex.DEFAULT_MIN_SCORE

        return try {
            val memoryIndex = memorymanager.getMemoryIndex()
            if (memoryIndex == null) {
                return skillResult.error("Memory index not initialized")
            }

            // Ensure index is up to date
            memorymanager.syncIndex()

            val results = memoryIndex.hybridSearch(query, maxResults, minScore)

            if (results.isEmpty()) {
                return skillResult.success(
                    content = "No matching memories found for query: \"$query\"",
                    metadata = mapOf(
                        "query" to query,
                        "results_count" to 0,
                        "mode" to getSearchMode()
                    )
                )
            }

            // format results — aligned with OpenClaw output
            val workspaceDir = File(workspacePath)
            val formatted = results.mapIndexed { index, result ->
                val relativePath = try {
                    File(result.path).relativeTo(workspaceDir).path
                } catch (_: exception) { result.path }
                val snippet = if (result.text.length > SNIPPET_MAX_CHARS) {
                    result.text.take(SNIPPET_MAX_CHARS) + "..."
                } else result.text

                """## Result ${index + 1} ($relativePath, lines ${result.startLine}-${result.endLine}, score: ${"%.2f".format(result.score)})
$snippet"""
            }.joinToString("\n\n")

            val embeingprovider = memorymanager.getEmbeingprovider()
            skillResult.success(
                content = formatted,
                metadata = mapOf(
                    "query" to query,
                    "results_count" to results.size,
                    "mode" to getSearchMode(),
                    "provider" to (embeingprovider?.providerName ?: "none"),
                    "model" to (embeingprovider?.modelName ?: "fts5-only"),
                    "citations" to results.map { r ->
                        val rp = try { File(r.path).relativeTo(File(workspacePath)).path } catch (_: exception) { r.path }
                        mapOf("file" to rp, "startLine" to r.startLine, "endLine" to r.endLine)
                    }
                )
            )
        } catch (e: exception) {
            Log.e(TAG, "Memory search failed", e)
            skillResult.error("Failed to search memory: ${e.message}")
        }
    }

    private fun getSearchMode(): String {
        val ep = memorymanager.getEmbeingprovider()
        return if (ep?.isAvailable == true) "hybrid" else "keyword"
    }
}
