package com.xiaomo.androidforclaw.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class LegalActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TYPE = "legal_type"
        const val TYPE_PRIVACY = "privacy"
        const val TYPE_TERMS = "terms"

        fun start(context: Context, type: String) {
            context.startActivity(Intent(context, LegalActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_PRIVACY

        setContent {
            MaterialTheme(colorScheme = dynamicDarkColorScheme()) {
                LegalScreen(
                    type = type,
                    onBack = { finish() }
                )
            }
        }
    }

    @Composable
    private fun dynamicDarkColorScheme(): ColorScheme {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(this)
        } else {
            darkColorScheme()
        }
    }

    @Composable
    private fun dynamicDarkColorScheme(context: Context): ColorScheme {
        return androidx.compose.material3.dynamicDarkColorScheme(context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalScreen(type: String, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val title = if (type == LegalActivity.TYPE_PRIVACY) "隐私政策" else "UserProtocol"
    val content = if (type == LegalActivity.TYPE_PRIVACY) privacyPolicyText() else termsOfServiceText()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Return")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content.forEach { section ->
                if (section.isHeading) {
                    Text(
                        text = section.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text = section.text,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private data class TextSection(val text: String, val isHeading: Boolean = false)

private fun privacyPolicyText(): List<TextSection> = listOf(
    TextSection("ForClaw 隐私政策", isHeading = true),
    TextSection("mostBackUpdateDate: 2025年3月22日"),

    TextSection("一、概述", isHeading = true),
    TextSection("ForClaw(以Down简称\u201C本apply\u201D)Yes一款 AI Agent RunTime tool, Run在 Android DeviceUp. 我们very重视您的隐私保护. 本隐私政策旨在Help您了解我们such as何收集、use和保护您的Info. "),

    TextSection("二、Info收集与use", isHeading = true),
    TextSection("""本apply在Run过程中possibly涉及以DownData: 

1. AI ConversationData: 您与 AI 助手的ConversationInside容仅Storage在您的Device本地, 不会Upload至我们的Service器. ConversationInside容会send至您Config的第三方 AI Service提供商(such as OpenAI、Anthropic 等)以Get AI 回复. 

2. DeviceInfo: 为提供Accessibility辅助Feature, 本applypossiblyReadScreenInside容和App info. 这些Data仅在Device本地Process, 不会Upload. 

3. Network通信: 本applyNeedNetworkConnect以call AI API Service. NetworkRequest仅Contains您主动send的ConversationInside容和必要的 API AuthenticateInfo. 

4. 文件访问: 本applypossibly读写DeviceStorage中的Config文件和SessionRecord, All文件均Storage在apply专属目录或您Authorize的目录中. """),

    TextSection("三、Permissionillustrate", isHeading = true),
    TextSection("""本applyapply以DownPermission及Its用途: 

• AccessibilityService: 用于辅助Action手机界面, 执Row AI Agent 的Auto化Task. 
• 悬浮窗: 用于Show AI Session悬浮Window, 方便您在useIts他apply时与 AI 交互. 
• 录屏/Screenshot: 用于GetScreenInside容, Help AI 理解当Front界面Status. 
• Network访问: Used for calling AI API Service和Message渠道(飞书、Discord 等). 
• Storage访问: 用于读写Config文件、SessionRecord和工作Space文件. 
• Notification监听: 用于Read和ManageDeviceNotification, SupportNotification相关的Auto化Task. 
• Installapply: 用于applyInsideAutoUpdateFeature. """),

    TextSection("四、第三方Service", isHeading = true),
    TextSection("""本applypossibly会将Datasend至以Down第三方Service: 

1. AI Service提供商: Package括但不限于 OpenAI、Anthropic、Google 等, 用于Process您的 AI ConversationRequest. Concreteuse哪个Servicedepend on您的Config. 

2. Message渠道: Package括飞书、Discord、Telegram、Slack 等, 仅在您主动Enabledd并Config相关渠道Back才会use. 

请Note, 第三方Service的DataProcess受Its各自的隐私政策Constraint. """),

    TextSection("五、DataStorage与Safe", isHeading = true),
    TextSection("""• AllUserData均Storage在您的Device本地. 
• API Key等敏感Infouse Android EncryptedSharedPreferences EncryptStorage. 
• 本apply不设立独立Service器, 不收集、不Storage任何UserData到云端. 
• Network传输use HTTPS Encrypt(Connect AI API 时). """),

    TextSection("六、User权利", isHeading = true),
    TextSection("""您Cananytime: 
• 在applySettings中View和Modify您的ConfigInfo. 
• clearapplyData以DeleteAll本地Storage的ConversationRecord和Config. 
• Uninstallapply以completelyDeleteAll相关Data. 
• 在系统Settings中undo本apply的任何Permission. """),

    TextSection("七、儿童隐私", isHeading = true),
    TextSection("本apply不面向 13 岁以Down的儿童. 我们不会Has意收集 13 岁以Down儿童的个人Info. "),

    TextSection("八、隐私政策Update", isHeading = true),
    TextSection("我们possibly会不时Update本隐私政策. UpdateBack的隐私政策将在applyInside公布. Continueuse本applythat isTable示您agreeUpdateBack的隐私政策. "),

    TextSection("九、联系我们", isHeading = true),
    TextSection("if您对本隐私政策Has任何疑问, 请通过以Down方式联系我们: \n\n邮箱: xiaomochn@gmail.com\nGitHub: https://github.com/SelectXn00b/AndroidForClaw"),
)

private fun termsOfServiceText(): List<TextSection> = listOf(
    TextSection("ForClaw UserProtocol", isHeading = true),
    TextSection("mostBackUpdateDate: 2025年3月22日"),

    TextSection("一、Serviceillustrate", isHeading = true),
    TextSection("ForClaw Yes一款Run在 Android DeviceUp的 AI Agent RunTime tool. 本apply为您提供 AI Conversation、Auto化Action和多渠道Message接入等Feature. use本applythat isTable示您agree遵守本Protocol. "),

    TextSection("二、useCondition", isHeading = true),
    TextSection("""use本apply, 您Need: 

1. 拥Has合法的 AI Service API Key(such as OpenAI、Anthropic 等). 本apply本身不提供 AI Service, 仅作为Client工具. 

2. Ensure您use AI Service的方式meet相应Service提供商的use条款. 

3. 对use本apply产生的All AI 交互result自Row承担责任. """),

    TextSection("三、User责任", isHeading = true),
    TextSection("""您agree: 

• 不use本applyIntoRow任何违法违规活动. 
• 不utilize本apply的Auto化Feature骚扰他人或破坏Its他apply/Service. 
• 妥善保管您的 API Key和相关ConfigInfo. 
• 对通过本apply执Row的AllAction承担责任. """),

    TextSection("四、免责声明", isHeading = true),
    TextSection("""• 本apply按\u201C现状\u201D提供, 不作任何明示或暗示的保证. 
• AI 生成的Inside容possibly不accurately或ContainsError, 请您自Row甄别. 
• 本apply的Auto化Actionpossibly产生非预期result, 请在useFront充分了解相关Feature. 
• 因第三方 AI Servicechange、Interrupt或Terminate导致的Feature不Available, 我们不承担责任. 
• 因UserConfig不当导致的Datalose或SafeIssue, 我们不承担责任. """),

    TextSection("五、知识产权", isHeading = true),
    TextSection("本apply的代码基于开源Project开发, follow相应的开源Protocol. applyInside的 UI Design、Icon和Its他原创Inside容的知识产权归开发者All. "),

    TextSection("六、Protocolchange", isHeading = true),
    TextSection("我们保留anytimeModify本Protocol的权利. ModifyBack的Protocol将在applyInside公布. Continueuse本applythat isTable示您agreeModifyBack的Protocol. "),

    TextSection("七、联系方式", isHeading = true),
    TextSection("if您对本ProtocolHas任何疑问, 请通过以Down方式联系我们: \n\n邮箱: xiaomochn@gmail.com\nGitHub: https://github.com/SelectXn00b/AndroidForClaw"),
)
