---
name: clawhub
description: Search, install, and manage skills from ClawHub (clawhub.com). Use when you need to find new skills, install a skill, or check available skills. Also handles ClawHub token configuration when rate-limited.
metadata:
  {
    "openclaw": {
      "emoji": "🔍"
    }
  }
---

# ClawHub — Skill Hub

Search and install skills from [clawhub.com](https://clawhub.com).

## Available Tools

### skills_search

Search ClawHub for available skills.

```
skills_search(query="weather")
skills_search(query="")        // list all
skills_search(query="feishu", limit=10)
```

### skills_install

Install a skill by slug name.

```
skills_install(slug="weather")
skills_install(slug="x-twitter", version="1.2.0")
```

### clawhub_config

Configure ClawHub authentication token. Use when encountering 429 rate limit errors.

```
clawhub_config(action="set", token="your-token-here")  // save token
clawhub_config(action="get")                             // check token status
clawhub_config(action="clear")                           // remove token
```

**When to use:**
- If `skills_search` or `skills_install` returns HTTP 429 (Too Many Requests), ask the user for their ClawHub token and save it with `clawhub_config(action="set", token="...")`.
- Token is stored locally and automatically attached to all ClawHub API requests as `Authorization: Bearer <token>`.
- Get a token at [clawhub.com](https://clawhub.com) (account settings).

## Installed Skills

Skills are installed to `/sdcard/.androidforclaw/skills/`.

Users can:
- Edit skill SKILL.md files directly
- Add custom skills by creating new directories
- Remove skills by deleting directories

## Examples

Find skills for social media:
```
skills_search(query="twitter")
```

Install a skill:
```
skills_install(slug="x-twitter")
```

List all available skills:
```
skills_search(query="")
```

Handle rate limiting:
```
// If you get a 429 error, ask the user for their token:
clawhub_config(action="set", token="user-provided-token")
// Then retry the request
skills_search(query="twitter")
```
