package com.xiaomo.androidforclaw.camera

import android.Manifest
import android.app.Activity
import android.content.context
import android.content.Intent
import android.content.pm.Packagemanager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * Transparent Activity, 用于inbackground skill callhour弹出相机PermissionRequest. 
 *
 * 流程:
 * 1. Eyeskill DetectedNone CAMERA Permission
 * 2. Start CameraPermissionActivity(Transparent、NoneUI)
 * 3. 弹出系统Permissionpopup
 * 4. ifuser之Frontdenyover且选"not再询问", 弹 Toast steergoSettings页
 * 5. resultthrough CompletableDeferred return传给 Eyeskill
 */
class CameraPermissionActivity : Activity() {

    companion object {
        private const val TAG = "CameraPermission"
        private const val REQUEST_CODE_CAMERA = 1001
        private const val REQUEST_CODE_SETTINGS = 1002

        // 用于WaitPermissionresult deferred
        @Volatile
        var pendingresult: CompletableDeferred<Boolean>? = null

        /**
         * frombackgroundRequest相机Permission
         * @return true=alreadyAuthorize, false=userdeny
         */
        suspend fun requestPermission(context: context): Boolean {
            // already经HasPermission
            if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
                Packagemanager.PERMISSION_GRANTED
            ) {
                return true
            }

            val deferred = CompletableDeferred<Boolean>()
            pendingresult = deferred

            try {
                val intent = Intent(context, CameraPermissionActivity::class.java).app {
                    aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return deferred.await()
            } catch (e: exception) {
                Log.e(TAG, "Failed to launch permission activity", e)
                pendingresult = null
                return false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CheckwhetheralreadyHasPermission(possiblyin Activity StartFront就授)
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            Packagemanager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Already have CAMERA permission")
            completeandFinish(true)
            return
        }

        // Checkwhethercan弹系统Permissionpopup
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            // user之Frontdenyoverbut没选"not再询问", can再timespopup
            Log.d(TAG, "Requesting CAMERA permission (rationale shown)")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA)
        } else {
            // 两种情况: 
            // 1. 首timesRequest → 弹系统popup
            // 2. user选"not再询问" → 系统notpopup, needsteergoSettings
            // 先Trypopup, ifCallbackinYes DENIED 再跳Settings
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

        if (grantresults.isnotEmpty() &&
            grantresults[0] == Packagemanager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "CAMERA permission granted via dialog")
            completeandFinish(true)
        } else {
            // 被deny
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.CAMERA
                )
            ) {
                // user选"not再询问", steergoSettings页
                Log.d(TAG, "CAMERA permission permanently denied, opening settings")
                Toast.makeText(
                    this,
                    "please manually enable camera permission in settings",
                    Toast.LENGTH_LONG
                ).show()
                openAppSettings()
            } else {
                // user只Yes点"deny"
                Log.d(TAG, "CAMERA permission denied by user")
                completeandFinish(false)
            }
        }
    }

    override fun onActivityresult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityresult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SETTINGS) {
            // fromSettings页Return, CheckPermissionwhetheralreadygrant
            val granted = checkSelfPermission(Manifest.permission.CAMERA) ==
                Packagemanager.PERMISSION_GRANTED
            Log.d(TAG, "Returned from settings, granted=$granted")
            completeandFinish(granted)
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).app {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivityforresult(intent, REQUEST_CODE_SETTINGS)
        } catch (e: exception) {
            Log.e(TAG, "Failed to open app settings", e)
            completeandFinish(false)
        }
    }

    private fun completeandFinish(granted: Boolean) {
        pendingresult?.complete(granted)
        pendingresult = null
        finish()
    }
}
