package com.xiaomo.feishu.tools.media

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel tool definitions.
 */


import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.FeishuToolBase

/**
 * Feishu media tools
 */
class FeishuMediaTools(
    private val config: FeishuConfig,
    private val client: FeishuClient
) {
    private val imageUploadTool = FeishuImageUploadTool(config, client)

    /**
     * Get all media tools
     */
    fun getAllTools(): List<FeishuToolBase> {
        return listOf(
            imageUploadTool
        )
    }
}
