package com.xiaomo.androidforclaw.agent.tools

/**
 * android API skill — 暴露 android 系统 API 给 agent
 *
 * 让 agent through API 直接Action系统Feature, 而notYes靠 UI Auto化硬刚. 
 * Aligned with OpenClaw  tool schema: 单one工具 + action ParametersRoute. 
 *
 * SupportAction: 
 * - 闹钟/定hour器: set_alarm, set_timer
 * - 剪贴板: get_clipboard, set_clipboard
 * - 电池/Storage: get_battery, get_storage
 * - 手电筒: flashlight
 * - 音量: get_volume, set_volume
 * - highlight度: set_brightness
 * - Start App/Activity: start_app, start_activity
 * - 发Broadcast: send_broadcast
 * - ScreenTimeout: set_screen_timeout
 * - Settings页跳转: open_settings
 */



import android.content.ActivitynotFoundexception
import android.content.ClipData
import android.content.Clipboardmanager
import android.content.context
import android.content.Intent
import android.media.Audiomanager

import android.os.Batterymanager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.hardware.camera2.Cameramanager
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

class AndroidApiSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "AndroidApiSkill"
    }

    override val name = "android_api"
    override val description = """Android system API tool. Directly call system API to control device features, no need for UI automation. 
Supported Actions: 
- set_alarm: Set alarm (Parameters: hour, minute, message)
- set_timer: Set timer (Parameters: seconds, message)
- get_clipboard: Read clipboard
- set_clipboard: Write clipboard (Parameters: text)
- get_battery: Get battery status
- get_storage: Get storage space
- flashlight: Toggle flashlight (Parameters: on=true/false)
- get_volume: Get volume info
- set_volume: Set volume (Parameters: stream, level 0-100)
- set_brightness: Set screen brightness (Parameters: level 0-255, auto=true/false)
- start_app: Start app (Parameters: package)
- start_activity: Start activity (Parameters: action, data?, package?)
- send_broadcast: Send broadcast (Parameters: action, package?)
- set_screen_timeout: Set screen timeout (Parameters: seconds)
- open_settings: Open system settings page (Parameters: page)"""

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "action" to Propertyschema(
                            type = "string",
                            description = "Action type",
                            enum = listOf(
                                "set_alarm", "set_timer",
                                "get_clipboard", "set_clipboard",
                                "get_battery", "get_storage",
                                "flashlight",
                                "get_volume", "set_volume",
                                "set_brightness",
                                "start_app", "start_activity",
                                "send_broadcast",
                                "set_screen_timeout",
                                "open_settings"
                            )
                        ),
                        "hour" to Propertyschema(type = "number", description = "smallhour (0-23) for set_alarm"),
                        "minute" to Propertyschema(type = "number", description = "minute钟 (0-59) for set_alarm"),
                        "seconds" to Propertyschema(type = "number", description = "seconds数 for set_timer / set_screen_timeout"),
                        "message" to Propertyschema(type = "string", description = "闹钟/定hour器tag"),
                        "text" to Propertyschema(type = "string", description = "剪贴板Text for set_clipboard"),
                        "on" to Propertyschema(type = "boolean", description = "开关 for flashlight"),
                        "stream" to Propertyschema(type = "string", description = "音量Type: music/call/ring/notification/alarm/system", enum = listOf("music", "call", "ring", "notification", "alarm", "system")),
                        "level" to Propertyschema(type = "number", description = "音量level别 for set_volume (0-100) orhighlight度level别 for set_brightness (0-255)"),
                        "auto" to Propertyschema(type = "boolean", description = "highlight度Auto调节 for set_brightness"),
                        "package" to Propertyschema(type = "string", description = "Package name for start_app / start_activity / send_broadcast"),
                        "action" to Propertyschema(type = "string", description = "Intent action for start_activity / send_broadcast"),
                        "data" to Propertyschema(type = "string", description = "Intent data URI for start_activity"),
                        "page" to Propertyschema(type = "string", description = "Settings页面: wifi/bluetooth/battery/display/sound/storage/app/all", enum = listOf("wifi", "bluetooth", "battery", "display", "sound", "storage", "app", "all"))
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val action = args["action"] as? String ?: return skillresult.error("Missing 'action' parameter")

        return try {
            when (action) {
                "set_alarm" -> setAlarm(args)
                "set_timer" -> setTimer(args)
                "get_clipboard" -> getClipboard()
                "set_clipboard" -> setClipboard(args)
                "get_battery" -> getBattery()
                "get_storage" -> getStorage()
                "flashlight" -> toggleFlashlight(args)
                "get_volume" -> getVolume()
                "set_volume" -> setVolume(args)
                "set_brightness" -> setBrightness(args)
                "start_app" -> startApp(args)
                "start_activity" -> startActivity(args)
                "send_broadcast" -> sendBroadcast(args)
                "set_screen_timeout" -> setScreenTimeout(args)
                "open_settings" -> openSettings(args)
                else -> skillresult.error("Unknown action: $action")
            }
        } catch (e: exception) {
            Log.e(TAG, "android_api.$action failed", e)
            skillresult.error("ActionFailed: ${e.message}")
        }
    }

    // ========== Alarm / Timer ==========

    private fun setAlarm(args: Map<String, Any?>): SkillResult {
        val hour = (args["hour"] as? Number)?.toInt() ?: return SkillResult.error("Missing 'hour'")
        val minute = (args["minute"] as? Number)?.toInt() ?: return SkillResult.error("Missing 'minute'")
        val message = args["message"] as? String ?: "AndroidClaw alarm"

        val intent = Intent("android.intent.action.SET_ALARM").apply {
            putExtra("android.intent.extra.alarm.HOUR", hour)
            putExtra("android.intent.extra.alarm.MINUTES", minute)
            putExtra("android.intent.extra.alarm.MESSAGE", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            SkillResult.success("Alarm set: ${hour}h ${minute}m - $message")
        } catch (e: ActivityNotFoundException) {
            SkillResult.error("Clock app not found")
        }
    }

    private fun setTimer(args: Map<String, Any?>): SkillResult {
        val seconds = (args["seconds"] as? Number)?.toInt() ?: return SkillResult.error("Missing 'seconds'")
        val message = args["message"] as? String ?: "AndroidClaw timer"

        val intent = Intent("android.intent.action.SET_TIMER").apply {
            putExtra("android.intent.extra.alarm.LENGTH", seconds)
            putExtra("android.intent.extra.alarm.MESSAGE", message)
            putExtra("android.intent.extra.alarm.SKIP_UI", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            SkillResult.success("Timer set: ${seconds}s - $message")
        } catch (e: ActivityNotFoundException) {
            SkillResult.error("Clock app not found")
        }
    }

    // ========== Clipboard ==========

    private fun getClipboard(): SkillResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return SkillResult.success("Clipboard is empty")
        }
        val text = clip.getItemAt(0).text?.toString() ?: ""
        return SkillResult.success("Clipboard content: $text")
    }

    private fun setClipboard(args: Map<String, Any?>): SkillResult {
        val text = args["text"] as? String ?: return SkillResult.error("Missing 'text'")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AndroidClaw", text))
        return SkillResult.success("Copied to clipboard: ${text.take(50)}${if (text.length > 50) "..." else ""}")
    }

    // ========== Battery ==========

    private fun getBattery(): SkillResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)

        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }

        val chargingInfo = if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
            val currentNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentAvg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            " (Current: ${currentNow / 1000}mA, Avg: ${currentAvg / 1000}mA)"
        } else ""

        return SkillResult.success("Battery: ${level}% | Status: $statusStr$chargingInfo")
    }

    // ========== Storage ==========

    private fun getStorage(): SkillResult {
        val internal = StatFs(Environment.getDataDirectory().path)
        val internalTotal = internal.totalBytes
        val internalFree = internal.availableBytes

        val result = StringBuilder()
        result.appendLine("Internal Storage:")
        result.appendLine("  Total: ${formatBytes(internalTotal)}")
        result.appendLine("  Available: ${formatBytes(internalFree)}")
        result.appendLine("  Used: ${formatBytes(internalTotal - internalFree)} (${(internalTotal - internalFree) * 100 / internalTotal}%)")

        // External storage if available
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val external = StatFs(Environment.getExternalStorageDirectory().path)
            result.appendLine("External Storage:")
            result.appendLine("  Total: ${formatBytes(external.totalBytes)}")
            result.appendLine("  Available: ${formatBytes(external.availableBytes)}")
        }

        return SkillResult.success(result.toString().trim())
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "${bytes / 1_073_741_824} GB"
        bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    // ========== Flashlight ==========

    private fun toggleFlashlight(args: Map<String, Any?>): SkillResult {
        val on = args["on"] as? Boolean ?: return SkillResult.error("Missing 'on' parameter (true/false)")
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cm.cameraIdList.firstOrNull() ?: return SkillResult.error("No available camera")
            cm.setTorchMode(cameraId, on)
            return SkillResult.success(if (on) "Flashlight turned on" else "Flashlight turned off")
        } catch (e: Exception) {
            return SkillResult.error("Flashlight operation failed: ${e.message}")
        }
    }

    // ========== Volume ==========

    private fun getVolume(): SkillResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streams = listOf(
            "music" to AudioManager.STREAM_MUSIC,
            "call" to AudioManager.STREAM_VOICE_CALL,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "system" to AudioManager.STREAM_SYSTEM
        )
        val result = StringBuilder("Volume Info:\n")
        for ((name, stream) in streams) {
            val current = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            val percent = if (max > 0) current * 100 / max else 0
            val mute = if (am.isStreamMute(stream)) " [Muted]" else ""
            result.appendLine("  $name: $current/$max (${percent}%)$mute")
        }
        return SkillResult.success(result.toString().trim())
    }

    private fun setVolume(args: Map<String, Any?>): SkillResult {
        val streamName = args["stream"] as? String ?: "music"
        val level = (args["level"] as? Number)?.toInt() ?: return SkillResult.error("Missing 'level'")

        val stream = when (streamName) {
            "music" -> AudioManager.STREAM_MUSIC
            "call" -> AudioManager.STREAM_VOICE_CALL
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "system" -> AudioManager.STREAM_SYSTEM
            else -> return SkillResult.error("Unknown stream: $streamName")
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream)
        val vol = (level * max / 100).coerceIn(0, max)
        am.setStreamVolume(stream, vol, 0)

        return SkillResult.success("Set $streamName volume: $vol/$max (${level}%)")
    }

    // ========== Brightness ==========

    private fun setBrightness(args: Map<String, Any?>): SkillResult {
        val auto = args["auto"] as? Boolean
        if (auto == true) {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return SkillResult.success("Switched to auto brightness")
        }

        val level = (args["level"] as? Number)?.toInt()
            ?: return SkillResult.error("Missing 'level' (0-255) or 'auto' (true)")

        // Need to disable auto brightness first
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 255))

        return SkillResult.success("Set brightness: $level/255")
    }

    // ========== Start App ==========

    private fun startApp(args: Map<String, Any?>): SkillResult {
        val packageName = args["package"] as? String ?: return SkillResult.error("Missing 'package'")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return SkillResult.error("App not found: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return SkillResult.success("Started: $packageName")
    }

    private fun startActivity(args: Map<String, Any?>): SkillResult {
        val action = args["action"] as? String ?: return SkillResult.error("Missing 'action' (Intent action)")
        val data = args["data"] as? String
        val pkg = args["package"] as? String

        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (data != null) setData(android.net.Uri.parse(data))
            if (pkg != null) setPackage(pkg)
        }
        return try {
            context.startActivity(intent)
            SkillResult.success("Started activity: $action")
        } catch (e: ActivityNotFoundException) {
            SkillResult.error("Target activity not found: $action")
        }
    }

    // ========== Broadcast ==========

    private fun sendBroadcast(args: Map<String, Any?>): SkillResult {
        val action = args["action"] as? String ?: return SkillResult.error("Missing 'action'")
        val pkg = args["package"] as? String

        val intent = Intent(action).apply {
            if (pkg != null) setPackage(pkg)
        }
        context.sendBroadcast(intent)
        return SkillResult.success("Sent broadcast: $action")
    }

    // ========== Screen Timeout ==========

    private fun setScreenTimeout(args: Map<String, Any?>): SkillResult {
        val seconds = (args["seconds"] as? Number)?.toInt() ?: return SkillResult.error("Missing 'seconds'")

        // Use Intent to open screen timeout settings (direct write needs WRITE_SETTINGS which we may not have)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            // Fall back to opening settings
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return SkillResult.error("WRITE_SETTINGS permission required, opened authorization page")
        }

        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
        return SkillResult.success("Set screen timeout: ${seconds}s")
    }

    // ========== Settings Page ==========

    private fun openSettings(args: Map<String, Any?>): SkillResult {
        val page = args["page"] as? String ?: "all"
        val intentAction = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> "android.intent.action.POWER_USAGE_SUMMARY"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "app" -> Settings.ACTION_APPLICATION_SETTINGS
            "all" -> Settings.ACTION_SETTINGS
            else -> return SkillResult.error("Unknown settings page: $page")
        }

        val intent = Intent(intentAction).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            context.startActivity(intent)
            SkillResult.success("Opened settings page: $page")
        } catch (e: ActivityNotFoundException) {
            SkillResult.error("Settings page not found: $page")
        }
    }
}
