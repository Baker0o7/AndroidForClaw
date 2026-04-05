---
name: android-api
description: Android system API tool. Triggered when user asks to set alarm, timer, clipboard, flashlight, volume, brightness, battery query, start app, system settings, etc. Replaces UI automation with direct system API calls.
---

# Android API Skill

Directly call Android system APIs to operate device functions, more reliable and efficient than UI automation.

## Usage

Single tool `android_api`, routing to different operations via `action` parameter.

## Supported Operations

### Alarm/Timer
- `set_alarm` — Set alarm. Parameters: `hour`(0-23), `minute`(0-59), `message`(label, optional)
- `set_timer` — Set countdown. Parameters: `seconds`, `message`(label, optional)

```
android_api(action="set_alarm", hour=7, minute=30, message="Wake up")
android_api(action="set_timer", seconds=1800, message="Cooking")
```

### Clipboard
- `get_clipboard` — Read current clipboard content
- `set_clipboard` — Write to clipboard. Parameters: `text`

```
android_api(action="get_clipboard")
android_api(action="set_clipboard", text="Hello World")
```

### Battery/Storage
- `get_battery` — Get battery level and charging status
- `get_storage` — Get internal and external storage space

```
android_api(action="get_battery")
android_api(action="get_storage")
```

### Flashlight
- `flashlight` — Toggle flashlight. Parameters: `on`(true/false)

```
android_api(action="flashlight", on=true)
```

### Volume
- `get_volume` — Get all audio track volumes
- `set_volume` — Set volume. Parameters: `stream`(music/call/ring/notification/alarm/system), `level`(0-100)

```
android_api(action="set_volume", stream="music", level=50)
```

### Brightness
- `set_brightness` — Set screen brightness. Parameters: `level`(0-255) or `auto`(true)

```
android_api(action="set_brightness", level=128)
android_api(action="set_brightness", auto=true)
```

### Start App/Activity
- `start_app` — Start app. Parameters: `package`(package name)
- `start_activity` — Start Activity. Parameters: `action`(Intent action), `data?`(URI), `package?`

```
android_api(action="start_app", package="com.android.settings")
android_api(action="start_activity", action="android.intent.action.VIEW", data="https://example.com")
```

### Broadcast/Settings
- `send_broadcast` — Send broadcast. Parameters: `action`, `package?`
- `set_screen_timeout` — Set screen timeout. Parameters: `seconds`
- `open_settings` — Open system settings page. Parameters: `page`(wifi/bluetooth/battery/display/sound/storage/app/all)

```
android_api(action="open_settings", page="wifi")
android_api(action="set_screen_timeout", seconds=300)
```

## Best Practices

1. **Prefer API over UI automation**: System operations like setting alarm, adjusting volume, checking battery are 10x more reliable with direct API calls than screenshot + tap
2. **Note permissions**: Brightness adjustment requires WRITE_SETTINGS permission, will redirect to authorization page on first use
3. **Intent fallback**: Alarm/timer uses Intent to invoke system clock, actual interaction done by system
4. **Combine with device tool**: For operations API can't handle (like clicking inside an app), complement with device(action="act")
