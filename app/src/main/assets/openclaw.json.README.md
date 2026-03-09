# openclaw.json 配置说明

## 配置文件位置

应用运行时会在以下位置创建配置文件：
```
/data/data/com.xiaomo.androidforclaw/files/config/openclaw.json
```

如果配置文件不存在，应用会自动从 `assets/openclaw.json.default` 复制默认配置。

## 必填配置项

### 1. API Keys

配置文件使用环境变量占位符，需要替换为实际的 API Key：

```json
{
  "models": {
    "providers": {
      "anthropic": {
        "apiKey": "${ANTHROPIC_API_KEY}"  // 替换为你的 Anthropic API Key
      },
      "openrouter": {
        "apiKey": "${OPENROUTER_API_KEY}"  // 替换为你的 OpenRouter API Key
      }
    }
  }
}
```

### 2. Gateway Token

用于 Gateway API 认证：

```json
{
  "gateway": {
    "auth": {
      "token": "${GATEWAY_TOKEN}"  // 替换为随机生成的 token
    }
  }
}
```

### 3. 飞书配置（可选）

如果需要使用飞书集成：

```json
{
  "channels": {
    "feishu": {
      "accounts": {
        "default": {
          "enabled": true,
          "appId": "${FEISHU_APP_ID}",      // 替换为你的飞书 App ID
          "appSecret": "${FEISHU_APP_SECRET}"  // 替换为你的飞书 App Secret
        }
      }
    }
  }
}
```

## 环境变量替换

应用支持以下占位符格式：
- `${ANTHROPIC_API_KEY}` - 从环境变量或 MMKV 读取
- `${OPENROUTER_API_KEY}` - 从环境变量或 MMKV 读取
- `${FEISHU_APP_ID}` - 从环境变量或 MMKV 读取
- `${FEISHU_APP_SECRET}` - 从环境变量或 MMKV 读取
- `${GATEWAY_TOKEN}` - 从环境变量或 MMKV 读取

## 配置修改

1. **通过 ADB 修改**：
   ```bash
   adb pull /data/data/com.xiaomo.androidforclaw/files/config/openclaw.json
   # 编辑文件
   adb push openclaw.json /data/data/com.xiaomo.androidforclaw/files/config/openclaw.json
   ```

2. **通过应用界面**：
   应用设置 → 查看配置文件

3. **启用热重载**：
   配置文件修改后，应用会自动重新加载（需要在代码中启用 `ConfigLoader.enableHotReload()`）

## 默认配置说明

- **默认模型**: Claude Opus 4.6 (通过 Anthropic 或 OpenRouter)
- **Gateway 模式**: local（本地模式）
- **飞书集成**: 默认禁用
- **Discord 集成**: 默认禁用
- **TTS**: 使用 Edge TTS，中文女声

## 参考

完整配置选项请参考：
- [config/openclaw.json.example](../../../../../../../config/openclaw.json.example)
- [CLAUDE.md](../../../../../../../CLAUDE.md) - 配置系统说明
