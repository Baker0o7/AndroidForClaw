package com.xiaomo.androidforclaw.ui.skills

import kotlinx.serialization.Serializable

/**
 * skills 市场Data模型
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
 * skills 市场StaticData源
 */
object skillsMarketData {

    val categories = listOf(
        skillCategory("All", "[NET]"),
        skillCategory("Auto化", "[SYNC]"),
        skillCategory("Efficiency", "[STATS]"),
        skillCategory("开发工具", "[DEV]"),
        skillCategory("Search研究", "[SEARCH]"),
        skillCategory("通讯", "[CHAT]"),
        skillCategory("Smart家居", "[HOME]"),
        skillCategory("Safe", "[LOCK]"),
        skillCategory("自我into化", "[BRAIN]"),
    )

    /**
     * from awesome-openclaw-skills 热门 skills
     */
    val featuredskills = listOf(
        skillItem("capability-evolver", "Capability Evolver", "autogame-17", "agent 自我into化Capability", "35K+", "自我into化", "https://clawhub.ai/autogame-17/capability-evolver"),
        skillItem("self-improving-agent", "Self-Improving agent", "pskoett", "fromError中学习, 越用越聪明", "62.5K+", "自我into化", "https://clawhub.ai/pskoett/self-improving-agent"),
        skillItem("gog", "GOG", "steipete", "Google Workspace all家桶(Gmail/day历/Drive)", "14K+", "Efficiency", "https://clawhub.ai/steipete/gog"),
        skillItem("agent-browser", "agent Browser", "TheSethRose", "自main浏览器Auto化", "11K+", "Auto化", "https://clawhub.ai/TheSethRose/agent-browser"),
        skillItem("summarize", "Summarize", "steipete", "contentSmart摘need(URL/YouTube/播客)", "10K+", "Search研究", "https://clawhub.ai/steipete/summarize"),
        skillItem("github", "GitHub", "openclaw", "PR/Issue MonitorManage", "10K+", "开发工具", "https://clawhub.ai/openclaw/github"),
        skillItem("mission-control", "Mission Control", "openclaw", "晨间Task简报Aggregate", "8K+", "Efficiency", "https://clawhub.ai/openclaw/mission-control"),
        skillItem("frontend-design", "Frontend Design", "openclaw", "生产level UI 生成", "7K+", "开发工具", "https://clawhub.ai/openclaw/frontend-design"),
        skillItem("slack", "Slack", "openclaw", "TeamMessageAuto化", "6K+", "通讯", "https://clawhub.ai/openclaw/slack"),
        skillItem("tavily", "Tavily", "openclaw", "AI Optimize网页Search", "5K+", "Search研究", "https://clawhub.ai/openclaw/tavily"),
        skillItem("n8n-workflow", "N8N Workflow", "openclaw", "工作流编排引擎", "5K+", "Auto化", "https://clawhub.ai/openclaw/n8n-workflow"),
        skillItem("vercel", "Vercel", "openclaw", "自然languageDeploy", "4K+", "开发工具", "https://clawhub.ai/openclaw/vercel"),
        skillItem("elevenlabs-agent", "ElevenLabs agent", "openclaw", "语音合成and通话", "4K+", "通讯", "https://clawhub.ai/openclaw/elevenlabs-agent"),
        skillItem("obsidian", "Obsidian", "openclaw", "Knowledge BaseManage", "4K+", "Efficiency", "https://clawhub.ai/openclaw/obsidian"),
        skillItem("composio", "Composio", "openclaw", "860+ 工具集成platform", "3K+", "Auto化", "https://clawhub.ai/openclaw/composio"),
        skillItem("agent-memory", "agent Memory", "dennis-da-menace", "Persistentmemory系统", "3K+", "自我into化", "https://clawhub.ai/Dennis-Da-Menace/agent-memory"),
        skillItem("home-assistant", "Home Assistant", "openclaw", "本地Smart家居控制", "3K+", "Smart家居", "https://clawhub.ai/openclaw/home-assistant"),
        skillItem("agent-autopilot", "agent Autopilot", "edoserbia", "Heartbeat驱动自mainTaskexecution", "2K+", "Efficiency", "https://clawhub.ai/edoserbia/agent-autopilot"),
        skillItem("security-auditor", "Security Auditor", "openclaw", "skill AuditandMonitor", "2K+", "Safe", "https://clawhub.ai/openclaw/security-auditor"),
        skillItem("linear", "Linear", "openclaw", "Issue & Sprint Manage", "2K+", "开发工具", "https://clawhub.ai/openclaw/linear"),
        skillItem("discord", "Discord", "openclaw", "社区Manage", "2K+", "通讯", "https://clawhub.ai/openclaw/discord"),
        skillItem("exa-search", "Exa Search", "openclaw", "开发者专用Search", "2K+", "Search研究", "https://clawhub.ai/openclaw/exa-search"),
    )

    /**
     * 热门 skill collection(精选合集)
     */
    val collections = listOf(
        skillcollection("Voltagent 精选合集", "from 13,700+ 技can中Filter 5,400+ 优质技can", "https://github.com/Voltagent/awesome-openclaw-skills", "github", "🦞", "5,400+ skills · 21.8K ⭐"),
        skillcollection("Sundial Top skills", "Top most practical skills collection", "https://github.com/sundial-org/awesome-openclaw-skills", "github", "⭐", "定期Update"),
        skillcollection("中文必装Task list", "from 13,000+ 技can中精选 Top 10 must-install, 国inside友good", "https://www.cnblogs.com/informatics/p/19679935", "website", "🇨🇳", "Top 10 must-install"),
        skillcollection("5000+ 工作流指南", "Build a practical workflow in 10 minutes", "https://www.zymn.cc/2026/03/22/openclaw-skills-guide/", "website", "⚡", "Practical tutorial"),
        skillcollection("阿in云精选榜", "10,000+ skills selection + deployment tutorial", "https://developer.aliyun.com/article/1714848", "website", "☁️", "Including deployment tutorial"),
        skillcollection("awesome-openclaw Resource库", "skills + plugins + Dashboard + Memory complete guide", "https://github.com/alvinreal/awesome-openclaw", "github", "📚", "Full range of resources"),
    )

    /**
     * 底partAggregatecontentList
     */
    val aggregatedResources = listOf(
        skillItem("clawhub-ai", "ClawHub 官方市场", "OpenClaw", "官方技canRegisterTable, 13,700+ 社区技can", "13,700+", "", "https://clawhub.ai"),
        skillItem("aiagentstore", "AI agent Store 精选", "aiagentstore.ai", "2,999+ 精选minuteClasscollection", "2,999+", "", "https://aiagentstore.ai/ai-agent/awesome-openclaw-skills"),
        skillItem("openclaw-hub", "OpenClaw Hub 排Row", "openclaw-hub.org", "按nextload/星标排名技can排Row榜", "", "", "https://openclaw-hub.org/openclaw-hub-top-skills.html"),
        skillItem("vincentkoc", "Vincent Koc Resource集", "vincentkoc", "skills + plugins + MCP tools + DeployStack", "", "", "https://github.com/vincentkoc/awesome-openclaw"),
        skillItem("kdnuggets", "KDnuggets 10 Large repository", "kdnuggets", "10 count必学 OpenClaw GitHub 仓库", "", "", "https://www.kdnuggets.com/10-github-repositories-to-master-openclaw"),
        skillItem("thunderbit", "Thunderbit Top 10", "thunderbit", "2026 yearmost佳 OpenClaw 技can指南", "", "", "https://thunderbit.com/blog/best-skills-for-openclaw"),
    )
}
