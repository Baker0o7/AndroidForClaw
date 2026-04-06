/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Permission页Proxy: 直接跳转toMergeintomain app  observer Permission页, 避免userseeto两layerPermission页. 
 */
class PermissionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent().app {
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
