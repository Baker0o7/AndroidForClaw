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
    val title = if (type == LegalActivity.TYPE_PRIVACY) "Privacy Policy" else "Terms of Service"
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
    TextSection("forClaw Privacy Policy", isHeading = true),
    TextSection("Last Updated: March 22, 2025"),

    TextSection("1. Overview", isHeading = true),
    TextSection("forClaw (hereinafter referred to as \"this app\") is an AI agent runtime tool that runs on Android devices. We place great importance on your privacy protection. This privacy policy aims to help you understand how we collect, use, and protect your information."),

    TextSection("2. Information Collection and Use", isHeading = true),
    TextSection("""During the operation of this app, the following data may be involved:

1. AI Conversation Data: Your conversations with the AI assistant are stored locally on your device only and will not be uploaded to our servers. Conversation content will be sent to your configured third-party AI service providers (such as OpenAI, Anthropic, etc.) to obtain AI responses.

2. Device Information: To provide accessibility assistance features, this app may read screen content and app information. This data is processed locally on the device only and will not be uploaded.

3. Network Communication: This app requires network connectivity to call AI API services. Network requests contain only the conversation content you proactively send and necessary API authentication information.

4. File Access: This app may read/write configuration files and session records in device storage. All files are stored in the app's dedicated directory or your authorized directory."""),

    TextSection("3. Permission Explanation", isHeading = true),
    TextSection("""This app requests the following permissions and their uses:

• Accessibility Service: Used to assist in operating the phone interface and executing AI agent automated tasks.
• Floating Window: Used to display the AI session floating window for convenient interaction with other apps while using AI.
• Screen Recording/Screenshot: Used to obtain screen content to help AI understand the current frontend interface status.
• Network Access: Used for calling AI API services and message channels (Feishu, Discord, etc.).
• Storage Access: Used to read/write configuration files, session records, and workspace files.
• Notification Listener: Used to read and manage device notifications to support notification-related automated tasks.
• Install Apps: Used for the app's internal auto-update feature."""),

    TextSection("4. Third-Party Services", isHeading = true),
    TextSection("""This app may send data to the following third-party services:

1. AI Service Providers: Including but not limited to OpenAI, Anthropic, Google, etc., used to process your AI conversation requests. The specific service used depends on your configuration.

2. Message Channels: Including Feishu, Discord, Telegram, Slack, etc., used only when you actively enable and configure the relevant channels.

Please note that third-party service data processing is subject to their respective privacy policies."""),

    TextSection("5. Data Storage and Security", isHeading = true),
    TextSection("""• All user data is stored locally on your device.
• Sensitive information such as API keys is stored using Android's EncryptedSharedPreferences encryption.
• This app does not set up independent servers and does not collect or store any user data to the cloud.
• Network transmission uses HTTPS encryption (when connecting to AI APIs)."""),

    TextSection("6. User Rights", isHeading = true),
    TextSection("""You may at any time:
• View and modify your configuration information in the app settings.
• Clear app data by deleting all local storage conversation records and configurations.
• Uninstall the app to completely delete all related data.
• In system settings, revoke any permissions granted to this app."""),

    TextSection("7. Children's Privacy", isHeading = true),
    TextSection("This app is not directed toward children under 13 years of age. We do not knowingly collect personal information from children under 13."),

    TextSection("8. Privacy Policy Updates", isHeading = true),
    TextSection("We may update this privacy policy from time to time. Updated privacy policies will be announced within the app. Continued use of this app signifies your agreement to the updated privacy policy."),

    TextSection("9. Contact Us", isHeading = true),
    TextSection("If you have any questions about this privacy policy, please contact us through the following means:\n\nEmail: xiaomochn@gmail.com\nGitHub: https://github.com/SelectXn00b/androidforClaw"),
)

private fun termsOfserviceText(): List<TextSection> = listOf(
    TextSection("forClaw Terms of Service", isHeading = true),
    TextSection("Last Updated: March 22, 2025"),

    TextSection("1. Service Description", isHeading = true),
    TextSection("forClaw is an AI agent runtime tool that runs on Android devices. This app provides you with AI conversation, automated actions, and multi-channel message access features. Using this app signifies your agreement to comply with this Terms of Service."),

    TextSection("2. Conditions of Use", isHeading = true),
    TextSection("""To use this app, you need:

1. A valid AI service API key (such as OpenAI, Anthropic, etc.). This app itself does not provide AI services; it only acts as a client tool.

2. Ensure that your use of AI services complies with the relevant service provider's terms of use.

3. You bear sole responsibility for all AI interaction results generated through the use of this app."""),

    TextSection("3. User Responsibilities", isHeading = true),
    TextSection("""You agree to:

• Not use this app for any illegal or unlawful activities.
• Not utilize this app's automated features to harass others or damage other apps/services.
• Properly safeguard your API key and related configuration information.
• Assume responsibility for all actions executed through this app."""),

    TextSection("4. Disclaimer", isHeading = true),
    TextSection("""• This app is provided on an \"as is\" basis, without any express or implied warranties.
• AI-generated content may be inaccurate or contain errors; please verify it yourself.
• This app's automated actions may produce unexpected results; please understand the relevant features before use.
• Due to changes, interruptions, or terminations of third-party AI services leading to feature unavailability, we assume no liability.
• Due to user configuration errors leading to data loss or security issues, we assume no liability."""),

    TextSection("5. Intellectual Property", isHeading = true),
    TextSection("This app's code is based on open-source projects and follows relevant open-source protocols. The app's UI design, icons, and other original content intellectual property belong to the developers."),

    TextSection("6. Terms Modification", isHeading = true),
    TextSection("We reserve the right to modify this Terms of Service at any time. Modified terms will be announced within the app. Continued use of this app signifies your agreement to the modified terms."),

    TextSection("7. Contact Information", isHeading = true),
    TextSection("If you have any questions about this Terms of Service, please contact us through the following means:\n\nEmail: xiaomochn@gmail.com\nGitHub: https://github.com/SelectXn00b/androidforClaw"),
)
