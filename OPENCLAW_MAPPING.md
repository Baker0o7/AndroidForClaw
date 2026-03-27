# OpenClaw ↔ AndroidForClaw Alignment Mapping

> Last updated: 2026-03-27
> OpenClaw commit: latest main
> AndroidForClaw commit: d087fcf77

---

## 1. Agent Core (Runner / Loop / Context)

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `agents/pi-embedded-runner/run.ts` | `agent/loop/AgentLoop.kt` | **Aligned** | Iterative tool loop, merged with subscribe.ts |
| `agents/pi-embedded-subscribe.ts` | `agent/loop/AgentLoop.kt` (progressFlow) | **Aligned** | Progress events merged into AgentLoop |
| `agents/pi-embedded-runner/run/attempt.ts` | `agent/loop/AgentLoop.kt` (runLlmCall) | **Aligned** | Single LLM attempt with error handling |
| `agents/tool-loop-detection.ts` | `agent/loop/ToolLoopDetection.kt` | **Aligned** | Config-driven, 4 detectors, threshold validation |
| `agents/pi-embedded-helpers/errors.ts` | `agent/context/ContextErrors.kt` | **Aligned** | All error types: context overflow, transient HTTP, role ordering, billing |
| `shared/assistant-error-format.ts` | `agent/context/ContextErrors.kt` | **Aligned** | HTTP_STATUS_CODE_PREFIX_RE |
| `agents/system-prompt.ts` | `agent/context/ContextBuilder.kt` | **Aligned** | System prompt composition, SILENT_REPLY_TOKEN |
| `agents/bootstrap-budget.ts` | `agent/context/ContextBuilder.kt` | **Aligned** | Bootstrap file budget |
| `agents/context-window-guard.ts` | `agent/context/ContextWindowGuard.kt` | **Aligned** | 16K minimum, token resolution |
| `agents/context.ts` | `agent/context/ContextWindowGuard.kt` | **Aligned** | Context window lookup |
| `agents/compaction.ts` | `agent/context/MessageCompactor.kt` | **Aligned** | Token estimation, summary generation |
| `agents/tool-result-context-guard.ts` | `agent/context/ToolResultContextGuard.kt` | **Aligned** | Tool result size bounding |
| `agents/tool-result-truncation.ts` | `agent/context/ToolResultTruncator.kt` | **Aligned** | Tool result truncation |
| `auto-reply/tokens.ts` | `agent/subagent/SubagentPromptBuilder.kt` | **Aligned** | SILENT_REPLY_TOKEN = "NO_REPLY" |

## 2. Subagent System

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `agents/subagent-registry.ts` | `agent/subagent/SubagentRegistry.kt` | **Aligned** | In-memory registry + queries |
| `agents/subagent-registry.types.ts` | `agent/subagent/SubagentTypes.kt` | **Aligned** | Types, constants, enums |
| `agents/subagent-registry.store.ts` | `agent/subagent/SubagentRegistryStore.kt` | **Aligned** | JSON disk persistence |
| `agents/subagent-registry-queries.ts` | `agent/subagent/SubagentRegistry.kt` | **Aligned** | Merged into registry |
| `agents/subagent-registry-state.ts` | `agent/subagent/SubagentRegistryStore.kt` | **Aligned** | Merged into store |
| `agents/subagent-spawn.ts` | `agent/subagent/SubagentSpawner.kt` | **Aligned** | Coroutine-based spawning |
| `agents/subagent-control.ts` | `agent/subagent/SubagentSpawner.kt` | **Aligned** | Kill/steer merged into spawner |
| `agents/subagent-announce.ts` | `agent/subagent/SubagentPromptBuilder.kt` | **Aligned** | Announcement formatting |
| `agents/subagent-announce-dispatch.ts` | `agent/subagent/SubagentSpawner.kt` | **Aligned** | Dispatch merged into spawner |
| `agents/subagent-announce-queue.ts` | `agent/subagent/SubagentSpawner.kt` | **Aligned** | Queue merged into spawner |
| `agents/subagent-attachments.ts` | `agent/subagent/SubagentAttachments.kt` | **Aligned** | 5MB/50 files/1MB per file limits |
| `agents/subagent-lifecycle-events.ts` | `agent/subagent/SubagentHooks.kt` | **Aligned** | 4 lifecycle hook points |
| `agents/subagent-orphan-recovery.ts` | `agent/subagent/SubagentOrphanRecovery.kt` | **Aligned** | Orphan recovery with retry |
| `agents/subagent-capabilities.ts` | `agent/subagent/SubagentTypes.kt` | **Aligned** | Merged into types |
| `agents/subagent-depth.ts` | `agent/subagent/SubagentSpawner.kt` | **Aligned** | Depth tracking in spawner |
| N/A | `agent/subagent/SessionVisibilityGuard.kt` | **Aligned** | 4 visibility modes (SELF/TREE/AGENT/ALL) |
| `agents/subagent-registry-cleanup.ts` | `agent/subagent/SubagentRegistry.kt` | **Partial** | Cleanup in registry, no separate file |
| `agents/subagent-registry-completion.ts` | `agent/subagent/SubagentRegistry.kt` | **Partial** | Completion in registry, no separate file |

## 3. Agent Tools

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `agents/tools/sessions-spawn-tool.ts` | `agent/tools/SessionsSpawnTool.kt` | **Aligned** | |
| `agents/tools/sessions-send-tool.ts` | `agent/tools/SessionsSendTool.kt` | **Aligned** | |
| `agents/tools/sessions-list-tool.ts` | `agent/tools/SessionsListTool.kt` | **Aligned** | |
| `agents/tools/sessions-history-tool.ts` | `agent/tools/SessionsHistoryTool.kt` | **Aligned** | Redaction + 80KB cap |
| `agents/tools/sessions-yield-tool.ts` | `agent/tools/SessionsYieldTool.kt` | **Aligned** | |
| `agents/tools/session-status-tool.ts` | `agent/tools/SessionStatusTool.kt` | **Aligned** | |
| `agents/tools/subagents-tool.ts` | `agent/tools/SubagentsTool.kt` | **Aligned** | list/kill/steer |
| `agents/tools/web-fetch.ts` | `agent/tools/WebFetchTool.kt` | **Aligned** | |
| `agents/tools/web-search.ts` | `agent/tools/WebSearchTool.kt` | **Aligned** | Brave Search API |
| `agents/tools/canvas-tool.ts` | `agent/tools/CanvasTool.kt` | **Aligned** | |
| `agents/tools/memory-tool.ts` | `agent/tools/memory/MemoryGetSkill.kt` + `MemorySearchSkill.kt` | **Aligned** | Split into get/search |
| `agents/bash-tools.exec.ts` | `agent/tools/ExecTool.kt` + `ExecFacadeTool.kt` | **Aligned** | Termux SSH bridge |
| `agents/apply-patch.ts` | `agent/tools/EditFileTool.kt` + `WriteFileTool.kt` | **Aligned** | |
| `agents/pi-tools.read.ts` | `agent/tools/ReadFileTool.kt` | **Aligned** | |
| `agents/pi-tools.schema.ts` (list_dir) | `agent/tools/ListDirTool.kt` | **Aligned** | |
| `agents/tools/browser-tool.ts` | `agent/tools/device/DeviceTool.kt` | **Aligned** | Playwright-style snapshot+act |
| `agents/tool-catalog.ts` | `agent/tools/ToolRegistry.kt` + `AndroidToolRegistry.kt` | **Aligned** | |
| `agents/openclaw-tools.ts` | `agent/tools/ToolCallDispatcher.kt` | **Aligned** | |
| `agents/tools/tts-tool.ts` | N/A | **Missing** | No LLM-facing TTS tool |
| `agents/tools/pdf-tool.ts` | N/A | **Missing** | No PDF tool |
| `agents/tools/image-generate-tool.ts` | N/A | **Missing** | No image generation tool |
| `agents/tools/image-tool.ts` | N/A | **Missing** | No image handling tool |
| `agents/tools/agents-list-tool.ts` | N/A | **Missing** | No agents-list tool |
| `agents/tools/cron-tool.ts` | N/A | **Missing** | No LLM-facing cron tool (cron exists via gateway RPC) |
| `agents/tools/message-tool.ts` | `agent/tools/FeishuSendImageSkill.kt` | **Partial** | Only Feishu image sending |
| `agents/tools/nodes-tool.ts` | N/A | **Missing** | No nodes management tool |
| `agents/tools/gateway-tool.ts` | N/A | **Missing** | No gateway invocation tool |

## 4. Skills System

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `agents/skills.ts` | `agent/skills/SkillsLoader.kt` | **Aligned** | Bundled + managed + workspace |
| `agents/skills-install.ts` | `agent/skills/SkillInstaller.kt` + `ClawHubClient.kt` | **Aligned** | |
| `agents/skills/` (subsystem) | `agent/skills/SkillParser.kt` + `SkillDocument.kt` + `SkillMetadata.kt` | **Aligned** | |
| N/A | `agent/skills/SkillLockManager.kt` | **Android-only** | Lock file management |
| N/A | `agent/skills/SkillStatusBuilder.kt` | **Android-only** | Status display |
| `browser/client.ts` | `agent/skills/BrowserForClawSkill.kt` | **Aligned** | |
| `browser/client-actions-*.ts` | `agent/skills/browser/*.kt` | **Aligned** | 6 browser skill files |

## 5. Model / Provider System

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `agents/model-catalog.ts` | `config/ProviderRegistry.kt` | **Partial** | Loads from assets/providers.json |
| `agents/model-selection.ts` | `config/ConfigLoader.kt` | **Partial** | Simplified selection |
| `agents/model-auth.ts` | `providers/ApiAdapter.kt` | **Partial** | API key header shaping |
| `agents/model-fallback.ts` | N/A | **Missing** | No model fallback chain |
| `agents/model-fallback.types.ts` | N/A | **Missing** | |
| `agents/models-config.ts` | `config/ModelConfig.kt` | **Partial** | Simplified |
| `agents/defaults.ts` | `config/OpenClawConfig.kt` | **Aligned** | Default model config |
| `agents/pi-embedded-runner/model.ts` | `providers/UnifiedLLMProvider.kt` | **Partial** | No provider normalization |
| `agents/pi-embedded-payloads.ts` | `providers/ApiAdapter.kt` | **Partial** | Basic request shaping |
| `agents/model-id-normalization.ts` | N/A | **Missing** | |
| `agents/model-compat.ts` | N/A | **Missing** | |
| `agents/model-ref-profile.ts` | N/A | **Missing** | |
| `agents/model-scan.ts` | N/A | **Missing** | |
| `agents/model-suppression.ts` | N/A | **Missing** | |
| `agents/model-tool-support.ts` | N/A | **Missing** | |
| `agents/api-key-rotation.ts` | N/A | **Missing** | No API key rotation on rate limit |
| `agents/fast-mode.ts` | N/A | **Missing** | |
| Provider-specific (anthropic-vertex, bedrock, cloudflare, etc.) | N/A | **N/A** | Server-side only |

## 6. Session / History

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `config/sessions/store.ts` | `agent/session/SessionManager.kt` | **Aligned** | |
| `config/sessions/types.ts` | `agent/session/SessionManager.kt` | **Aligned** | Merged |
| `config/sessions/paths.ts` | `session/JsonlSessionStorage.kt` | **Aligned** | JSONL storage |
| `config/sessions/transcript.ts` | `agent/session/HistorySanitizer.kt` | **Aligned** | |
| `config/sessions/group.ts` | N/A | **Missing** | No group session |
| `config/sessions/artifacts.ts` | N/A | **Missing** | No session artifacts |
| `config/sessions/metadata.ts` | N/A | **Missing** | No session metadata store |
| `config/sessions/disk-budget.ts` | N/A | **Missing** | No disk budget |
| `config/sessions/store-maintenance.ts` | N/A | **Missing** | No store maintenance |
| `config/sessions/store-migrations.ts` | N/A | **Missing** | No store migrations |

## 7. Memory System

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `memory/manager.ts` | `agent/memory/MemoryManager.kt` | **Aligned** | |
| `memory/` (search, index) | `agent/memory/MemoryIndex.kt` + `EmbeddingProvider.kt` | **Aligned** | SQLite vector search |
| `memory/` (chunking) | `agent/memory/ChunkUtils.kt` | **Aligned** | |

## 8. Configuration

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `config/config.ts` + `types.*.ts` | `config/OpenClawConfig.kt` | **Aligned** | Full config model |
| `config/io.ts` | `config/ConfigLoader.kt` | **Aligned** | |
| `config/paths.ts` | `workspace/StoragePaths.kt` | **Aligned** | /sdcard/.androidforclaw/ |
| `config/agent-limits.ts` | `config/OpenClawConfig.kt` | **Aligned** | Concurrent/depth limits |
| `config/env-substitution.ts` | N/A | **Missing** | No ${ENV_VAR} substitution |
| `config/merge-config.ts` | N/A | **Missing** | No config merging |
| `config/includes.ts` | N/A | **Missing** | No config includes |
| `config/legacy.ts` | N/A | **Missing** | No legacy migration |
| `config/zod-schema.ts` | N/A | **Missing** | No schema validation |
| `config/backup-rotation.ts` | `config/ConfigBackupManager.kt` | **Aligned** | |

## 9. Gateway

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `gateway/server.ts` + `server.impl.ts` | `gateway/GatewayServer.kt` + `GatewayController.kt` | **Aligned** | NanoHTTPD-based |
| `gateway/server-methods.ts` | `gateway/GatewayService.kt` | **Aligned** | RPC dispatch |
| `gateway/server-methods/chat.ts` | `gateway/methods/AgentMethods.kt` | **Aligned** | |
| `gateway/server-methods/config.ts` | `gateway/methods/ConfigMethods.kt` | **Aligned** | |
| `gateway/server-methods/sessions.ts` | `gateway/methods/SessionMethods.kt` | **Aligned** | |
| `gateway/server-methods/models.ts` | `gateway/methods/ModelsMethods.kt` | **Aligned** | |
| `gateway/server-methods/cron.ts` | `gateway/methods/CronMethods.kt` | **Aligned** | |
| `gateway/server-methods/health.ts` | `gateway/methods/HealthMethods.kt` | **Aligned** | |
| `gateway/server-methods/skills.ts` | `gateway/methods/SkillsMethods.kt` | **Aligned** | |
| `gateway/server-chat.ts` | `gateway/MainEntryAgentHandler.kt` | **Aligned** | |
| `gateway/auth.ts` | `gateway/security/TokenAuth.kt` | **Aligned** | |
| `gateway/boot.ts` | `core/MyApplication.kt` + `core/MainEntryNew.kt` | **Aligned** | |
| `gateway/server-ws-runtime.ts` | `gateway/websocket/GatewayWebSocketServer.kt` | **Aligned** | |
| `gateway/protocol/schema/*.ts` | `gateway/protocol/ProtocolTypes.kt` + `FrameSerializer.kt` | **Aligned** | |
| `gateway/server-methods/browser.ts` | N/A | **Missing** | No browser gateway method |
| `gateway/server-methods/devices.ts` | N/A | **Missing** | No devices gateway method |
| `gateway/server-methods/nodes.ts` | N/A | **Missing** | No nodes gateway method |
| `gateway/server-methods/secrets.ts` | N/A | **Missing** | No secrets gateway method |
| `gateway/server-methods/send.ts` | N/A | **Missing** | No send gateway method |
| `gateway/server-methods/connect.ts` | N/A | **Partial** | Handled in GatewaySession (client side) |
| `gateway/chat-abort.ts` | `gateway/GatewayController.kt` | **Aligned** | Abort via activeJobs cancel |
| `gateway/chat-attachments.ts` | `gateway/GatewayController.kt` | **Aligned** | Image attachment parsing |
| `gateway/config-reload.ts` | N/A | **Missing** | No hot config reload |
| `gateway/server-cron.ts` | `gateway/methods/CronMethods.kt` | **Aligned** | |
| `gateway/server-model-catalog.ts` | `gateway/methods/ModelsMethods.kt` | **Aligned** | |

## 10. Cron System

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `cron/service.ts` | `cron/CronService.kt` + `CronInitializer.kt` | **Aligned** | |
| `cron/store.ts` | `cron/CronStore.kt` | **Aligned** | |
| `cron/parse.ts` + `schedule.ts` | `cron/CronScheduleParser.kt` | **Aligned** | |
| `cron/types.ts` | `cron/CronTypes.kt` | **Aligned** | |
| `cron/run-log.ts` | `cron/CronRunLog.kt` | **Aligned** | |

## 11. Auto-Reply / Reply Pipeline

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `auto-reply/reply/agent-runner.ts` | `agent/loop/AgentLoop.kt` | **Aligned** | runReplyAgent merged into AgentLoop |
| `auto-reply/reply/agent-runner-execution.ts` | `agent/loop/AgentLoop.kt` | **Aligned** | Fallback, error handling |
| `auto-reply/dispatch.ts` | `core/MainEntryNew.kt` + `core/MessageQueueManager.kt` | **Partial** | Simplified dispatch |
| `auto-reply/reply/queue.ts` | `core/MessageQueueManager.kt` | **Partial** | Basic queue |
| `auto-reply/reply/block-streaming.ts` | N/A | **Missing** | No block streaming (batch only) |
| `auto-reply/reply/block-reply-pipeline.ts` | N/A | **Missing** | |
| `auto-reply/reply/block-reply-coalescer.ts` | N/A | **Missing** | |
| `auto-reply/chunk.ts` | N/A | **Missing** | No text chunking for channels |
| `auto-reply/command-detection.ts` | N/A | **Missing** | No slash command detection |
| `auto-reply/commands-registry.ts` | N/A | **Missing** | No command registry |
| `auto-reply/heartbeat.ts` | N/A | **Missing** | No heartbeat system |
| `auto-reply/inbound-debounce.ts` | N/A | **Missing** | No inbound debounce |
| `auto-reply/model.ts` | N/A | **Missing** | No model directive extraction |
| `auto-reply/thinking.ts` | N/A | **Partial** | Thinking level in config only |
| `auto-reply/templating.ts` | N/A | **Missing** | No reply templates |
| `auto-reply/fallback-state.ts` | N/A | **Missing** | No fallback state tracking |

## 12. Shared Utilities

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `shared/assistant-error-format.ts` | `agent/context/ContextErrors.kt` | **Aligned** | |
| `shared/subagents-format.ts` | `agent/tools/SessionsListTool.kt` | **Aligned** | formatDurationCompact |
| `shared/text/reasoning-tags.ts` | `util/ReasoningTagFilter.kt` | **Aligned** | |
| `shared/chat-content.ts` | N/A | **Missing** | |
| `shared/chat-envelope.ts` | N/A | **Missing** | |
| `shared/frontmatter.ts` | `agent/skills/SkillParser.kt` | **Aligned** | Frontmatter parsing |
| `shared/text-chunking.ts` | `agent/memory/ChunkUtils.kt` | **Aligned** | |

## 13. Browser / Device

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `browser/client.ts` | `browser/BrowserToolClient.kt` | **Aligned** | HTTP client |
| `browser/client-actions-*.ts` | `agent/skills/browser/*.kt` | **Aligned** | 6 action files |
| `browser/chrome-mcp.ts` | `mcp/ObserverMcpServer.kt` | **Partial** | MCP server |
| N/A | `agent/tools/device/DeviceTool.kt` | **Android-only** | Playwright-style device control |
| N/A | `agent/tools/device/SnapshotBuilder.kt` | **Android-only** | Accessibility → ARIA snapshot |
| N/A | `agent/tools/device/RefManager.kt` | **Android-only** | UI element ref IDs |

## 14. Image / Media

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `agents/pi-embedded-helpers/image-sanitization.ts` | `media/ImageSanitizer.kt` | **Aligned** | Max 1200px, 5MB |
| `agents/tool-images.ts` | `media/ImageSanitizer.kt` | **Aligned** | |
| `media/` | N/A | **Missing** | No general media handling |

## 15. Channels

| OpenClaw (TypeScript) | Android (Kotlin) | Status | Notes |
|---|---|---|---|
| `channels/` (Discord, Telegram, Slack, WhatsApp, Signal, etc.) | `ui/activity/*ChannelActivity.kt` | **Partial** | Config UI only, no runtime channel plugins |
| N/A | `config/FeishuConfigAdapter.kt` | **Android-only** | Feishu-specific |

---

## Summary: Alignment Score by Category

| Category | Aligned | Partial | Missing | Android-Only |
|---|---|---|---|---|
| Agent Core (loop/context/errors) | 14 | 0 | 0 | 0 |
| Subagent System | 15 | 2 | 0 | 0 |
| Agent Tools | 16 | 1 | 7 | 15+ |
| Skills System | 5 | 0 | 0 | 2 |
| Model/Provider | 2 | 5 | 9 | 0 |
| Session/History | 4 | 0 | 5 | 0 |
| Memory | 3 | 0 | 0 | 0 |
| Configuration | 5 | 0 | 5 | 0 |
| Gateway | 14 | 1 | 5 | 0 |
| Cron | 5 | 0 | 0 | 0 |
| Auto-Reply Pipeline | 2 | 2 | 10 | 0 |
| Shared Utilities | 5 | 0 | 2 | 0 |
| Browser/Device | 2 | 1 | 0 | 3 |
| Image/Media | 2 | 0 | 1 | 0 |
| **Total** | **94** | **12** | **44** | **20+** |

---

## Key Gaps (Priority Order)

### High Priority (Core Agent Behavior)
1. **Model Fallback Chain** (`model-fallback.ts`) - No primary/fallback model switching
2. **API Key Rotation** (`api-key-rotation.ts`) - No rotation on rate limit errors
3. **Model ID Normalization** (`model-id-normalization.ts`) - No provider alias handling

### Medium Priority (Reply Pipeline)
4. **Block Streaming** (`block-streaming.ts`, `block-reply-pipeline.ts`) - Batch only, no streaming
5. **Command Detection/Registry** (`command-detection.ts`, `commands-registry.ts`) - No slash commands
6. **Heartbeat System** (`heartbeat.ts`) - No keepalive mechanism
7. **Inbound Debounce** (`inbound-debounce.ts`) - No message deduplication

### Medium Priority (Tools)
8. **TTS Tool** (`tts-tool.ts`) - No LLM-facing TTS tool (TTS exists via gateway)
9. **PDF Tool** (`pdf-tool.ts`) - No PDF reading
10. **Image Generation Tool** (`image-generate-tool.ts`) - No image gen
11. **Cron Tool** (`cron-tool.ts`) - No LLM-facing cron tool
12. **Nodes/Gateway Tools** - No device management tools

### Lower Priority (Infrastructure)
13. **Session Metadata/Artifacts/Maintenance** - No session lifecycle management
14. **Config Merging/Includes/Validation** - No advanced config features
15. **Hot Config Reload** - No runtime config refresh
16. **Fallback State Tracking** - No model failure state

---

## Android-Only Features (No OpenClaw Equivalent)

| Feature | Files |
|---|---|
| Accessibility-based device control | `DeviceController.kt`, `DeviceTool.kt`, `SnapshotBuilder.kt` |
| Android gesture skills (tap/swipe/type/home/back) | `TapSkill.kt`, `SwipeSkill.kt`, `TypeSkill.kt`, etc. |
| Camera eye skill | `EyeSkill.kt`, `CameraCaptureManager.kt` |
| Screenshot capture | `ScreenshotSkill.kt` |
| App management (open/install/list) | `OpenAppSkill.kt`, `InstallAppSkill.kt`, `ListInstalledAppsSkill.kt` |
| Termux bridge (SSH shell) | `TermuxBridgeTool.kt`, `TermuxSSHPool.kt` |
| ClawIME input method | `ClawImeInputSkill.kt`, `ClawIMEManager.kt` |
| Floating window | `SessionFloatWindow.kt` |
| MCP server for external agents | `ObserverMcpServer.kt` |
| App self-update | `AppUpdater.kt` |
