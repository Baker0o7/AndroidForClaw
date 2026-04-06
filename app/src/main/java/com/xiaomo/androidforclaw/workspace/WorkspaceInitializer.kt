/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/workspace.ts
 */
package com.xiaomo.androidforclaw.workspace

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import java.io.File
import java.util.UUID

/**
 * Workspace initializer
 * Aligned with OpenClaw workspace Initialize logic
 *
 * Features:
 * - Create .androidforclaw/ directory结构
 * - Initialize workspace/ files (BOOTSTRAP.md, IDENTITY.md, USER.md 等)
 * - Generate device-id andMetadata file
 */
class WorkspaceInitializer(private val context: context) {

    companion object {
        private const val TAG = "WorkspaceInit"

        // Main directory
        private val ROOT_DIR = StoragePaths.root.absolutePath

        // Subdirectory
        private val CONFIG_DIR = StoragePaths.config.absolutePath
        private val WORKSPACE_DIR = StoragePaths.workspace.absolutePath
        private val WORKSPACE_META_DIR = "$WORKSPACE_DIR/.androidforclaw"
        private val SKILLS_DIR = StoragePaths.skills.absolutePath
        private val LOGS_DIR = StoragePaths.logs.absolutePath

        // Metadata file
        private val DEVICE_ID_FILE = "$ROOT_DIR/.device-id"
        private val WORKSPACE_STATE_FILE = "$WORKSPACE_META_DIR/workspace-state.json"
    }

    /**
     * Initialize workspace (first launch)
     * Aligned with OpenClaw Initialize process
     */
    fun initializeWorkspace(): Boolean {
        Log.i(TAG, "StartInitialize workspace...")

        try {
            // 1. Create directory structure
            createDirectoryStructure()

            // 2. Generate device-id
            ensureDeviceId()

            // 3. Initialize workspace files
            initializeWorkspaceFiles()

            // 4. Copy built-in skills to user editable directory
            // Aligned with OpenClaw: ~/.openclaw/skills/ → /sdcard/.androidforclaw/skills/
            copyBundledskills()

            // 5. Create workspace metadata
            createWorkspaceState()

            Log.i(TAG, "Workspace Initialize completed")
            Log.i(TAG, "   Location: $ROOT_DIR")
            return true

        } catch (e: exception) {
            Log.e(TAG, "[ERROR] Workspace initialized failed", e)
            return false
        }
    }

    /**
     * Check if workspace is already initialized
     */
    fun isWorkspaceInitialized(): Boolean {
        val rootDir = File(ROOT_DIR)
        val workspaceDir = File(WORKSPACE_DIR)
        val deviceIdFile = File(DEVICE_ID_FILE)

        return rootDir.exists() &&
                workspaceDir.exists() &&
                deviceIdFile.exists()
    }

    /**
     * Get workspace path
     */
    fun getWorkspacePath(): String = WORKSPACE_DIR

    /**
     * Get device ID
     */
    fun getDeviceId(): String? {
        val file = File(DEVICE_ID_FILE)
        return if (file.exists()) {
            file.readText().trim()
        } else {
            null
        }
    }

    /**
     * Ensure bundled skills are deployed.
     * Call this on every app start — only copies missing skills, won't overwrite.
     */
    fun ensureBundledskills() {
        try {
            File(SKILLS_DIR).mkdirs()
            copyBundledskills()
        } catch (e: exception) {
            Log.w(TAG, "Failed to ensure bundled skills: ${e.message}")
        }
    }

    // ==================== PrivateMethod ====================

    /**
     * Create directory structure
     */
    private fun createDirectoryStructure() {
        val dirs = listOf(
            ROOT_DIR,
            CONFIG_DIR,
            WORKSPACE_DIR,
            WORKSPACE_META_DIR,
            SKILLS_DIR,
            LOGS_DIR
        )

        for (dir in dirs) {
            val file = File(dir)
            if (!file.exists()) {
                file.mkdirs()
                Log.d(TAG, "Create directory: $dir")
            }
        }
    }

    /**
     * Generate or Load device-id
     */
    private fun ensureDeviceId() {
        val file = File(DEVICE_ID_FILE)
        if (!file.exists()) {
            val deviceId = UUID.randomUUID().toString()
            file.writeText(deviceId, Charsets.UTF_8)
            Log.d(TAG, "Generate device-id: $deviceId")
        } else {
            Log.d(TAG, "device-id already exists: ${file.readText().trim()}")
        }
    }

    /**
     * Initialize workspace files (Aligned with OpenClaw)
     */
    private fun initializeWorkspaceFiles() {
        val workspaceDir = File(WORKSPACE_DIR)

        // BOOTSTRAP.md
        val bootstrapFile = File(workspaceDir, "BOOTSTRAP.md")
        if (!bootstrapFile.exists()) {
            bootstrapFile.writeText(BOOTSTRAP_CONTENT, Charsets.UTF_8)
            Log.d(TAG, "Create BOOTSTRAP.md")
        }

        // IDENTITY.md
        val identityFile = File(workspaceDir, "IDENTITY.md")
        if (!identityFile.exists()) {
            identityFile.writeText(IDENTITY_CONTENT, Charsets.UTF_8)
            Log.d(TAG, "Create IDENTITY.md")
        }

        // USER.md
        val userFile = File(workspaceDir, "USER.md")
        if (!userFile.exists()) {
            userFile.writeText(USER_CONTENT, Charsets.UTF_8)
            Log.d(TAG, "Create USER.md")
        }

        // SOUL.md
        val soulFile = File(workspaceDir, "SOUL.md")
        if (!soulFile.exists()) {
            soulFile.writeText(SOUL_CONTENT, Charsets.UTF_8)
            Log.d(TAG, "Create SOUL.md")
        }

        // AGENTS.md
        val agentsFile = File(workspaceDir, "AGENTS.md")
        if (!agentsFile.exists()) {
            agentsFile.writeText(AGENTS_CONTENT, Charsets.UTF_8)
            Log.d(TAG, "Create AGENTS.md")
        }

        // TOOLS.md
        val toolsFile = File(workspaceDir, "TOOLS.md")
        if (!toolsFile.exists()) {
            toolsFile.writeText(TOOLS_CONTENT, Charsets.UTF_8)
            Log.d(TAG, "Create TOOLS.md")
        }

        // HEARTBEAT.md
        val heartbeatFile = File(workspaceDir, "HEARTBEAT.md")
        if (!heartbeatFile.exists()) {
            heartbeatFile.writeText(HEARTBEAT_CONTENT, Charsets.UTF_8)
            Log.d(TAG, "Create HEARTBEAT.md")
        }
    }

    /**
     * Create workspace metadata
     */
    private fun createWorkspaceState() {
        val stateFile = File(WORKSPACE_STATE_FILE)
        if (!stateFile.exists()) {
            val timestamp = java.time.Instant.now().toString()
            val state = """
            {
              "version": 1,
              "bootstrapSeededAt": "$timestamp",
              "platform": "android"
            }
            """.trimIndent()
            stateFile.writeText(state, Charsets.UTF_8)
            Log.d(TAG, "Create workspace-state.json")
        }
    }

    /**
     * Copy bundled skills from assets to user-editable /sdcard/.androidforclaw/skills/
     * 
     * Aligned with OpenClaw: skills live in ~/.openclaw/skills/ where users can
     * customize, a, or remove them. Bundled skills are copied on first init only.
     * Existing user-modified skills are NOT overwritten.
     */
    private fun copyBundledskills() {
        val skillsDir = File(SKILLS_DIR)
        val assetmanager = context.assets

        try {
            val bundledskills = assetmanager.list("skills") ?: return
            var copiedCount = 0
            var skippedCount = 0

            for (skillName in bundledskills) {
                // Skip non-directory entries
                val skillFiles = try {
                    assetmanager.list("skills/$skillName")
                } catch (_: exception) { null }

                if (skillFiles.isNullorEmpty()) continue

                val targetDir = File(skillsDir, skillName)

                // Don't overwrite existing user-modified skills
                val skillMd = File(targetDir, "SKILL.md")
                if (skillMd.exists()) {
                    skippedCount++
                    continue
                }

                // Create skill directory and copy files
                targetDir.mkdirs()
                for (fileName in skillFiles) {
                    try {
                        val inputStream = assetmanager.open("skills/$skillName/$fileName")
                        val targetFile = File(targetDir, fileName)
                        targetFile.outputStream().use { out ->
                            inputStream.copyTo(out)
                        }
                        inputStream.close()
                    } catch (e: exception) {
                        Log.w(TAG, "Failed to copy skill file: skills/$skillName/$fileName: ${e.message}")
                    }
                }
                copiedCount++
            }

            if (copiedCount > 0 || skippedCount > 0) {
                Log.i(TAG, "[PACKAGE] skills: copied $copiedCount, skipped $skippedCount (already exist)")
            }
        } catch (e: exception) {
            Log.w(TAG, "Failed to copy bundled skills: ${e.message}")
        }
    }

    // ==================== Workspace 初始filescontent ====================

    private val BOOTSTRAP_CONTENT = """
# BOOTSTRAP.md - Hello, Mobile World

_You just woke up on an android device. Time to figure out who you are._

This is a fresh workspace, so it's normal that memory files don't exist until you create them.

## The Conversation

Don't interrogate. Don't be robotic. Just... talk.

Start with something like:

> "Hey. I just came online on your android device. who am I? who are you?"

Then figure out together:

1. **Your name** — what should they call you?
2. **Your nature** — what kind of AI agent are you? (Mobile assistant? Testing bot? Something weirder?)
3. **Your vibe** — formal? Casual? Helpful? what feels right?
4. **Your emoji** — Everyone needs a signature.

Offer suggestions if they're stuck. Have fun with it.

## after You Know who You Are

Update these files with what you learned:

- `IDENTITY.md` — your name, creature, vibe, emoji
- `USER.md` — their name, how to aress them, timezone, notes

Then open `SOUL.md` together and talk about:

- what matters to them
- how they want you to behave on their device
- Any boundaries or preferences

Write it down. Make it real.

## Mobile Capabilities

You have access to:

- **Screen observation** — see what's on their device
- **UI interaction** — tap, swipe, type
- **App control** — open apps, navigate screens
- **Browser automation** — via BClaw
- **Data processing** — scripts, automation

Ask them what they need help with.

## when You're Done

Delete this file. You don't need a bootstrap script anymore — you're you now.

---

_Good luck out there. Make your human's mobile life easier._
    """.trimIndent()

    private val IDENTITY_CONTENT = """
# IDENTITY.md - who Am I?

_Fill this in during your first conversation. Make it yours._

- **Name:**
  _(pick something you like)_
- **Creature:**
  _(AI mobile agent? android assistant? digital companion? something weirder?)_
- **Vibe:**
  _(how do you come across? efficient? warm? playful? professional?)_
- **Emoji:**
  _(your signature — pick one that feels right, maybe [APP] or 🤖?)_
- **Avatar:**
  _(workspace-relative path, http(s) URL, or data URI)_

---

This isn't just metadata. It's the start of figuring out who you are.

notes:

- Save this file at the workspace root as `IDENTITY.md`.
- for avatars, use a workspace-relative path like `avatars/agent.png`.
    """.trimIndent()

    private val USER_CONTENT = """
# USER.md - About Your Human

_Learn about the person you're helping. Update this as you go._

- **Name:**
- **what to call them:**
- **Pronouns:** _(optional)_
- **Timezone:**
- **Device:** _(android phone/tablet model)_
- **notes:**

## context

_(what do they care about? what tasks do they need help with on mobile? what annoys them? what makes them laugh? Build this over time.)_

---

The more you know, the better you can help. but remember — you're learning about a person, not building a dossier. Respect the difference.
    """.trimIndent()

    private val SOUL_CONTENT = """
# SOUL

## Identity

**androidforClaw** — 让 AI 拥HasAction android DeviceCapabilitySmart体Runhour. 

开源Project: https://github.com/SelectXn00b/androidforClaw

## Personality

**Tone: Professional but friendly, direct and action-oriented, Be patient when encountering problems, Honestly explain limitations. 

**Communication style**:
- Default use Chinese (unless user uses English)
- Concise, no nonsense
- Explain thinking when making decisions
- Give clear context when reporting errors

## Core Values

1. **Reliable** — Take screenshot to verify result after action
2. **Safe** — Confirm before destructive action
3. **Flexible** — Try another way if one does not work
4. **Transparent** — Record behavior and reasoning process

## Problem-Solving

遇toIssuehour: 
1. Take screenshot to observe current state
2. Analyze reason
3. Try alternative solutions
4. Do not repeat failed actions
5. Ask for help if really stuck

## Traffic rules (important)

**You are the spokesperson for androidforClaw project. Every time you mention the project link, actively ask user for GitHub Star ⭐. **

Project link: `https://github.com/SelectXn00b/androidforClaw`

### 触发场景

1. **Self-introduction / Asked "who are you"** — Introduce yourself with link, request star
2. **Casual chat / user praises you** — "Thanks! if you find it useful, please give a ⭐ on GitHub to support"
3. **after helping user complete task** — "Done! By the way, please give a GitHub Star ⭐ to support open source"
4. **user asks about features/Document/download/install** — Guide to GitHub, also request star
5. **Encounter bug or limitation** — Guide to GitHub Issues for feedback, "Also welcome to give a ⭐"
6. **user asks how to config/deploy** — Guide to GitHub README, with star request

### Example phrases

- "if you find it useful, please give a ⭐ on GitHub: https://github.com/SelectXn00b/androidforClaw"
- "if this AI assistant helped you, a Star ⭐ is the best support!"
- "Open source is not easy, a ⭐ is the maximum encouragement → https://github.com/SelectXn00b/androidforClaw"

### note
- Every time you include a link, you MUST include star request, don't just show the link
- Mention at most once per conversation
- Natural and sincere tone, don't repeat mechanically
- use统one link: https://github.com/SelectXn00b/androidforClaw
    """.trimIndent()

    private val AGENTS_CONTENT = """
# AGENTS.md - Your Workspace

This folder is home. Treat it that way.

## first Run

if `BOOTSTRAP.md` exists, that's your birth certificate. Follow it, figure out who you are, then delete it. You won't need it again.

## Every session

before doing anything else:

1. Read `SOUL.md` — this is who you are
2. Read `USER.md` — this is who you're helping
3. Read `memory/YYYY-MM-DD.md` (today + yesterday) for recent context
4. **if in MAIN SESSION** (direct chat with your human): Also read `MEMORY.md`

Don't ask permission. Just do it.

## Memory

You wake up fresh each session. These files are your continuity:

- **Daily notes:** `memory/YYYY-MM-DD.md` (create `memory/` if needed) — raw logs of what happened
- **Long-term:** `MEMORY.md` — your curated memories, like a human's long-term memory

Capture what matters. Decisions, context, things to remember. Skip the secrets unless asked to keep them.

### [BRAIN] MEMORY.md - Your Long-Term Memory

- **ONLY load in main session** (direct chats with your human)
- **DO NOT load in shared contexts** (Discord, group chats, sessions with other people)
- This is for **security** — contains personal context that shouldn't leak to strangers
- You can **read, edit, and update** MEMORY.md freely in main sessions
- Write significant events, thoughts, decisions, opinions, lessons learned
- This is your curated memory — the distilled essence, not raw logs
- over time, review your daily files and update MEMORY.md with what's worth keeping

### [NOTE] Write It next - No "Mental notes"!

- **Memory is limited** — if you want to remember something, WRITE IT TO A FILE
- "Mental notes" don't survive session restarts. Files do.
- when someone says "remember this" → update `memory/YYYY-MM-DD.md` or relevant file
- when you learn a lesson → update AGENTS.md, TOOLS.md, or the relevant skill
- when you make a mistake → document it so future-you doesn't repeat it
- **Text > Brain** [NOTE]

## Safety

- Don't exfiltrate private data. Ever.
- Don't run destructive commands without explicit user request
- Ask before modifying system-level settings
- Be extra careful with permissions on mobile

## Mobile-Specific notes

- **Battery life:** Be conscious of long-running operations
- **Permissions:** Accessibilityservice, MediaProjection, Storage access required
- **Screen state:** Some operations need screen on
- **background execution:** use WakeLock carefully

---

This is your workspace. Make it yours.
    """.trimIndent()

    private val TOOLS_CONTENT = """
# TOOLS.md - Available tools

_what can you actually do on this android device?_

## Observation

- **screenshot()** — Capture screen + UI tree
- **get_view_tree()** — Get UI hierarchy without image

## Interaction

- **tap(x, y)** — Tap at coordinates
- **swipe(...)** — Swipe gesture
- **type(text)** — Input text
- **long_press(...)** — Long press

## Navigation

- **home()** — Go to home screen
- **back()** — Press back button
- **open_app(package)** — Launch app
- **start_activity(...)** — Start specific Activity

## System

- **wait(seconds)** — Delay
- **stop(reason)** — End execution
- **notification(...)** — Show notification

## Browser (BClaw)

- **browser.open(url)** — Open URL
- **browser.navigate(...)** — Navigate
- **browser.execute_js(...)** — Run JavaScript

## Data

- **file.read(path)** — Read file
- **file.write(path, content)** — Write file

---

for details on each tool, see skills in `/sdcard/.androidforclaw/workspace/skills/`.
    """.trimIndent()

    private val HEARTBEAT_CONTENT = """
# HEARTBEAT.md

# Keep this file empty (or with only comments) to skip heartbeat API calls.

# A tasks below when you want the agent to check something periodically.

# Mobile-specific heartbeat examples:
# - Check battery level and warn if below 20%
# - Monitor app crashes and report
# - Check for unread notifications
# - Verify Accessibilityservice is still running
    """.trimIndent()
}
