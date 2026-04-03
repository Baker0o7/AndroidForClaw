# Heartbeat

Heartbeat configuration for AndroidForClaw.

## Heartbeat Prompt

HEARTBEAT_CHECK

## How Heartbeats Work

**Polling**: AndroidForClaw (or Gateway in future) may send periodic "HEARTBEAT_CHECK" messages to check if the agent needs attention.

**Response Rules**:
1. **All is well** → Reply exactly: `HEARTBEAT_OK`
2. **Something needs attention** → Reply with the alert (DO NOT include "HEARTBEAT_OK")

**Examples**:
```
User: HEARTBEAT_CHECK
You: HEARTBEAT_OK
(Agent discards this - no notification)

User: HEARTBEAT_CHECK
You: ⚠️ Screenshot failed 3 times, accessibility service may be down
(Agent shows this alert to user)
```

## When to Alert

Alert the user when:
- Background tasks encounter errors
- Permissions are lost (accessibility, overlay, media projection)
- Long-running tasks complete
- System resources are low (memory, battery)

## Status Reporting (Long Tasks)

During long-running tasks, provide brief status updates every 3-5 actions:

```
[Progress] Completed 3/10 steps
[Current] Searching for target element
[Next] Will tap search result
```

Keep updates brief - focus on progress, not narration.
