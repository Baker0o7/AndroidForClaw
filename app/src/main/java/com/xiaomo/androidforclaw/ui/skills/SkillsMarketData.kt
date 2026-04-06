package com.xiaomo.androidforclaw.ui.skills

import kotlinx.serialization.Serializable

/**
 * Skills 市场Data模型
 */
data class SkillItem(
    val slug: String,
    val name: String,
    val author: String,
    val description: String,
    val downloads: String = "",
    val category: String = "",
    val clawhubUrl: String = "",
)

data class SkillCollection(
    val name: String,
    val description: String,
    val url: String,
    val source: String = "", // "github" / "website"
    val coverEmoji: String = "📦",
    val stats: String = "",
)

data class SkillCategory(
    val label: String,
    val emoji: String,
)

/**
 * Skills 市场StaticData源
 */
object SkillsMarketData {

    val categories = listOf(
        SkillCategory("All", "🌐"),
        SkillCategory("Auto化", "🔄"),
        SkillCategory("Efficiency", "📊"),
        SkillCategory("开发工具", "💻"),
        SkillCategory("Search研究", "🔍"),
        SkillCategory("通讯", "💬"),
        SkillCategory("Smart家居", "🏠"),
        SkillCategory("Safe", "🔒"),
        SkillCategory("自我Into化", "🧠"),
    )

    /**
     * from awesome-openclaw-skills 的热门 Skills
     */
    val featuredSkills = listOf(
        SkillItem("capability-evolver", "Capability Evolver", "autogame-17", "Agent 自我Into化Capability", "35K+", "自我Into化", "https://clawhub.ai/autogame-17/capability-evolver"),
        SkillItem("self-improving-agent", "Self-Improving Agent", "pskoett", "从Error中学习, 越用越聪明", "62.5K+", "自我Into化", "https://clawhub.ai/pskoett/self-improving-agent"),
        SkillItem("gog", "GOG", "steipete", "Google Workspace 全家桶(Gmail/日历/Drive)", "14K+", "Efficiency", "https://clawhub.ai/steipete/gog"),
        SkillItem("agent-browser", "Agent Browser", "TheSethRose", "自主浏览器Auto化", "11K+", "Auto化", "https://clawhub.ai/TheSethRose/agent-browser"),
        SkillItem("summarize", "Summarize", "steipete", "Inside容Smart摘要(URL/YouTube/播客)", "10K+", "Search研究", "https://clawhub.ai/steipete/summarize"),
        SkillItem("github", "GitHub", "openclaw", "PR/Issue MonitorManage", "10K+", "开发工具", "https://clawhub.ai/openclaw/github"),
        SkillItem("mission-control", "Mission Control", "openclaw", "晨间Task简报Aggregate", "8K+", "Efficiency", "https://clawhub.ai/openclaw/mission-control"),
        SkillItem("frontend-design", "Frontend Design", "openclaw", "生产级 UI 生成", "7K+", "开发工具", "https://clawhub.ai/openclaw/frontend-design"),
        SkillItem("slack", "Slack", "openclaw", "TeamMessageAuto化", "6K+", "通讯", "https://clawhub.ai/openclaw/slack"),
        SkillItem("tavily", "Tavily", "openclaw", "AI Optimize的网页Search", "5K+", "Search研究", "https://clawhub.ai/openclaw/tavily"),
        SkillItem("n8n-workflow", "N8N Workflow", "openclaw", "工作流编排引擎", "5K+", "Auto化", "https://clawhub.ai/openclaw/n8n-workflow"),
        SkillItem("vercel", "Vercel", "openclaw", "自然语言Deploy", "4K+", "开发工具", "https://clawhub.ai/openclaw/vercel"),
        SkillItem("elevenlabs-agent", "ElevenLabs Agent", "openclaw", "语音合成与通话", "4K+", "通讯", "https://clawhub.ai/openclaw/elevenlabs-agent"),
        SkillItem("obsidian", "Obsidian", "openclaw", "Knowledge BaseManage", "4K+", "Efficiency", "https://clawhub.ai/openclaw/obsidian"),
        SkillItem("composio", "Composio", "openclaw", "860+ 工具集成平台", "3K+", "Auto化", "https://clawhub.ai/openclaw/composio"),
        SkillItem("agent-memory", "Agent Memory", "dennis-da-menace", "Persistentmemory系统", "3K+", "自我Into化", "https://clawhub.ai/Dennis-Da-Menace/agent-memory"),
        SkillItem("home-assistant", "Home Assistant", "openclaw", "本地Smart家居控制", "3K+", "Smart家居", "https://clawhub.ai/openclaw/home-assistant"),
        SkillItem("agent-autopilot", "Agent Autopilot", "edoserbia", "Heartbeat驱动的自主Task执Row", "2K+", "Efficiency", "https://clawhub.ai/edoserbia/agent-autopilot"),
        SkillItem("security-auditor", "Security Auditor", "openclaw", "Skill Audit与Monitor", "2K+", "Safe", "https://clawhub.ai/openclaw/security-auditor"),
        SkillItem("linear", "Linear", "openclaw", "Issue & Sprint Manage", "2K+", "开发工具", "https://clawhub.ai/openclaw/linear"),
        SkillItem("discord", "Discord", "openclaw", "社区Manage", "2K+", "通讯", "https://clawhub.ai/openclaw/discord"),
        SkillItem("exa-search", "Exa Search", "openclaw", "开发者专用Search", "2K+", "Search研究", "https://clawhub.ai/openclaw/exa-search"),
    )

    /**
     * 热门 Skill Collection(精选合集)
     */
    val collections = listOf(
        SkillCollection("VoltAgent 精选合集", "从 13,700+ 技能中Filter 5,400+ 优质技能", "https://github.com/VoltAgent/awesome-openclaw-skills", "github", "🦞", "5,400+ skills · 21.8K ⭐"),
        SkillCollection("Sundial Top Skills", "Top most practical skills collection", "https://github.com/sundial-org/awesome-openclaw-skills", "github", "⭐", "定期Update"),
        SkillCollection("中文必装Task list", "从 13,000+ 技能中精选 Top 10 must-install, 国Inside友好", "https://www.cnblogs.com/informatics/p/19679935", "website", "🇨🇳", "Top 10 must-install"),
        SkillCollection("5000+ 工作流指南", "Build a practical workflow in 10 minutes", "https://www.zymn.cc/2026/03/22/openclaw-skills-guide/", "website", "⚡", "Practical tutorial"),
        SkillCollection("阿里云精选榜", "10,000+ skills selection + deployment tutorial", "https://developer.aliyun.com/article/1714848", "website", "☁️", "Including deployment tutorial"),
        SkillCollection("awesome-openclaw Resource库", "skills + plugins + Dashboard + Memory complete guide", "https://github.com/alvinreal/awesome-openclaw", "github", "📚", "Full range of resources"),
    )

    /**
     * 底部AggregateInside容List
     */
    val aggregatedResources = listOf(
        SkillItem("clawhub-ai", "ClawHub 官方市场", "OpenClaw", "官方技能RegisterTable, 13,700+ 社区技能", "13,700+", "", "https://clawhub.ai"),
        SkillItem("aiagentstore", "AI Agent Store 精选", "aiagentstore.ai", "2,999+ 精选分ClassCollection", "2,999+", "", "https://aiagentstore.ai/ai-agent/awesome-openclaw-skills"),
        SkillItem("openclaw-hub", "OpenClaw Hub 排Row", "openclaw-hub.org", "按Download/星标排名的技能排Row榜", "", "", "https://openclaw-hub.org/openclaw-hub-top-skills.html"),
        SkillItem("vincentkoc", "Vincent Koc Resource集", "vincentkoc", "skills + plugins + MCP tools + DeployStack", "", "", "https://github.com/vincentkoc/awesome-openclaw"),
        SkillItem("kdnuggets", "KDnuggets 10 Large repository", "kdnuggets", "10 个必学的 OpenClaw GitHub 仓库", "", "", "https://www.kdnuggets.com/10-github-repositories-to-master-openclaw"),
        SkillItem("thunderbit", "Thunderbit Top 10", "thunderbit", "2026 年most佳 OpenClaw 技能指南", "", "", "https://thunderbit.com/blog/best-skills-for-openclaw"),
    )
}
