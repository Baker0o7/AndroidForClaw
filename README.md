# AndroidForClaw

[![Release](https://img.shields.io/badge/Release-v1.1.17-blue.svg)](https://github.com/SelectXn00b/AndroidForClaw/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://www.android.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **Let AI truly control your Android phone.**

Architecture aligned with [OpenClaw](https://github.com/openclaw/openclaw) (280k+ Stars), bringing full AI Agent capabilities to your phone — see the screen, tap apps, run code, connect platforms.

**[Docs](https://vcn23e479dhx.feishu.cn/wiki/UZtFwM6t9iArPVkMvRccwSran6d)** · **[Quick Start](#-quick-start)** · **[Community](#-community)**

---

## What Can AI Do for You

### Control Any App

WeChat, Alipay, TikTok, Taobao, Maps... **Anything you can do manually, AI can do too.**

```
You: Open WeChat and send "See you tomorrow" to John
AI:  → Open WeChat → Search John → Type message → Send ✅
```

### Cross-App Workflows

```
You: Got an address in WeChat, navigate me there
AI:  → Copy address from WeChat → Open Maps → Search → Start navigation
```

### Run Code

Execute commands via Termux SSH (Shell works directly, Python/Node.js need to be installed in Termux):

```
You: Use Python to analyze the CSV in my Downloads folder
AI:  → exec("python3 analyze.py") → Return analysis results
```

### Search & Fetch Web

```
You: Search for today's tech news
AI:  → web_search("tech news") → Return titles + links + summaries
```

### Multi-Platform Messaging

Control your phone AI remotely via Feishu, Discord, and more:

| Channel | Status |
|---------|--------|
| Feishu | ✅ Available (WebSocket real-time, DM/group, 39 Feishu tools) |
| Discord | ✅ Available (Gateway v10, DM/group, permission policies) |
| In-app Chat | ✅ Available |
| Telegram | 🔧 In Development |
| Slack | 🔧 In Development |

---

## Quick Start

### Download & Install

Download from the [Release page](https://github.com/SelectXn00b/AndroidForClaw/releases/latest):

| APK | Description | Required? |
|-----|-------------|-----------|
| **AndroidForClaw** | Main app (Accessibility Service, Agent, Gateway) | ✅ Required |
| **BrowserForClaw** | AI Browser (web automation) | Optional |

### 3 Steps to Get Started

1. **Install** — Download and install AndroidForClaw
2. **Configure** — Open the app, enter an API Key, enable Accessibility + Screen Capture permissions
3. **Chat** — Talk directly in the app, or send messages via Feishu/Discord

---

## Architecture

```
846 Kotlin source files · 167,000+ lines of code · 10 modules
```

### Core Features

| Feature | Description |
|---------|-------------|
| **Playwright Mode** | Screen ops aligned with Playwright |
| **Context Management** | 4-layer protection |
| **Multi-model** | Qwen 3.6 Plus · DeepSeek R1 · Claude Sonnet 4 · Gemini 2.5 |
| **MCP Server** | Expose accessibility/screenshot to external agents (port 8399) |
| **Skill System** | Built-in Skills + ClawHub online installation |

### General Tools (16)

| Tool | Function |
|------|----------|
| `device` | Screen ops: snapshot / tap / type / scroll / press |
| `read_file` | Read file contents |
| `write_file` | Create or overwrite files |
| `edit_file` | Precise file editing |
| `list_dir` | List directory contents |
| `exec` | Execute commands |
| `web_search` | Brave search engine |
| `web_fetch` | Fetch web page content |
| `javascript` | Execute JavaScript |
| `tts` | Text-to-speech |

### Supported Models

| Provider | Models | Notes |
|----------|--------|-------|
| **OpenRouter** | Qwen 3.6 Plus, Hunter Alpha, DeepSeek R1, Claude Sonnet 4 | Recommended |
| **Google** | Gemini 2.5 Pro, Gemini 2.5 Flash | Direct |
| **Anthropic** | Claude Sonnet 4, Claude Opus 4 | Direct |
| **OpenAI** | GPT-4.1, o3 | Direct |
| **Custom** | Any OpenAI-compatible API | Ollama, vLLM, etc. |

---

## Build from Source

```bash
git clone https://github.com/SelectXn00b/AndroidForClaw.git
cd AndroidForClaw
./gradlew assembleRelease
```

---

## Related Projects

| Project | Description |
|---------|-------------|
| [OpenClaw](https://github.com/openclaw/openclaw) | AI Agent framework (Desktop) |
| [iOSForClaw](https://github.com/SelectXn00b/iOSForClaw) | OpenClaw iOS client |
| [AndroidForClaw](https://github.com/SelectXn00b/AndroidForClaw) | OpenClaw Android client (this project) |

---

## Community

### Feishu Group

**[Click to join Feishu Group](https://applink.feishu.cn/client/chat/chatter/add_by_link?link_token=566r8836-6547-43e0-b6be-d6c4a5b12b74)**

### Discord

[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/rDpaFym2b8)

---

## License

MIT — [LICENSE](LICENSE)

---

⭐ **If this project helps you, please give it a Star!** ⭐
