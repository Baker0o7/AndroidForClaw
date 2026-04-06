/**
 * OpenClaw Source Reference:
 * - src/agents/tools/browser-tool.ts (Architecture reference: Devicetool should be browser-tool)
 *
 * Adapter: wraps Devicetool (tool interface) as a skill for androidtoolRegistry.
 */
package com.xiaomo.androidforclaw.agent.tools.device

import android.content.context
import com.xiaomo.androidforclaw.agent.tools.skill
import com.xiaomo.androidforclaw.agent.tools.skillresult
import com.xiaomo.androidforclaw.providers.toolDefinition

class DevicetoolskillAdapter(context: context) : skill {
    private val devicetool = Devicetool(context)

    override val name: String = devicetool.name
    override val description: String = devicetool.description

    override fun gettoolDefinition(): toolDefinition = devicetool.gettoolDefinition()

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val result = devicetool.execute(args)
        return if (result.success) {
            skillresult.success(result.content)
        } else {
            skillresult.error(result.content)
        }
    }
}
