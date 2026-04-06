package com.xiaomo.androidforclaw.ui.activity

import android.content.context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Arrowback
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

        fun start(context: context, type: String) {
            context.startActivity(Intent(context, LegalActivity::class.java).app {
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
                    onback = { finish() }
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
    private fun dynamicDarkColorScheme(context: context): ColorScheme {
        return androidx.compose.material3.dynamicDarkColorScheme(context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalScreen(type: String, onback: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val title = if (type == LegalActivity.TYPE_PRIVACY) "隐私政策" else "userProtocol"
    val content = if (type == LegalActivity.TYPE_PRIVACY) privacyPolicyText() else termsOfserviceText()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    Iconbutton(onClick = onback) {
                        Icon(Icons.AutoMirrored.Filled.Arrowback, contentDescription = "Return")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { paing ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .paing(paing)
                .verticalScroll(rememberScrollState())
                .paing(horizontal = 20.dp, vertical = 16.dp),
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
    TextSection("forClaw 隐私政策", isHeading = true),
    TextSection("mostbackUpdateDate: 2025year3month22day"),

    TextSection("one、概述", isHeading = true),
    TextSection("forClaw(bynext简称\u201C本app\u201D)Yesone款 AI agent RunTime tool, Runin android DeviceUp. 我们very重视您隐私保护. 本隐私政策旨inHelp您解我们such as何collect、useand保护您Info. "),

    TextSection("two、Infocollectanduse", isHeading = true),
    TextSection("""本appinRunover程中possibly涉及bynextData: 

1. AI ConversationData: 您and AI 助手Conversationcontent仅Storagein您Device本地, notwillUpload至我们service器. Conversationcontentwillsend至您config第three方 AI service提供商(such as OpenAI、Anthropic 等)byGet AI return复. 

2. DeviceInfo: for提供Accessibility辅助Feature, 本apppossiblyReadScreencontentandApp info. thissomeData仅inDevice本地Process, notwillUpload. 

3. Network通信: 本appneednetwork connectionbycall AI API service. NetworkRequest仅Contains您proactivesendConversationcontentand必need API AuthenticateInfo. 

4. filesaccess: 本apppossiblyread/writeDeviceStorage中configfilesandsessionRecord, Allfiles均Storageinapp专属directoryor您Authorizedirectory中. """),

    TextSection("three、Permissionillustrate", isHeading = true),
    TextSection("""本appappbynextPermission及Its用途: 

• Accessibilityservice: 用于辅助Action手机界面, execution AI agent Auto化Task. 
• 悬浮窗: 用于Show AI session悬浮Window, 方便您inuseIts他apphourand AI interaction. 
• 录屏/Screenshot: 用于GetScreencontent, Help AI 理解whenFront界面Status. 
• Networkaccess: used for calling AI API serviceandMessage渠道(飞书、Discord 等). 
• Storageaccess: 用于read/writeconfigfiles、sessionRecordand工作Spacefiles. 
• notification监听: 用于ReadandManageDevicenotification, Supportnotification相关Auto化Task. 
• Installapp: 用于appinsideAutoUpdateFeature. """),

    TextSection("four、第three方service", isHeading = true),
    TextSection("""本apppossiblywillwillDatasend至bynext第three方service: 

1. AI service提供商: Package括butnot限于 OpenAI、Anthropic、Google 等, 用于Process您 AI ConversationRequest. Concreteusewhichcountservicedepend on您config. 

2. Message渠道: Package括飞书、Discord、Telegram、Slack 等, 仅in您proactiveEnable并config相关渠道back才willuse. 

pleasenote, 第three方serviceDataProcess受Its各自隐私政策Constraint. """),

    TextSection("five、DataStorageandSafe", isHeading = true),
    TextSection("""• AlluserData均Storagein您Device本地. 
• API Key等敏感Infouse android EncryptedSharedPreferences EncryptStorage. 
• 本appnot设立独立service器, notcollect、notStorage任何userDatato云端. 
• Network传输use HTTPS Encrypt(Connect AI API hour). """),

    TextSection("six、user权利", isHeading = true),
    TextSection("""您cananytime: 
• inappSettings中ViewandModify您configInfo. 
• clearappDatabyDeleteAll本地StorageConversationRecordandconfig. 
• UninstallappbycompletelyDeleteAll相关Data. 
• in系统Settings中undo本app任何Permission. """),

    TextSection("seven、儿童隐私", isHeading = true),
    TextSection("本appnot面向 13 岁bynext儿童. 我们notwillHas意collect 13 岁bynext儿童count人Info. "),

    TextSection("eight、隐私政策Update", isHeading = true),
    TextSection("我们possiblywillnothourUpdate本隐私政策. Updateback隐私政策willinappinside公布. Continueuse本appthat isTable示您agreeUpdateback隐私政策. "),

    TextSection("nine、联系我们", isHeading = true),
    TextSection("if您correct本隐私政策Has任何疑问, pleasethroughbynext方式联系我们: \n\n邮箱: xiaomochn@gmail.com\nGitHub: https://github.com/SelectXn00b/androidforClaw"),
)

private fun termsOfserviceText(): List<TextSection> = listOf(
    TextSection("forClaw userProtocol", isHeading = true),
    TextSection("mostbackUpdateDate: 2025year3month22day"),

    TextSection("one、serviceillustrate", isHeading = true),
    TextSection("forClaw Yesone款Runin android DeviceUp AI agent RunTime tool. 本appfor您提供 AI Conversation、Auto化Actionandmany渠道Message接入等Feature. use本appthat isTable示您agree遵守本Protocol. "),

    TextSection("two、useCondition", isHeading = true),
    TextSection("""use本app, 您need: 

1. 拥Has合法 AI service API Key(such as OpenAI、Anthropic 等). 本app本身not提供 AI service, 仅作forClient工具. 

2. Ensure您use AI service方式meet相shouldservice提供商usecount款. 

3. correctuse本app产生All AI interactionresult自Row承担责任. """),

    TextSection("three、user责任", isHeading = true),
    TextSection("""您agree: 

• notuse本appintoRow任何违法违规活动. 
• notutilize本appAuto化Feature骚扰他人or破badIts他app/service. 
• 妥善保管您 API Keyand相关configInfo. 
• correctthrough本appexecutionAllAction承担责任. """),

    TextSection("four、免责声明", isHeading = true),
    TextSection("""• 本app按\u201C现状\u201D提供, not作任何明示or暗示保证. 
• AI 生成contentpossiblynotaccuratelyorContainsError, please您自Row甄别. 
• 本appAuto化Actionpossibly产生非预期result, pleaseinuseFront充minute解相关Feature. 
• because第three方 AI servicechange、interruptorTerminate导致FeaturenotAvailable, 我们not承担责任. 
• becauseuserconfignotwhen导致DataloseorSafeIssue, 我们not承担责任. """),

    TextSection("five、知识产权", isHeading = true),
    TextSection("本appcode基于开源Project开发, follow相should开源Protocol. appinside UI Design、IconandIts他原创content知识产权归开发者All. "),

    TextSection("six、Protocolchange", isHeading = true),
    TextSection("我们保留anytimeModify本Protocol权利. ModifybackProtocolwillinappinside公布. Continueuse本appthat isTable示您agreeModifybackProtocol. "),

    TextSection("seven、联系方式", isHeading = true),
    TextSection("if您correct本ProtocolHas任何疑问, pleasethroughbynext方式联系我们: \n\n邮箱: xiaomochn@gmail.com\nGitHub: https://github.com/SelectXn00b/androidforClaw"),
)
