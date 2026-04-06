package com.xiaomo.androidforclaw.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * Transparent Activity, 用于在Back台 Skill call时弹出相机PermissionRequest. 
 *
 * 流程:
 * 1. EyeSkill DetectedNone CAMERA Permission
 * 2. Start CameraPermissionActivity(Transparent、NoneUI)
 * 3. 弹出系统Permission弹窗
 * 4. ifUser之Frontdeny过且选了"不再询问", 弹 Toast 引导去Settings页
 * 5. result通过 CompletableDeferred 回传给 EyeSkill
 */
class CameraPermissionActivity : Activity() {

    companion object {
        private const val TAG = "CameraPermission"
        private const val REQUEST_CODE_CAMERA = 1001
        private const val REQUEST_CODE_SETTINGS = 1002

        // 用于WaitPermissionresult的 deferred
        @Volatile
        var pendingresult: CompletableDeferred<Boolean>? = null

        /**
         * 从Back台Request相机Permission
         * @return true=已Authorize, false=Userdeny
         */
        suspend fun requestPermission(context: Context): Boolean {
            // 已经HasPermission
            if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }

            val deferred = CompletableDeferred<Boolean>()
            pendingresult = deferred

            try {
                val intent = Intent(context, CameraPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return deferred.await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch permission activity", e)
                pendingresult = null
                return false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CheckYesNo已HasPermission(possibly在 Activity StartFront就授了)
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Already have CAMERA permission")
            completeAndFinish(true)
            return
        }

        // CheckYesNoCan弹系统Permission弹窗
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            // User之Frontdeny过但没选"不再询问", Can再次弹窗
            Log.d(TAG, "Requesting CAMERA permission (rationale shown)")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA)
        } else {
            // 两种情况: 
            // 1. 首次Request → 弹系统弹窗
            // 2. User选了"不再询问" → 系统不弹窗, 需引导去Settings
            // 先Try弹窗, ifCallback里Yes DENIED 再跳Settings
            Log.d(TAG, "Requesting CAMERA permission (first time or denied permanently)")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA)
        }
    }

    override fun onRequestPermissionsresult(
        requestCode: Int,
        permissions: Array<out String>,
        grantresults: IntArray
    ) {
        super.onRequestPermissionsresult(requestCode, permissions, grantresults)
        if (requestCode != REQUEST_CODE_CAMERA) return

        if (grantresults.isNotEmpty() &&
            grantresults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "CAMERA permission granted via dialog")
            completeAndFinish(true)
        } else {
            // 被deny了
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.CAMERA
                )
            ) {
                // User选了"不再询问", 引导去Settings页
                Log.d(TAG, "CAMERA permission permanently denied, opening settings")
                Toast.makeText(
                    this,
                    "Please manually enable camera permission in settings",
                    Toast.LENGTH_LONG
                ).show()
                openAppSettings()
            } else {
                // User只Yes点了"deny"
                Log.d(TAG, "CAMERA permission denied by user")
                completeAndFinish(false)
            }
        }
    }

    override fun onActivityresult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityresult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SETTINGS) {
            // 从Settings页Return, CheckPermissionYesNo已grant
            val granted = checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Returned from settings, granted=$granted")
            completeAndFinish(granted)
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivityForresult(intent, REQUEST_CODE_SETTINGS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
            completeAndFinish(false)
        }
    }

    private fun completeAndFinish(granted: Boolean) {
        pendingresult?.complete(granted)
        pendingresult = null
        finish()
    }
}
