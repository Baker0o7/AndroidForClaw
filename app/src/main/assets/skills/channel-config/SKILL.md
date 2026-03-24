---
name: channel-config
description: Configure messaging channels in AndroidForClaw, including Feishu, Discord, WeChat, Telegram, WhatsApp, Signal, and Slack. Use when the user asks to configure, enable, disable, inspect, or fix channel settings such as tokens, appId/appSecret, dmPolicy, groupPolicy, requireMention, or connection status. For AndroidForClaw, these settings live in /sdcard/.androidforclaw/openclaw.json under channels.*.
---

# Channel Config

For AndroidForClaw, channel settings are stored in:

- `/sdcard/.androidforclaw/openclaw.json`

## Supported Channels

AndroidForClaw 支持以下消息渠道（全部已实现）：

| 渠道 | 配置路径 | 状态 |
|------|----------|------|
| 飞书 (Feishu) | `channels.feishu.*` | ✅ 已实现 |
| Discord | `channels.discord.*` | ✅ 已实现 |
| 微信 (WeChat) | `channels.weixin.*` | ✅ 已实现 |
| Telegram | `channels.telegram.*` | ✅ 已实现 |
| WhatsApp | `channels.whatsapp.*` | ✅ 已实现 |
| Signal | `channels.signal.*` | ✅ 已实现 |
| Slack | `channels.slack.*` | ✅ 已实现 |

## 配置路径

### 飞书 (Feishu)
- `channels.feishu.enabled`
- `channels.feishu.appId`
- `channels.feishu.appSecret`
- `channels.feishu.domain`
- `channels.feishu.connectionMode`
- `channels.feishu.dmPolicy`
- `channels.feishu.groupPolicy`
- `channels.feishu.requireMention`

### Discord
- `channels.discord.enabled`
- `channels.discord.token`
- `channels.discord.dmPolicy`
- `channels.discord.groupPolicy`
- `channels.discord.requireMention`
- `channels.discord.replyToMode`

### 微信 (WeChat)
- `channels.weixin.enabled`
- `channels.weixin.baseUrl` (默认 `https://ilinkai.weixin.qq.com`)
- `channels.weixin.cdnBaseUrl`
- `channels.weixin.routeTag`
- `channels.weixin.model` (覆盖渠道模型)

### Telegram
- `channels.telegram.enabled`
- `channels.telegram.token`
- `channels.telegram.dmPolicy`
- `channels.telegram.groupPolicy`

### WhatsApp
- `channels.whatsapp.enabled`

### Signal
- `channels.signal.enabled`

### Slack
- `channels.slack.enabled`
- `channels.slack.token`

## Workflow

1. Read `/sdcard/.androidforclaw/openclaw.json` first.
2. Update only the requested channel subtree under `channels.*`.
3. Preserve unrelated config.
4. If the user provides credentials, write them exactly.
5. Enable the channel when the user asks to configure it unless they explicitly say not to.
6. After writing config, tell the user what changed.
7. If asked to verify, check the app logs / connection status after config is saved.

## Notes

- Prefer structured config edits over unrelated file changes.
- Do not change model settings in this skill unless the user explicitly asks.
- For Feishu, common required fields are `enabled`, `appId`, `appSecret`.
- For Discord, common required fields are `enabled`, `token`.
- For WeChat, login via QR code in the app UI; config only needs `enabled: true`.
