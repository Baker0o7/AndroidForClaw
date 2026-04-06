package com.xiaomo.androidforclaw.ui.skills

import kotlinx.serialization.Serializable

/**
 * Skills market data models
 */
data class skillItem(
    val slug: String,
    val name: String,
    val author: String,
    val description: String,
    val downloads: String = "",
    val category: String = "",
    val clawhubUrl: String = "",
)

data class skillcollection(
    val name: String,
    val description: String,
    val url: String,
    val source: String = "", // "github" / "website"
    val coverEmoji: String = "[PACKAGE]",
    val stats: String = "",
)

data class skillCategory(
    val label: String,
    val emoji: String,
)

/**
 * Skills market static data source
 */
object skillsMarketData {

    val categories = listOf(
        skillCategory("All", "[NET]"),
        skillCategory("Automation", "[SYNC]"),
        skillCategory("Efficiency", "[STATS]"),
        skillCategory("Dev Tools", "[DEV]"),
        skillCategory("Research", "[SEARCH]"),
        skillCategory("Communication", "[CHAT]"),
        skillCategory("Smart Home", "[HOME]"),
        skillCategory("Security", "[LOCK]"),
        skillCategory("Self-improvement", "[BRAIN]"),
    )

    /**
     * Featured skills from awesome-openclaw-skills
     */
    val featuredskills = listOf(
        skillItem("capability-evolver", "Capability Evolver", "autogame-17", "Agent self-improvement capability", "35K+", "Self-improvement", "https://clawhub.ai/autogame-17/capability-evolver"),
        skillItem("self-improving-agent", "Self-Improving Agent", "pskoett", "Learn from errors, get smarter over time", "62.5K+", "Self-improvement", "https://clawhub.ai/pskoett/self-improving-agent"),
        skillItem("gog", "GOG", "steipete", "Google Workspace suite (Gmail/Calendar/Drive)", "14K+", "Efficiency", "https://clawhub.ai/steipete/gog"),
        skillItem("agent-browser", "Agent Browser", "TheSethRose", "Browser automation", "11K+", "Automation", "https://clawhub.ai/TheSethRose/agent-browser"),
        skillItem("summarize", "Summarize", "steipete", "Smart content extraction (URL/YouTube/Podcast)", "10K+", "Research", "https://clawhub.ai/steipete/summarize"),
        skillItem("github", "GitHub", "openclaw", "PR/Issue monitoring and management", "10K+", "Dev Tools", "https://clawhub.ai/openclaw/github"),
        skillItem("mission-control", "Mission Control", "openclaw", "Morning task briefing aggregation", "8K+", "Efficiency", "https://clawhub.ai/openclaw/mission-control"),
        skillItem("frontend-design", "Frontend Design", "openclaw", "Production-level UI generation", "7K+", "Dev Tools", "https://clawhub.ai/openclaw/frontend-design"),
        skillItem("slack", "Slack", "openclaw", "Team message automation", "6K+", "Communication", "https://clawhub.ai/openclaw/slack"),
        skillItem("tavily", "Tavily", "openclaw", "AI-optimized web search", "5K+", "Research", "https://clawhub.ai/openclaw/tavily"),
        skillItem("n8n-workflow", "N8N Workflow", "openclaw", "Workflow orchestration engine", "5K+", "Automation", "https://clawhub.ai/openclaw/n8n-workflow"),
        skillItem("vercel", "Vercel", "openclaw", "Natural language deployment", "4K+", "Dev Tools", "https://clawhub.ai/openclaw/vercel"),
        skillItem("elevenlabs-agent", "ElevenLabs Agent", "openclaw", "Voice synthesis and calls", "4K+", "Communication", "https://clawhub.ai/openclaw/elevenlabs-agent"),
        skillItem("obsidian", "Obsidian", "openclaw", "Knowledge base management", "4K+", "Efficiency", "https://clawhub.ai/openclaw/obsidian"),
        skillItem("composio", "Composio", "openclaw", "860+ tool integration platform", "3K+", "Automation", "https://clawhub.ai/openclaw/composio"),
        skillItem("agent-memory", "Agent Memory", "dennis-da-menace", "Persistent memory system", "3K+", "Self-improvement", "https://clawhub.ai/Dennis-Da-Menace/agent-memory"),
        skillItem("home-assistant", "Home Assistant", "openclaw", "Local smart home control", "3K+", "Smart Home", "https://clawhub.ai/openclaw/home-assistant"),
        skillItem("agent-autopilot", "Agent Autopilot", "edoserbia", "Heartbeat-driven task execution", "2K+", "Efficiency", "https://clawhub.ai/edoserbia/agent-autopilot"),
        skillItem("security-auditor", "Security Auditor", "openclaw", "Skill audit and monitoring", "2K+", "Security", "https://clawhub.ai/openclaw/security-auditor"),
        skillItem("linear", "Linear", "openclaw", "Issue & Sprint management", "2K+", "Dev Tools", "https://clawhub.ai/openclaw/linear"),
        skillItem("discord", "Discord", "openclaw", "Community management", "2K+", "Communication", "https://clawhub.ai/openclaw/discord"),
        skillItem("exa-search", "Exa Search", "openclaw", "Developer-focused search", "2K+", "Research", "https://clawhub.ai/openclaw/exa-search"),
    )

    /**
     * Featured skill collections
     */
    val collections = listOf(
        skillcollection("Voltagent Collection", "Filtered 5,400+ quality skills from 13,700+", "https://github.com/Voltagent/awesome-openclaw-skills", "github", "🦞", "5,400+ skills · 21.8K ⭐"),
        skillcollection("Sundial Top Skills", "Top most practical skills collection", "https://github.com/sundial-org/awesome-openclaw-skills", "github", "⭐", "Regular updates"),
        skillcollection("Must-Install Task List", "Top 10 must-install from 13,000+ skills, recommended by domestic users", "https://www.cnblogs.com/informatics/p/19679935", "website", "🇨🇳", "Top 10 must-install"),
        skillcollection("5000+ Workflow Guide", "Build a practical workflow in 10 minutes", "https://www.zymn.cc/2026/03/22/openclaw-skills-guide/", "website", "⚡", "Practical tutorial"),
        skillcollection("Aliyun Top Picks", "10,000+ skills selection + deployment tutorial", "https://developer.aliyun.com/article/1714848", "website", "☁️", "Including deployment tutorial"),
        skillcollection("Awesome-OpenClaw Resources", "Skills + plugins + Dashboard + Memory complete guide", "https://github.com/alvinreal/awesome-openclaw", "github", "📚", "Full range of resources"),
    )

    /**
     * Bottom aggregated content list
     */
    val aggregatedResources = listOf(
        skillItem("clawhub-ai", "ClawHub Official Market", "OpenClaw", "Official skill registry, 13,700+ community skills", "13,700+", "", "https://clawhub.ai"),
        skillItem("aiagentstore", "AI Agent Store Picks", "aiagentstore.ai", "2,999+ curated collection", "2,999+", "", "https://aiagentstore.ai/ai-agent/awesome-openclaw-skills"),
        skillItem("openclaw-hub", "OpenClaw Hub Ranking", "openclaw-hub.org", "Skills ranked by downloads/stars", "", "", "https://openclaw-hub.org/openclaw-hub-top-skills.html"),
        skillItem("vincentkoc", "Vincent Koc Resources", "vincentkoc", "Skills + plugins + MCP tools + DeployStack", "", "", "https://github.com/vincentkoc/awesome-openclaw"),
        skillItem("kdnuggets", "KDnuggets 10 Large Repository", "kdnuggets", "10 must-learn OpenClaw GitHub repositories", "", "", "https://www.kdnuggets.com/10-github-repositories-to-master-openclaw"),
        skillItem("thunderbit", "Thunderbit Top 10", "thunderbit", "2026 best OpenClaw skills guide", "", "", "https://thunderbit.com/blog/best-skills-for-openclaw"),
    )
}
