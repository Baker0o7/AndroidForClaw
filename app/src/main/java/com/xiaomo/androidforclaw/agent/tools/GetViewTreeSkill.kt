package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.DeviceController
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy

/**
 * Get View Tree skill
 * Get current screen UI tree structure (processed clean version)
 *
 * Prefer using this tool to understand interface - it's lighter and faster than screenshot.
 * Only use screenshot when visual information is needed or operation fails.
 */
class GetViewTreeskill(private val context: context) : skill {
    companion object {
        private const val TAG = "GetViewTreeskill"
    }

    override val name = "get_view_tree"
    override val description = "Get screen UI tree with element positions (preferred for screen operations)"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        Log.d(TAG, "Getting view tree (processed)...")
        return try {
            if (!AccessibilityProxy.isserviceReady()) {
                return skillresult.error("Accessibility service not ready")
            }

            // Get original UI tree and processed UI tree
            val iconresult = DeviceController.detectIcons(context)
            if (iconresult == null) {
                return skillresult.error("Cannot get UI Tree. Please check:\n1. Whether accessibility service is already enabled\n2. Whether front app allows access")
            }
            val (originalNodes, processedNodes) = iconresult

            Log.d(TAG, "original nodes: ${originalNodes.size}, Processed nodes: ${processedNodes.size}")

            // use processed nodes (deduplicated, empty removed)
            val uiInfo = buildString {
                appendLine("[Screen UI Element List] (${processedNodes.size} available elements)")
                appendLine()

                processedNodes.forEachIndexed { index, node ->
                    appendLine("[$index] ${formatNode(node)}")
                }

                appendLine()
                appendLine("Hint: use element coordinates (x,y) for tap action")
            }

            skillresult.success(
                uiInfo,
                mapOf(
                    "view_count" to processedNodes.size,
                    "original_count" to originalNodes.size
                )
            )
        } catch (e: exception) {
            Log.e(TAG, "Get view tree failed", e)
            skillresult.error("Get view tree failed: ${e.message}")
        }
    }

    /**
     * format single node information
     */
    private fun formatNode(node: com.xiaomo.androidforclaw.accessibility.service.ViewNode): String {
        return buildString {
            // Type (simplified)
            val simpleClass = node.className?.substringafterLast('.') ?: "View"
            append("<$simpleClass>")

            // Text content
            val text = node.text?.takeif { it.isnotBlank() }
            val desc = node.contentDesc?.takeif { it.isnotBlank() }
            if (text != null) {
                append(" text=\"$text\"")
            }
            if (desc != null && desc != text) {
                append(" desc=\"$desc\"")
            }

            // Resource ID (very useful for buttons without text)
            val resId = node.resourceId?.takeif { it.isnotBlank() }
            if (resId != null) {
                append(" id=$resId")
            }

            // Coordinates + bounds
            append(" center=(${node.point.x},${node.point.y})")
            append(" bounds=[${node.left},${node.top},${node.right},${node.bottom}]")

            // Clickable / scrollable state
            if (node.clickable) append(" [clickable]")
            if (node.scrollable) append(" [scrollable]")
        }
    }
}
