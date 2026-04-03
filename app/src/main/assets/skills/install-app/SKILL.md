---
name: install-app
description: Install APK files to Android device
metadata:
  {
    "openclaw": {
      "always": false,
      "skillKey": "install-app",
      "primaryEnv": "android",
      "emoji": "📦"
    }
  }
---

# Install App Skill

Install APK files to Android device.

## Tools

### install_app

Install APK file at specified path.

**Parameters:**
- `apk_path` (required): APK file path, supports:
  - Absolute path: `/sdcard/Download/app.apk`
  - Relative paths search in common directories (/sdcard/, /sdcard/Download/, /sdcard/.androidforclaw/)
  - `content://` URI
- `allow_downgrade` (optional, default false): Whether to allow downgrade installation

**Returns:**
- Package name, version number, installation type (new install/upgrade/downgrade/reinstall)
- APK size

## Use Cases

1. **Install downloaded APK**: User downloaded an APK file and asks to install
2. **Upgrade app**: Install new version APK
3. **Batch install**: Multiple APKs need to be installed sequentially
4. **Skill install**: Install companion apps required by skills (e.g., BrowserForClaw, ScreenForClaw)

## Examples

```
User: Install /sdcard/Download/wechat.apk for me
→ install_app(apk_path="/sdcard/Download/wechat.apk")

User: Install this old version with downgrade
→ install_app(apk_path="/sdcard/Download/app-old.apk", allow_downgrade=true)
```

## Notes

- If device hasn't granted "Install unknown apps" permission, system shows confirmation dialog
- When confirmation dialog appears, can use `screenshot` + `tap` tools to automatically click confirm
- Downgrade installation is disabled by default, requires user to explicitly specify `allow_downgrade=true`
- APK file must be a valid Android installation package (complete signature)
