---
name: weixin-channel
description: WeChat channel plugin. AndroidForClaw supports WeChat message sending and receiving (based on WeChat iLink AI interface). Triggered when user mentions WeChat, WeChat plugin, WeChat channel, WeChat login, or WeChat messages.
---

# WeChat Channel

AndroidForClaw **supports WeChat plugin**, allowing receiving and sending messages via WeChat.

## Architecture

Based on WeChat iLink AI interface (`ilinkai.weixin.qq.com`), binds WeChat account via QR code scan login. After that, Agent can:
- Receive WeChat private messages
- Reply to WeChat messages
- Long polling for real-time new message listening

Code is located in `extensions/weixin/` module.

## Configuration

Configuration file: `/sdcard/.androidforclaw/openclaw.json`

```json
{
  "channels": {
    "weixin": {
      "enabled": true,
      "baseUrl": "https://ilinkai.weixin.qq.com",
      "cdnBaseUrl": "https://novac2c.cdn.weixin.qq.com/c2c"
    }
  }
}
```

Configuration fields:
- `channels.weixin.enabled` — Whether to enable WeChat channel
- `channels.weixin.baseUrl` — API address (default `https://ilinkai.weixin.qq.com`)
- `channels.weixin.cdnBaseUrl` — CDN address (default `https://novac2c.cdn.weixin.qq.com/c2c`)
- `channels.weixin.routeTag` — Route tag (optional)
- `channels.weixin.model` — Override model for this channel (optional, format `providerId/modelId`)

## Login Process

1. Open App → Settings → Channel Management → WeChat
2. Click "Scan QR Code Login", App displays QR code
3. Scan with WeChat to confirm
4. After successful login, automatically start sending/receiving messages

Can also operate via `WeixinChannelActivity` interface.

## Use Cases

- User sends message to Agent via WeChat, Agent auto-replies
- As one of OpenClaw's messaging channels, alongside Feishu, Discord, etc.
- WeChat messages are routed to Agent session for processing

## Notes

- Need to scan QR code login first, token is saved locally
- Token validity is limited, need to re-scan when expired
- WeChat API has rate limits, avoid high-frequency sending
- This is a **real messaging channel plugin**, not UI automation simulated clicks
