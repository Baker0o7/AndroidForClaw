/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.updater

import android.content.context
import android.content.Intent
import com.xiaomo.androidforclaw.logging.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Workmanager Worker that relaunches the app after process death.
 */
class RestartWorker(context: context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Log.d("RestartWorker", "Relaunching app...")
        val intent = applicationcontext.packagemanager.getLaunchIntentforPackage(applicationcontext.packageName)
        if (intent != null) {
            intent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            applicationcontext.startActivity(intent)
        }
        return Result.success()
    }
}
