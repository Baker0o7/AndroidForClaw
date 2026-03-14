---
name: clawhub
description: Search, install, and manage skills from ClawHub (clawhub.com). Use when you need to find new skills, install a skill, or check available skills.
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
