/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Permission页Proxy: 直接跳转到MergeInto主 app 的 observer Permission页, 避免User看到两层Permission页. 
 */
class PermissionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent().apply {
                component = ComponentName(
                    "com.xiaomo.androidforclaw",
                    "com.xiaomo.androidforclaw.accessibility.PermissionActivity"
                )
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        } finally {
            finish()
        }
    }
}
