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
 * Transparent Activity, used in background skill to popup camera permission request.
 *
 * Flow:
 * 1. Eye skill detected no CAMERA permission
 * 2. Start CameraPermissionActivity (transparent, no UI)
 * 3. Show system permission popup
 * 4. If user denied and selected "don't ask again", show Toast and steer to settings page
 * 5. Result through CompletableDeferred return to Eye skill
 */
class CameraPermissionActivity : Activity() {

    companion object {
        private const val TAG = "CameraPermission"
        private const val REQUEST_CODE_CAMERA = 1001
        private const val REQUEST_CODE_SETTINGS = 1002

        // For waiting permission result deferred
        @Volatile
        var pendingresult: CompletableDeferred<Boolean>? = null

        /**
         * Request camera permission from background
         * @return true=already authorized, false=user denied
         */
        suspend fun requestPermission(context: context): Boolean {
            // Already has permission
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

        // Check whether already has permission (possibly granted at Activity start)
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            Packagemanager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Already have CAMERA permission")
            completeandFinish(true)
            return
        }

        // Check whether can show system permission popup
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            // User denied but didn't select "don't ask again", can show again
            Log.d(TAG, "Requesting CAMERA permission (rationale shown)")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA)
        } else {
            // Two cases:
            // 1. First time request -> show system popup
            // 2. User selected "don't ask again" -> system not popup, need steer to settings
            // First try popup, if callback is DENIED then jump to Settings
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
            // Denied
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.CAMERA
                )
            ) {
                // User selected "don't ask again", steer to settings page
                Log.d(TAG, "CAMERA permission permanently denied, opening settings")
                Toast.makeText(
                    this,
                    "please manually enable camera permission in settings",
                    Toast.LENGTH_LONG
                ).show()
                openAppSettings()
            } else {
                // User just clicked "deny"
                Log.d(TAG, "CAMERA permission denied by user")
                completeandFinish(false)
            }
        }
    }

    override fun onActivityresult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityresult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SETTINGS) {
            // Return from settings page, check permission whether already granted
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
