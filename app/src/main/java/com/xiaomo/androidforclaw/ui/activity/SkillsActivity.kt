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
 * Skills market page
 *
 * Layout:
 * 1. Search bar
 * 2. Category filter (All/Automation/Efficiency/Dev Tools...)
 * 3. Popular skills list (from awesome-openclaw-skills)
 * 4. Featured collections (Voltagent/Chinese Picks/AI Cloud Rankings, etc.)
 * 5. Bottom aggregated resource list (ClawHub/AI Agent Store, etc.)
 */
class SkillsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OpenClawTheme {
                skillsMarketScreen()
            }
        }
    }
}
