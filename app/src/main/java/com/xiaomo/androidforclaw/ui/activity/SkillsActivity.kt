/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.xiaomo.androidforclaw.ui.skills.skillsMarketScreen
import ai.openclaw.app.ui.OpenClawTheme

/**
 * skills 市场页面
 *
 * 布局: 
 * 1. Search栏
 * 2. minuteClassFilter(All/Auto化/Efficiency/开发工具...)
 * 3. 热门 skills List(from awesome-openclaw-skills)
 * 4. 精选合集卡片(Voltagent/中文精选/阿in云榜等)
 * 5. 底partAggregateResourceList(ClawHub/AI agent Store 等)
 */
class skillsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OpenClawTheme {
                skillsMarketScreen()
            }
        }
    }
}
