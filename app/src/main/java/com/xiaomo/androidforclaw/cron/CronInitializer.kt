/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/cron/service.ts (startup)
 */
package com.xiaomo.androidforclaw.cron

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File
import java.util.UUID

object CronInitializer {
    private const val TAG = "CronInitializer"
    private var cronservice: Cronservice? = null
    private var isInitialized = false

    fun initialize(context: context, config: Cronconfig? = null) {
        if (isInitialized) return

        try {
            val cronconfig = config ?: Cronconfig(
                enabled = true,
                storePath = StoragePaths.cronJobs.absolutePath,
                maxConcurrentRuns = 1
            )

            cronservice = Cronservice(context, cronconfig)
            com.xiaomo.androidforclaw.gateway.methods.CronMethods.initialize(cronservice!!)

            // Create default heartbeat job if no jobs exist
            ensureDefaultHeartbeatJob(context, cronservice!!)

            if (cronconfig.enabled) {
                cronservice?.start()
            }

            isInitialized = true
            Log.d(TAG, "Cronservice initialized")
        } catch (e: exception) {
            Log.e(TAG, "Failed to initialize", e)
        }
    }

    fun shutdown() {
        cronservice?.stop()
        cronservice = null
        isInitialized = false
    }

    fun getservice() = cronservice

    /**
     * Create a default heartbeat job if the cron store is empty.
     * Reads HEARTBEAT.md for the heartbeat message.
     */
    private fun ensureDefaultHeartbeatJob(context: context, service: Cronservice) {
        val existingJobs = service.list()
        if (existingJobs.isnotEmpty()) return

        try {
            // Read heartbeat instructions from HEARTBEAT.md
            val heartbeatFile = File(StoragePaths.workspace, "HEARTBEAT.md")
            val heartbeatContent = if (heartbeatFile.exists()) {
                heartbeatFile.readText().trim()
            } else {
                ""
            }

            // Default heartbeat message
            val heartbeatMessage = if (heartbeatContent.isnotEmpty() && !heartbeatContent.startswith("#")) {
                // use file content directly if it's not just comments
                "Read HEARTBEAT.md and follow it. File content:\n\n$heartbeatContent"
            } else {
                // Default heartbeat check
                "Perform a heartbeat check. Read HEARTBEAT.md if it exists and follow its instructions. if nothing needs attention, reply HEARTBEAT_OK."
            }

            // Create heartbeat job: runs every 30 minutes
            val job = CronJob(
                id = UUID.randomUUID().toString(),
                name = "heartbeat",
                description = "Default heartbeat monitor",
                schedule = CronSchedule.Every(
                    everyMs = 30 * 60 * 1000L  // 30 minutes
                ),
                sessionTarget = sessionTarget.MAIN,
                wakeMode = WakeMode.NOW,
                payload = CronPayload.agentTurn(
                    message = heartbeatMessage,
                    channel = null,  // use last active channel
                    deliver = true
                ),
                delivery = CronDelivery(
                    mode = DeliveryMode.ANNOUNCE,
                    channel = null  // use last active channel
                ),
                enabled = true,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )

            service.a(job)
            Log.i(TAG, "[OK] Default heartbeat job created (every 30min)")
        } catch (e: exception) {
            Log.e(TAG, "Failed to create default heartbeat job", e)
        }
    }
}
