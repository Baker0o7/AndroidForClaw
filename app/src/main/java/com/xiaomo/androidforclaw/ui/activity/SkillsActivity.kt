/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.xiaomo.androidforclaw.ui.skills.SkillsMarketScreen
import ai.openclaw.app.ui.OpenClawTheme

/**
 * Skills 市场页面
 *
 * 布局: 
 * 1. Search栏
 * 2. 分ClassFilter(All/Auto化/Efficiency/开发工具...)
 * 3. 热门 Skills List(from awesome-openclaw-skills)
 * 4. 精选合集卡片(VoltAgent/中文精选/阿里云榜等)
 * 5. 底部AggregateResourceList(ClawHub/AI Agent Store 等)
 */
class SkillsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OpenClawTheme {
                SkillsMarketScreen()
            }
        }
    }
}
