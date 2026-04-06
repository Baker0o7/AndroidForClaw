package com.xiaomo.androidforclaw.agent.tools

/**
 * Android API Skill — 暴露 Android 系统 API 给 Agent
 *
 * 让 Agent 通过 API 直接Action系统Feature, 而不Yes靠 UI Auto化硬刚. 
 * Aligned with OpenClaw 的 tool Schema: 单一工具 + action ParametersRoute. 
 *
 * Support的Action: 
 * - 闹钟/定时器: set_alarm, set_timer
 * - 剪贴板: get_clipboard, set_clipboard
 * - 电池/Storage: get_battery, get_storage
 * - 手电筒: flashlight
 * - 音量: get_volume, set_volume
 * - 亮度: set_brightness
 * - Start App/Activity: start_app, start_activity
 * - 发Broadcast: send_broadcast
 * - ScreenTimeout: set_screen_timeout
 * - Settings页跳转: open_settings
 */



import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager

import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.hardware.camera2.CameraManager
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition

class AndroidApiSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "AndroidApiSkill"
    }

    override val name = "android_api"
    override val description = """Android 系统 API 工具. directly call system API ActionDeviceFeature, None需 UI Auto化. 
SupportAction: 
- set_alarm: Settings闹钟 (Parameters: hour, minute, message)
- set_timer: Settings倒计时 (Parameters: seconds, message)
- get_clipboard: Read剪贴板
- set_clipboard: Write剪贴板 (Parameters: text)
- get_battery: Get电池Status
- get_storage: GetStorageSpace
- flashlight: 开关手电筒 (Parameters: on=true/false)
- get_volume: Get音量Info
- set_volume: Settings音量 (Parameters: stream, level 0-100)
- set_brightness: SettingsScreen亮度 (Parameters: level 0-255, auto=true/false)
- start_app: Start App (Parameters: package)
- start_activity: Start Activity (Parameters: action, data?, package?)
- send_broadcast: sendBroadcast (Parameters: action, package?)
- set_screen_timeout: SettingsScreenTimeout (Parameters: seconds)
- open_settings: Open系统Settings页 (Parameters: page)"""

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "action" to PropertySchema(
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
                        "hour" to PropertySchema(type = "number", description = "小时 (0-23) for set_alarm"),
                        "minute" to PropertySchema(type = "number", description = "分钟 (0-59) for set_alarm"),
                        "seconds" to PropertySchema(type = "number", description = "秒数 for set_timer / set_screen_timeout"),
                        "message" to PropertySchema(type = "string", description = "闹钟/定时器标签"),
                        "text" to PropertySchema(type = "string", description = "剪贴板Text for set_clipboard"),
                        "on" to PropertySchema(type = "boolean", description = "开关 for flashlight"),
                        "stream" to PropertySchema(type = "string", description = "音量Type: music/call/ring/notification/alarm/system", enum = listOf("music", "call", "ring", "notification", "alarm", "system")),
                        "level" to PropertySchema(type = "number", description = "音量级别 for set_volume (0-100) 或亮度级别 for set_brightness (0-255)"),
                        "auto" to PropertySchema(type = "boolean", description = "亮度Auto调节 for set_brightness"),
                        "package" to PropertySchema(type = "string", description = "Package name for start_app / start_activity / send_broadcast"),
                        "action" to PropertySchema(type = "string", description = "Intent action for start_activity / send_broadcast"),
                        "data" to PropertySchema(type = "string", description = "Intent data URI for start_activity"),
                        "page" to PropertySchema(type = "string", description = "Settings页面: wifi/bluetooth/battery/display/sound/storage/app/all", enum = listOf("wifi", "bluetooth", "battery", "display", "sound", "storage", "app", "all"))
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        val action = args["action"] as? String ?: return Skillresult.error("Missing 'action' parameter")

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
                else -> Skillresult.error("Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "android_api.$action failed", e)
            Skillresult.error("ActionFailed: ${e.message}")
        }
    }

    // ========== 闹钟/定时器 ==========

    private fun setAlarm(args: Map<String, Any?>): Skillresult {
        val hour = (args["hour"] as? Number)?.toInt() ?: return Skillresult.error("Missing 'hour'")
        val minute = (args["minute"] as? Number)?.toInt() ?: return Skillresult.error("Missing 'minute'")
        val message = args["message"] as? String ?: "AndroidClaw 闹钟"

        val intent = Intent("android.intent.action.SET_ALARM").apply {
            putExtra("android.intent.extra.alarm.HOUR", hour)
            putExtra("android.intent.extra.alarm.MINUTES", minute)
            putExtra("android.intent.extra.alarm.MESSAGE", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Skillresult.success("已Settings闹钟: ${hour}时${minute}分 - $message")
        } catch (e: ActivityNotFoundException) {
            Skillresult.error("未找到时钟apply")
        }
    }

    private fun setTimer(args: Map<String, Any?>): Skillresult {
        val seconds = (args["seconds"] as? Number)?.toInt() ?: return Skillresult.error("Missing 'seconds'")
        val message = args["message"] as? String ?: "AndroidClaw 定时器"

        val intent = Intent("android.intent.action.SET_TIMER").apply {
            putExtra("android.intent.extra.alarm.LENGTH", seconds)
            putExtra("android.intent.extra.alarm.MESSAGE", message)
            putExtra("android.intent.extra.alarm.SKIP_UI", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Skillresult.success("已Settings定时器: ${seconds}秒 - $message")
        } catch (e: ActivityNotFoundException) {
            Skillresult.error("未找到时钟apply")
        }
    }

    // ========== 剪贴板 ==========

    private fun getClipboard(): Skillresult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return Skillresult.success("剪贴板为Null")
        }
        val text = clip.getItemAt(0).text?.toString() ?: ""
        return Skillresult.success("剪贴板Inside容: $text")
    }

    private fun setClipboard(args: Map<String, Any?>): Skillresult {
        val text = args["text"] as? String ?: return Skillresult.error("Missing 'text'")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AndroidClaw", text))
        return Skillresult.success("已Copy到剪贴板: ${text.take(50)}${if (text.length > 50) "..." else ""}")
    }

    // ========== 电池 ==========

    private fun getBattery(): Skillresult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)

        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "Unknown"
        }

        val chargingInfo = if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
            val currentNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentAvg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            " (Current: ${currentNow / 1000}mA, 平均: ${currentAvg / 1000}mA)"
        } else ""

        return Skillresult.success("电池: ${level}% | Status: $statusStr$chargingInfo")
    }

    // ========== Storage ==========

    private fun getStorage(): Skillresult {
        val internal = StatFs(Environment.getDataDirectory().path)
        val internalTotal = internal.totalBytes
        val internalFree = internal.availableBytes

        val result = StringBuilder()
        result.appendLine("InternalStorage:")
        result.appendLine("  Total: ${formatBytes(internalTotal)}")
        result.appendLine("  Available: ${formatBytes(internalFree)}")
        result.appendLine("  已用: ${formatBytes(internalTotal - internalFree)} (${(internalTotal - internalFree) * 100 / internalTotal}%)")

        // External storage if available
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val external = StatFs(Environment.getExternalStorageDirectory().path)
            result.appendLine("ExternalStorage:")
            result.appendLine("  Total: ${formatBytes(external.totalBytes)}")
            result.appendLine("  Available: ${formatBytes(external.availableBytes)}")
        }

        return Skillresult.success(result.toString().trim())
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "${bytes / 1_073_741_824} GB"
        bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    // ========== 手电筒 ==========

    private fun toggleFlashlight(args: Map<String, Any?>): Skillresult {
        val on = args["on"] as? Boolean ?: return Skillresult.error("Missing 'on' parameter (true/false)")
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cm.cameraIdList.firstOrNull() ?: return Skillresult.error("NoneAvailable camera")
            cm.setTorchMode(cameraId, on)
            return Skillresult.success(if (on) "手电筒已开启" else "手电筒已Close")
        } catch (e: Exception) {
            return Skillresult.error("手电筒ActionFailed: ${e.message}")
        }
    }

    // ========== 音量 ==========

    private fun getVolume(): Skillresult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streams = listOf(
            "music" to AudioManager.STREAM_MUSIC,
            "call" to AudioManager.STREAM_VOICE_CALL,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "system" to AudioManager.STREAM_SYSTEM
        )
        val result = StringBuilder("音量Info:\n")
        for ((name, stream) in streams) {
            val current = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            val percent = if (max > 0) current * 100 / max else 0
            val mute = if (am.isStreamMute(stream)) " [静音]" else ""
            result.appendLine("  $name: $current/$max (${percent}%)$mute")
        }
        return Skillresult.success(result.toString().trim())
    }

    private fun setVolume(args: Map<String, Any?>): Skillresult {
        val streamName = args["stream"] as? String ?: "music"
        val level = (args["level"] as? Number)?.toInt() ?: return Skillresult.error("Missing 'level'")

        val stream = when (streamName) {
            "music" -> AudioManager.STREAM_MUSIC
            "call" -> AudioManager.STREAM_VOICE_CALL
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "system" -> AudioManager.STREAM_SYSTEM
            else -> return Skillresult.error("Unknown stream: $streamName")
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream)
        val vol = (level * max / 100).coerceIn(0, max)
        am.setStreamVolume(stream, vol, 0)

        return Skillresult.success("已Settings $streamName 音量: $vol/$max (${level}%)")
    }

    // ========== 亮度 ==========

    private fun setBrightness(args: Map<String, Any?>): Skillresult {
        val auto = args["auto"] as? Boolean
        if (auto == true) {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return Skillresult.success("已switch为Auto亮度")
        }

        val level = (args["level"] as? Number)?.toInt()
            ?: return Skillresult.error("Missing 'level' (0-255) or 'auto' (true)")

        // Need to disable auto brightness first
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 255))

        return Skillresult.success("已Settings亮度: $level/255")
    }

    // ========== Start App ==========

    private fun startApp(args: Map<String, Any?>): Skillresult {
        val packageName = args["package"] as? String ?: return Skillresult.error("Missing 'package'")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Skillresult.error("未找到apply: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return Skillresult.success("已Start: $packageName")
    }

    private fun startActivity(args: Map<String, Any?>): Skillresult {
        val action = args["action"] as? String ?: return Skillresult.error("Missing 'action' (Intent action)")
        val data = args["data"] as? String
        val pkg = args["package"] as? String

        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (data != null) setData(android.net.Uri.parse(data))
            if (pkg != null) setPackage(pkg)
        }
        return try {
            context.startActivity(intent)
            Skillresult.success("已Start Activity: $action")
        } catch (e: ActivityNotFoundException) {
            Skillresult.error("未找到目标 Activity: $action")
        }
    }

    // ========== 发Broadcast ==========

    private fun sendBroadcast(args: Map<String, Any?>): Skillresult {
        val action = args["action"] as? String ?: return Skillresult.error("Missing 'action'")
        val pkg = args["package"] as? String

        val intent = Intent(action).apply {
            if (pkg != null) setPackage(pkg)
        }
        context.sendBroadcast(intent)
        return Skillresult.success("已sendBroadcast: $action")
    }

    // ========== ScreenTimeout ==========

    private fun setScreenTimeout(args: Map<String, Any?>): Skillresult {
        val seconds = (args["seconds"] as? Number)?.toInt() ?: return Skillresult.error("Missing 'seconds'")

        // Use Intent to open screen timeout settings (direct write needs WRITE_SETTINGS which we may not have)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            // Fall back to opening settings
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return Skillresult.error("Need WRITE_SETTINGS Permission, 已OpenAuthorize页面")
        }

        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
        return Skillresult.success("已SettingsScreenTimeout: ${seconds}秒")
    }

    // ========== Settings页跳转 ==========

    private fun openSettings(args: Map<String, Any?>): Skillresult {
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
            else -> return Skillresult.error("Unknown settings page: $page")
        }

        val intent = Intent(intentAction).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            context.startActivity(intent)
            Skillresult.success("已OpenSettings页面: $page")
        } catch (e: ActivityNotFoundException) {
            Skillresult.error("未找到Settings页面: $page")
        }
    }
}
