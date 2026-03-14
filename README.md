# 📱 AndroidForClaw

[![Release](https://img.shields.io/badge/Release-v1.0.2-blue.svg)](https://github.com/xiaomochn/AndroidForClaw/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://www.android.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **让 AI 真正掌控 Android 手机。**
>
> 底层对齐 [OpenClaw](https://github.com/openclaw/openclaw) 架构，在手机上实现完整的 AI Agent：看屏幕、点 App、跑代码、连平台。

**[📖 文档](https://vcn23e479dhx.feishu.cn/wiki/UZtFwM6t9iArPVkMvRccwSran6d)** · **[💬 飞书群](https://applink.feishu.cn/client/chat/chatter/add_by_link?link_token=566r8836-6547-43e0-b6be-d6c4a5b12b74)** · **[Discord](https://discord.gg/k9NKrXUN)**

---

## 30 秒上手

```bash
# 1. 下载安装 APK
adb install AndroidForClaw-v1.0.2-release.apk

# 2. 打开 App → 输入 API Key（或跳过用内置 Key）
# 3. 开启无障碍权限
# 4. 开始对话
```

或者直接从 [Release 页面](https://github.com/xiaomochn/AndroidForClaw/releases/latest) 下载。

---

## 🎯 能做什么

### 操控任何 App

通过 Accessibility Service，AI 能操作你手机上的任何应用：

```
你：帮我打开微信发消息给张三说"明天见"
AI：→ 打开微信 → 搜索张三 → 输入"明天见" → 发送 ✅
```

微信、支付宝、抖音、淘宝、高德、相机……凡是你能手动操作的，AI 都能。

### 跨应用联动

```
你：微信收到一个地址，帮我导航过去
AI：→ 微信复制地址 → 打开高德 → 搜索 → 开始导航
```

### 屏幕操作（Playwright 模式）

对齐 Playwright 的 ref-based 交互，不依赖坐标：

```python
device(action="snapshot")                         # 获取 UI 树 + 元素 ref
# → button '设置' [ref=e1], input '搜索' [ref=e2], item '张三' [ref=e3]

device(action="act", kind="tap", ref="e3")        # 点击 '张三'
device(action="act", kind="type", ref="e5", text="你好")  # 输入文字
device(action="act", kind="scroll", direction="down")     # 滚动
device(action="open", package_name="com.tencent.mm")      # 打开 App
```

### 执行代码

通过 Termux SSH 桥接，运行 Python / Node.js / Shell：

```python
exec(command="python3 -c 'print(1+1)'")   # → 2
exec(command="node -e 'console.log(42)'")  # → 42
exec(command="curl https://api.example.com/data")
```

### 多平台消息

| 渠道 | 状态 | 说明 |
|------|------|------|
| 飞书 | ✅ 可用 | WebSocket 实时连接，32 个飞书工具 |
| Discord | ✅ 可用 | Gateway 连接 |
| Telegram | 🔧 开发中 | Bot API |
| Slack | 🔧 开发中 | Socket Mode |
| Signal | 🔧 开发中 | Signal CLI |
| WhatsApp | 🔧 开发中 | Web Protocol |

### 技能扩展

从 [ClawHub](https://clawhub.com) 搜索安装新能力：

```python
skills_search(query="twitter")    # 搜索技能
skills_install(slug="x-twitter")  # 一键安装
```

20 个内置 Skills 可在 `/sdcard/.androidforclaw/skills/` 自由编辑。

---

## 🏗️ 技术架构

```
324 源文件 · 62,000+ 行代码 · 10 个模块
```

```
┌──────────────────────────────────────────┐
│  Channels                                 │
│  飞书 · Discord · Telegram · Slack ·      │
│  Signal · WhatsApp · 设备内对话            │
├──────────────────────────────────────────┤
│  Agent Runtime                            │
│  AgentLoop · 19 Tools · 20 Skills ·       │
│  Context Management (4层) · Memory        │
├──────────────────────────────────────────┤
│  Providers                                │
│  OpenRouter · Azure · Anthropic · OpenAI  │
├──────────────────────────────────────────┤
│  Android Platform                         │
│  Accessibility · Termux SSH · device tool │
│  MediaProjection · BrowserForClaw         │
└──────────────────────────────────────────┘
```

### 项目结构

```
AndroidForClaw/
├── app/                          # 主应用 (Agent + Gateway + UI)
│   ├── agent/                    #   AgentLoop, Tools, Skills, Context
│   ├── gateway/                  #   WebSocket Gateway + RPC
│   ├── providers/                #   LLM Provider (OpenAI compat)
│   ├── tools/device/             #   Playwright-aligned device tool
│   └── ui/                       #   Compose UI
├── extensions/
│   ├── feishu/                   # 飞书 (29 files)
│   ├── discord/                  # Discord (15 files)
│   ├── telegram/                 # Telegram (16 files)
│   ├── slack/                    # Slack (16 files)
│   ├── signal/                   # Signal (16 files)
│   ├── whatsapp/                 # WhatsApp (16 files)
│   ├── observer/                 # 无障碍服务
│   └── BrowserForClaw/           # AI 浏览器
├── self-control/                 # 自控模块
├── quickjs-executor/             # JavaScript 引擎
├── tests/e2e/                    # E2E 测试 (Node.js + ADB)
├── MAPPING.md                    # OpenClaw 源码映射
└── ARCHITECTURE.md               # 架构文档
```

### 设备目录

```
/sdcard/.androidforclaw/              ← 对齐 ~/.openclaw/
├── openclaw.json                     # 配置
├── skills/                           # 可编辑 Skills (20个)
│   ├── feishu-doc/SKILL.md
│   ├── weather/SKILL.md
│   ├── clawhub/SKILL.md
│   └── skill-creator/scripts/*.py
├── workspace/                        # 工作区
│   ├── SOUL.md                       # AI 人格
│   ├── memory/                       # 持久记忆
│   └── sessions/                     # 会话历史
├── .ssh/                             # SSH 密钥 (Termux)
└── termux_ssh.json                   # Termux 连接配置
```

---

## 🔧 19 个 Tools

| Tool | 功能 | 来源 |
|------|------|------|
| `device` | 屏幕操作 (snapshot/tap/type/scroll/open) | Playwright 对齐 |
| `read_file` / `write_file` / `edit_file` | 文件读写编辑 | OpenClaw 对齐 |
| `list_dir` | 目录浏览 | OpenClaw 对齐 |
| `exec` | 执行命令 (Termux SSH / 内置 shell) | OpenClaw 对齐 |
| `web_search` | Brave 搜索 | OpenClaw 对齐 |
| `web_fetch` | 网页抓取 | OpenClaw 对齐 |
| `javascript` | JS 执行 (QuickJS) | OpenClaw 对齐 |
| `skills_search` / `skills_install` | ClawHub 技能管理 | OpenClaw 对齐 |
| `memory_search` / `memory_get` | 语义记忆 | OpenClaw 对齐 |
| `config_get` / `config_set` | 配置管理 | OpenClaw 对齐 |
| `list_installed_apps` / `install_app` | 应用管理 | Android 特有 |
| `start_activity` | Activity 跳转 | Android 特有 |
| `stop` / `log` | 控制 | Android 特有 |

---

## 🛠️ 配置

`/sdcard/.androidforclaw/openclaw.json`

```json
{
  "models": {
    "providers": {
      "openrouter": {
        "baseUrl": "https://openrouter.ai/api/v1",
        "apiKey": "sk-or-v1-你的key",
        "models": [{"id": "openrouter/hunter-alpha", "reasoning": true, "contextWindow": 1048576}]
      }
    }
  },
  "channels": {
    "feishu": { "enabled": true, "appId": "cli_xxx", "appSecret": "xxx" }
  }
}
```

**Termux 配置**：App 内置向导，装 Termux → 粘贴一行命令 → 自动完成。

---

## 🧪 测试

```bash
# 单元测试 (JVM)
./gradlew :app:testDebugUnitTest              # ExecFacadeTool, TermuxBridge, SkillsHub

# 设备端 UI 测试
adb shell am instrument -w \
  -e class com.xiaomo.androidforclaw.ui.ModelSetupActivityUITest \
  com.xiaomo.androidforclaw.test/androidx.test.runner.AndroidJUnitRunner    # 35 tests

# E2E 测试 (Node.js + ADB)
node tests/e2e/chat-e2e.mjs          # 聊天链路 7 tests
node tests/e2e/skill-hub-e2e.mjs     # ClawHub 搜索
node tests/e2e/create-skill-e2e.mjs  # Skill 创建

# Channel 模块测试
./gradlew :extensions:telegram:testDebugUnitTest
./gradlew :extensions:slack:testDebugUnitTest
./gradlew :extensions:signal:testDebugUnitTest
./gradlew :extensions:whatsapp:testDebugUnitTest
```

---

## 🔨 从源码构建

```bash
git clone https://github.com/xiaomochn/AndroidForClaw.git
cd AndroidForClaw
export JAVA_HOME=/path/to/jdk17
./gradlew assembleRelease
adb install releases/AndroidForClaw-v1.0.2-release.apk
```

---

## 📄 License

MIT — [LICENSE](LICENSE)

---

## 🔗 相关链接

- [OpenClaw](https://github.com/openclaw/openclaw) — 架构参照
- [ClawHub](https://clawhub.com) — 技能市场
- [源码映射](MAPPING.md) — OpenClaw ↔ AndroidForClaw 文件对照
- [架构文档](ARCHITECTURE.md) — 详细架构设计

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给个 Star 支持一下！**

</div>
