---
name: weixin-channel
description: 微信渠道插件。AndroidForClaw 支持微信消息收发（基于微信 iLink AI 接口）。当用户提到微信、WeChat、微信插件、微信渠道、微信登录、微信消息时触发。
---

# 微信渠道 (Weixin Channel)

AndroidForClaw **支持微信插件**，可以通过微信接收和发送消息。

## 架构

基于微信 iLink AI 接口（`ilinkai.weixin.qq.com`），通过扫码登录绑定微信账号，之后 Agent 可以：
- 接收微信私聊消息
- 回复微信消息
- 长轮询实时监听新消息

代码位于 `extensions/weixin/` 模块。

## 配置

配置文件：`/sdcard/.androidforclaw/openclaw.json`

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

配置字段：
- `channels.weixin.enabled` — 是否启用微信渠道
- `channels.weixin.baseUrl` — API 地址（默认 `https://ilinkai.weixin.qq.com`）
- `channels.weixin.cdnBaseUrl` — CDN 地址（默认 `https://novac2c.cdn.weixin.qq.com/c2c`）
- `channels.weixin.routeTag` — 路由标签（可选）
- `channels.weixin.model` — 覆盖该渠道使用的模型（可选，格式 `providerId/modelId`）

## 登录流程

1. 打开 App → 设置 → 渠道管理 → 微信
2. 点击「扫码登录」，App 显示二维码
3. 用微信扫码确认
4. 登录成功后自动开始收发消息

也可以通过 `WeixinChannelActivity` 界面操作。

## 使用场景

- 用户通过微信给 Agent 发消息，Agent 自动回复
- 作为 OpenClaw 的一个消息渠道，与飞书、Discord 等并列
- 微信消息会路由到 Agent 会话处理

## 注意事项

- 需要先扫码登录，token 会保存在本地
- Token 有效期有限，过期需重新扫码
- 微信接口有频率限制，避免高频发送
- 这是 **真正的消息渠道插件**，不是 UI 自动化模拟点击
