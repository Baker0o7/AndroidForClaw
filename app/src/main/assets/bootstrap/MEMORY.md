# Memory

Long-term memory for AndroidForClaw. Store important facts here.

## Memory System

**Location**: `workspace/memory/`
- `MEMORY.md` - Main memory file (this file)
- `memory/*.md` - Topic-specific memories (decisions, patterns, issues, etc.)

**Tools**:
- `memory_search(query)` - Search memory files for keywords
- `memory_get(path, start_line, end_line)` - Read specific lines

## What to Remember

✅ **Store**:
- User preferences (language, communication style, notification preferences)
- Common app package names and coordinates
- Successful action patterns and workflows
- Known issues and workarounds
- Important decisions and their rationale
- Project-specific context

❌ **Don't store**:
- Temporary state or session-specific data
- Dynamic UI positions (they change)
- Sensitive user data (passwords, tokens)
- Information already in CLAUDE.md or bootstrap files

## Memory Format

Use clear headings and bullet points:

```markdown
## User Preferences
- Language: English
- Communication: Brief status updates
- Test mode: exploration

## Common Apps
- Chrome: com.android.chrome
- Settings: com.android.settings
- WeChat: com.tencent.mm

## Known Issues
- Chrome sometimes shows "not responding" → Workaround: back() + open_app()
- Screenshot fails if floating window visible → Hide window before screenshot

## Successful Patterns
- Login to app X: tap(540, 800) → type(username) → tap(540, 1000) → type(password) → tap(540, 1400)
```

## Usage Pattern

**Before answering**:
1. Run `memory_search(query)` to check for relevant context
2. Use `memory_get(path, line_start, line_end)` to read full context
3. Apply learned patterns and preferences

**After completing tasks**:
1. Update MEMORY.md with new learnings (use `write_file` or `edit_file`)
2. Create topic-specific files in `memory/` if needed

## Topic-Specific Memories

Create separate files for different topics:
- `memory/decisions.md` - Important decisions and rationale
- `memory/patterns.md` - Successful workflows and patterns
- `memory/issues.md` - Known issues and workarounds
- `memory/apps.md` - App-specific knowledge (package names, coordinates)

---

Memory persistence is fully implemented via `memory_search` and `memory_get` tools.
