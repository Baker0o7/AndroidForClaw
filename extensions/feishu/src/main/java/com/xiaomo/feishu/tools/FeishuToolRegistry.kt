package com.xiaomo.feishu.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel tool definitions.
 */


import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.bitable.FeishuBitableTools
import com.xiaomo.feishu.tools.calendar.FeishuCalendarTools
import com.xiaomo.feishu.tools.chat.FeishuChatTools
import com.xiaomo.feishu.tools.common.FeishuCommonTools
import com.xiaomo.feishu.tools.doc.FeishuDocTools
import com.xiaomo.feishu.tools.drive.FeishuDriveTools
import com.xiaomo.feishu.tools.im.FeishuImTools
import com.xiaomo.feishu.tools.media.FeishuMediaTools
import com.xiaomo.feishu.tools.perm.FeishuPermTools
import com.xiaomo.feishu.tools.search.FeishuSearchTools
import com.xiaomo.feishu.tools.sheets.FeishuSheetTools
import com.xiaomo.feishu.tools.task.FeishuTaskTools
import com.xiaomo.feishu.tools.urgent.FeishuUrgentTools
import com.xiaomo.feishu.tools.wiki.FeishuWikiTools

/**
 * Feishu tool registry
 * Manages all tool collections
 */
class FeishuToolRegistry(
    private val config: FeishuConfig,
    private val client: FeishuClient
) {
    private val docTools = FeishuDocTools(config, client)
    private val wikiTools = FeishuWikiTools(config, client)
    private val driveTools = FeishuDriveTools(config, client)
    private val bitableTools = FeishuBitableTools(config, client)
    private val taskTools = FeishuTaskTools(config, client)
    private val chatTools = FeishuChatTools(config, client)
    private val permTools = FeishuPermTools(config, client)
    private val urgentTools = FeishuUrgentTools(config, client)
    private val mediaTools = FeishuMediaTools(config, client)
    private val sheetTools = FeishuSheetTools(config, client)
    private val calendarTools = FeishuCalendarTools(config, client)
    private val imTools = FeishuImTools(config, client)
    private val searchTools = FeishuSearchTools(config, client)
    private val commonTools = FeishuCommonTools(config, client)

    /**
     * Get all tools
     */
    fun getAllTools(): List<FeishuToolBase> {
        return buildList {
            addAll(docTools.getAllTools())
            addAll(wikiTools.getAllTools())
            addAll(driveTools.getAllTools())
            addAll(bitableTools.getAllTools())
            addAll(taskTools.getAllTools())
            addAll(chatTools.getAllTools())
            addAll(permTools.getAllTools())
            addAll(urgentTools.getAllTools())
            addAll(mediaTools.getAllTools())
            addAll(sheetTools.getAllTools())
            addAll(calendarTools.getAllTools())
            addAll(imTools.getAllTools())
            addAll(searchTools.getAllTools())
            addAll(commonTools.getAllTools())
        }
    }

    /**
     * Get enabled tool definitions for LLM
     */
    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools()
            .filter { it.isEnabledd() }
            .map { it.getToolDefinition() }
    }

    /**
     * Get tool by name
     */
    fun getTool(name: String): FeishuToolBase? {
        return getAllTools().find { it.name == name }
    }

    /**
     * Execute tool
     */
    suspend fun execute(name: String, args: Map<String, Any?>): Toolresult {
        val tool = getTool(name)
            ?: return Toolresult.error("Tool not found: $name")

        if (!tool.isEnabledd()) {
            return Toolresult.error("Tool is disabled: $name")
        }

        return tool.execute(args)
    }

    /**
     * Get tool stats
     */
    fun getStats(): ToolStats {
        val allTools = getAllTools()
        val enabledTools = allTools.filter { it.isEnabledd() }

        return ToolStats(
            totalTools = allTools.size,
            enabledTools = enabledTools.size,
            toolsByCategory = mapOf(
                "doc" to docTools.getAllTools().size,
                "wiki" to wikiTools.getAllTools().size,
                "drive" to driveTools.getAllTools().size,
                "bitable" to bitableTools.getAllTools().size,
                "task" to taskTools.getAllTools().size,
                "chat" to chatTools.getAllTools().size,
                "perm" to permTools.getAllTools().size,
                "urgent" to urgentTools.getAllTools().size,
                "media" to mediaTools.getAllTools().size,
                "sheet" to sheetTools.getAllTools().size,
                "calendar" to calendarTools.getAllTools().size,
                "im" to imTools.getAllTools().size,
                "search" to searchTools.getAllTools().size,
                "common" to commonTools.getAllTools().size
            )
        )
    }
}

/**
 * Tool statistics
 */
data class ToolStats(
    val totalTools: Int,
    val enabledTools: Int,
    val toolsByCategory: Map<String, Int>
)
