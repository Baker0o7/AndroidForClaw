package com.xiaomo.androidforclaw.agent.tools

import android.content.Context
import android.util.Log
import com.xiaomo.androidforclaw.DeviceController
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.ToolDefinition
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy

/**
 * Get View Tree Skill
 * Get current screen UI tree structure (processed clean version)
 *
 * Prefer using this tool to understand interface - it's lighter and faster than screenshot.
 * Only use screenshot when visual information is needed or operation fails.
 */
class GetViewTreeSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "GetViewTreeSkill"
    }

    override val name = "get_view_tree"
    override val description = """
        获取当前屏幕的 UI 树信息（已优化处理，去除重复和无用节点）。

        **优先使用此工具**来理解界面结构和查找可交互元素。

        特点：
        - 轻量、快速（无需截图）
        - 返回清理后的 UI 元素列表
        - 包含位置、文本、描述、是否可点击等信息

        只有在以下情况才使用 screenshot：
        - 需要查看颜色、图标等视觉信息
        - UI 树信息不足以完成任务
        - 操作失败需要视觉确认
    """.trimIndent()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        Log.d(TAG, "Getting view tree (processed)...")
        return try {
            if (!AccessibilityProxy.isServiceReady()) {
                return SkillResult.error("Accessibility service not ready")
            }

            // Get original UI tree and processed UI tree
            val iconResult = DeviceController.detectIcons(context)
            if (iconResult == null) {
                return SkillResult.error("无法获取 UI 树。请检查：\n1. 无障碍服务是否已启用\n2. 当前应用是否允许访问")
            }
            val (originalNodes, processedNodes) = iconResult

            Log.d(TAG, "Original nodes: ${originalNodes.size}, Processed nodes: ${processedNodes.size}")

            // Use processed nodes (deduplicated, empty removed)
            val uiInfo = buildString {
                appendLine("【屏幕 UI 元素列表】（共 ${processedNodes.size} 个可用元素）")
                appendLine()

                processedNodes.forEachIndexed { index, node ->
                    appendLine("[$index] ${formatNode(node)}")
                }

                appendLine()
                appendLine("提示：使用元素的坐标 (x,y) 进行 tap 操作")
            }

            SkillResult.success(
                uiInfo,
                mapOf(
                    "view_count" to processedNodes.size,
                    "original_count" to originalNodes.size
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Get view tree failed", e)
            SkillResult.error("Get view tree failed: ${e.message}")
        }
    }

    /**
     * Format single node information
     */
    private fun formatNode(node: com.xiaomo.androidforclaw.ViewNode): String {
        return buildString {
            // Text content
            val text = node.text?.takeIf { it.isNotBlank() } ?: node.contentDesc?.takeIf { it.isNotBlank() } ?: ""
            if (text.isNotEmpty()) {
                append("\"$text\"")
            } else {
                append("[无文本]")
            }

            // Coordinates
            append(" (${node.point.x}, ${node.point.y})")

            // Is clickable
            if (node.clickable) {
                append(" [可点击]")
            }

            // Type (simplified)
            val simpleClass = node.className?.substringAfterLast('.') ?: ""
            if (simpleClass.isNotEmpty() && !simpleClass.contains("Layout")) {
                append(" <$simpleClass>")
            }
        }
    }
}
