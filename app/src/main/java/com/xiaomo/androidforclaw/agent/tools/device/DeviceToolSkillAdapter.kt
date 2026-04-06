/**
 * OpenClaw Source Reference:
 * - src/agents/tools/browser-tool.ts (架构对齐: DeviceTool 对应 browser-tool)
 *
 * Adapter: wraps DeviceTool (Tool interface) as a Skill for AndroidToolRegistry.
 */
package com.xiaomo.androidforclaw.agent.tools.device

import android.content.Context
import com.xiaomo.androidforclaw.agent.tools.Skill
import com.xiaomo.androidforclaw.agent.tools.Skillresult
import com.xiaomo.androidforclaw.providers.ToolDefinition

class DeviceToolSkillAdapter(context: Context) : Skill {
    private val deviceTool = DeviceTool(context)

    override val name: String = deviceTool.name
    override val description: String = deviceTool.description

    override fun getToolDefinition(): ToolDefinition = deviceTool.getToolDefinition()

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        val result = deviceTool.execute(args)
        return if (result.success) {
            Skillresult.success(result.content)
        } else {
            Skillresult.error(result.content)
        }
    }
}
