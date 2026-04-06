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

class androidApiskill(private val context: context) : skill {
    companion object {
        private const val TAG = "androidApiskill"
    }

    override val name = "android_api"
    override val description = """android 系统 API 工具. directly call system API ActionDeviceFeature, Noneneed UI Auto化. 
SupportAction: 
- set_alarm: Settings闹钟 (Parameters: hour, minute, message)
- set_timer: Settings倒计hour (Parameters: seconds, message)
- get_clipboard: Read剪贴板
- set_clipboard: Write剪贴板 (Parameters: text)
- get_battery: Get电池Status
- get_storage: GetStorageSpace
- flashlight: 开关手电筒 (Parameters: on=true/false)
- get_volume: Get音量Info
- set_volume: Settings音量 (Parameters: stream, level 0-100)
- set_brightness: SettingsScreenhighlight度 (Parameters: level 0-255, auto=true/false)
- start_app: Start App (Parameters: package)
- start_activity: Start Activity (Parameters: action, data?, package?)
- send_broadcast: sendBroadcast (Parameters: action, package?)
- set_screen_timeout: SettingsScreenTimeout (Parameters: seconds)
- open_settings: Open系统Settings页 (Parameters: page)"""

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

    // ========== 闹钟/定hour器 ==========

    private fun setAlarm(args: Map<String, Any?>): skillresult {
        val hour = (args["hour"] as? Number)?.toInt() ?: return skillresult.error("Missing 'hour'")
        val minute = (args["minute"] as? Number)?.toInt() ?: return skillresult.error("Missing 'minute'")
        val message = args["message"] as? String ?: "androidClaw 闹钟"

        val intent = Intent("android.intent.action.SET_ALARM").app {
            putExtra("android.intent.extra.alarm.HOUR", hour)
            putExtra("android.intent.extra.alarm.MINUTES", minute)
            putExtra("android.intent.extra.alarm.MESSAGE", message)
            aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            skillresult.success("alreadySettings闹钟: ${hour}hour${minute}minute - $message")
        } catch (e: ActivitynotFoundexception) {
            skillresult.error("not找tohour钟app")
        }
    }

    private fun setTimer(args: Map<String, Any?>): skillresult {
        val seconds = (args["seconds"] as? Number)?.toInt() ?: return skillresult.error("Missing 'seconds'")
        val message = args["message"] as? String ?: "androidClaw 定hour器"

        val intent = Intent("android.intent.action.SET_TIMER").app {
            putExtra("android.intent.extra.alarm.LENGTH", seconds)
            putExtra("android.intent.extra.alarm.MESSAGE", message)
            putExtra("android.intent.extra.alarm.SKIP_UI", true)
            aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            skillresult.success("alreadySettings定hour器: ${seconds}seconds - $message")
        } catch (e: ActivitynotFoundexception) {
            skillresult.error("not找tohour钟app")
        }
    }

    // ========== 剪贴板 ==========

    private fun getClipboard(): skillresult {
        val cm = context.getSystemservice(context.CLIPBOARD_SERVICE) as Clipboardmanager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return skillresult.success("剪贴板forNull")
        }
        val text = clip.getItemAt(0).text?.toString() ?: ""
        return skillresult.success("剪贴板content: $text")
    }

    private fun setClipboard(args: Map<String, Any?>): skillresult {
        val text = args["text"] as? String ?: return skillresult.error("Missing 'text'")
        val cm = context.getSystemservice(context.CLIPBOARD_SERVICE) as Clipboardmanager
        cm.setPrimaryClip(ClipData.newPlainText("androidClaw", text))
        return skillresult.success("alreadyCopyto剪贴板: ${text.take(50)}${if (text.length > 50) "..." else ""}")
    }

    // ========== 电池 ==========

    private fun getBattery(): skillresult {
        val bm = context.getSystemservice(context.BATTERY_SERVICE) as Batterymanager
        val level = bm.getIntProperty(Batterymanager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(Batterymanager.BATTERY_PROPERTY_STATUS)

        val statusStr = when (status) {
            Batterymanager.BATTERY_STATUS_CHARGING -> "充电中"
            Batterymanager.BATTERY_STATUS_DISCHARGING -> "放电中"
            Batterymanager.BATTERY_STATUS_FULL -> "already充满"
            Batterymanager.BATTERY_STATUS_NOT_CHARGING -> "not充电"
            else -> "Unknown"
        }

        val chargingInfo = if (status == Batterymanager.BATTERY_STATUS_CHARGING) {
            val currentNow = bm.getIntProperty(Batterymanager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentAvg = bm.getIntProperty(Batterymanager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            " (Current: ${currentNow / 1000}mA, 平均: ${currentAvg / 1000}mA)"
        } else ""

        return skillresult.success("电池: ${level}% | Status: $statusStr$chargingInfo")
    }

    // ========== Storage ==========

    private fun getStorage(): skillresult {
        val internal = StatFs(Environment.getDataDirectory().path)
        val internalTotal = internal.totalBytes
        val internalFree = internal.availableBytes

        val result = StringBuilder()
        result.appendLine("InternalStorage:")
        result.appendLine("  Total: ${formatBytes(internalTotal)}")
        result.appendLine("  Available: ${formatBytes(internalFree)}")
        result.appendLine("  already用: ${formatBytes(internalTotal - internalFree)} (${(internalTotal - internalFree) * 100 / internalTotal}%)")

        // External storage if available
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val external = StatFs(Environment.getExternalStorageDirectory().path)
            result.appendLine("ExternalStorage:")
            result.appendLine("  Total: ${formatBytes(external.totalBytes)}")
            result.appendLine("  Available: ${formatBytes(external.availableBytes)}")
        }

        return skillresult.success(result.toString().trim())
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "${bytes / 1_073_741_824} GB"
        bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    // ========== 手电筒 ==========

    private fun toggleFlashlight(args: Map<String, Any?>): skillresult {
        val on = args["on"] as? Boolean ?: return skillresult.error("Missing 'on' parameter (true/false)")
        val cm = context.getSystemservice(context.CAMERA_SERVICE) as Cameramanager
        try {
            val cameraId = cm.cameraIdList.firstorNull() ?: return skillresult.error("NoneAvailable camera")
            cm.setTorchMode(cameraId, on)
            return skillresult.success(if (on) "手电筒alreadyopen" else "手电筒alreadyClose")
        } catch (e: exception) {
            return skillresult.error("手电筒ActionFailed: ${e.message}")
        }
    }

    // ========== 音量 ==========

    private fun getVolume(): skillresult {
        val am = context.getSystemservice(context.AUDIO_SERVICE) as Audiomanager
        val streams = listOf(
            "music" to Audiomanager.STREAM_MUSIC,
            "call" to Audiomanager.STREAM_VOICE_CALL,
            "ring" to Audiomanager.STREAM_RING,
            "notification" to Audiomanager.STREAM_NOTIFICATION,
            "alarm" to Audiomanager.STREAM_ALARM,
            "system" to Audiomanager.STREAM_SYSTEM
        )
        val result = StringBuilder("音量Info:\n")
        for ((name, stream) in streams) {
            val current = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            val percent = if (max > 0) current * 100 / max else 0
            val mute = if (am.isStreamMute(stream)) " [静音]" else ""
            result.appendLine("  $name: $current/$max (${percent}%)$mute")
        }
        return skillresult.success(result.toString().trim())
    }

    private fun setVolume(args: Map<String, Any?>): skillresult {
        val streamName = args["stream"] as? String ?: "music"
        val level = (args["level"] as? Number)?.toInt() ?: return skillresult.error("Missing 'level'")

        val stream = when (streamName) {
            "music" -> Audiomanager.STREAM_MUSIC
            "call" -> Audiomanager.STREAM_VOICE_CALL
            "ring" -> Audiomanager.STREAM_RING
            "notification" -> Audiomanager.STREAM_NOTIFICATION
            "alarm" -> Audiomanager.STREAM_ALARM
            "system" -> Audiomanager.STREAM_SYSTEM
            else -> return skillresult.error("Unknown stream: $streamName")
        }

        val am = context.getSystemservice(context.AUDIO_SERVICE) as Audiomanager
        val max = am.getStreamMaxVolume(stream)
        val vol = (level * max / 100).coerceIn(0, max)
        am.setStreamVolume(stream, vol, 0)

        return skillresult.success("alreadySettings $streamName 音量: $vol/$max (${level}%)")
    }

    // ========== highlight度 ==========

    private fun setBrightness(args: Map<String, Any?>): skillresult {
        val auto = args["auto"] as? Boolean
        if (auto == true) {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return skillresult.success("alreadyswitchforAutohighlight度")
        }

        val level = (args["level"] as? Number)?.toInt()
            ?: return skillresult.error("Missing 'level' (0-255) or 'auto' (true)")

        // need to disable auto brightness first
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 255))

        return skillresult.success("alreadySettingshighlight度: $level/255")
    }

    // ========== Start App ==========

    private fun startApp(args: Map<String, Any?>): skillresult {
        val packageName = args["package"] as? String ?: return skillresult.error("Missing 'package'")
        val intent = context.packagemanager.getLaunchIntentforPackage(packageName)
            ?: return skillresult.error("not找toapp: $packageName")
        intent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return skillresult.success("alreadyStart: $packageName")
    }

    private fun startActivity(args: Map<String, Any?>): skillresult {
        val action = args["action"] as? String ?: return skillresult.error("Missing 'action' (Intent action)")
        val data = args["data"] as? String
        val pkg = args["package"] as? String

        val intent = Intent(action).app {
            aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (data != null) setData(android.net.Uri.parse(data))
            if (pkg != null) setPackage(pkg)
        }
        return try {
            context.startActivity(intent)
            skillresult.success("alreadyStart Activity: $action")
        } catch (e: ActivitynotFoundexception) {
            skillresult.error("not找to目标 Activity: $action")
        }
    }

    // ========== 发Broadcast ==========

    private fun sendBroadcast(args: Map<String, Any?>): skillresult {
        val action = args["action"] as? String ?: return skillresult.error("Missing 'action'")
        val pkg = args["package"] as? String

        val intent = Intent(action).app {
            if (pkg != null) setPackage(pkg)
        }
        context.sendBroadcast(intent)
        return skillresult.success("alreadysendBroadcast: $action")
    }

    // ========== ScreenTimeout ==========

    private fun setScreenTimeout(args: Map<String, Any?>): skillresult {
        val seconds = (args["seconds"] as? Number)?.toInt() ?: return skillresult.error("Missing 'seconds'")

        // use Intent to open screen timeout settings (direct write needs WRITE_SETTINGS which we may not have)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            // Fall back to opening settings
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).app {
                aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return skillresult.error("need WRITE_SETTINGS Permission, alreadyOpenAuthorize页面")
        }

        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
        return skillresult.success("alreadySettingsScreenTimeout: ${seconds}seconds")
    }

    // ========== Settings页跳转 ==========

    private fun openSettings(args: Map<String, Any?>): skillresult {
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
            else -> return skillresult.error("Unknown settings page: $page")
        }

        val intent = Intent(intentAction).app { aFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            context.startActivity(intent)
            skillresult.success("alreadyOpenSettings页面: $page")
        } catch (e: ActivitynotFoundexception) {
            skillresult.error("not找toSettings页面: $page")
        }
    }
}
