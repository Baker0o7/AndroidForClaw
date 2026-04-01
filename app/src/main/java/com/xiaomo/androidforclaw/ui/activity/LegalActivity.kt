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
    val title = if (type == LegalActivity.TYPE_PRIVACY) "Privacy Policy" else "Terms of Service"
    val content = if (type == LegalActivity.TYPE_PRIVACY) privacyPolicyText() else termsOfServiceText()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
    TextSection("ForClaw Privacy Policy", isHeading = true),
    TextSection("Last updated: March 22, 2025"),

    TextSection("1. Overview", isHeading = true),
    TextSection("ForClaw (hereinafter \"the App\") is an AI Agent runtime tool that runs on Android devices. We attach great importance to your privacy protection. This privacy policy aims to help you understand how we collect, use, and protect your information."),

    TextSection("2. Information Collection and Use", isHeading = true),
    TextSection("""The following data may be involved during the App's operation:

1. AI Conversation Data: Your conversations with the AI assistant are stored only on your device locally and are not uploaded to our servers. Conversation content is sent to your configured third-party AI service providers (such as OpenAI, Anthropic, etc.) to obtain AI responses.

2. Device Information: To provide accessibility features, the App may read screen content and application information. This data is processed only locally on your device and is not uploaded.

3. Network Communication: The App requires network connectivity to call AI API services. Network requests contain only conversation content you actively send and necessary API authentication information.

4. File Access: The App may read and write configuration files and session records in device storage. All files are stored in the App's private directory or directories you have authorized."""),

    TextSection("3. Permissions", isHeading = true),
    TextSection("""The App requests the following permissions and their purposes:

• Accessibility Service: Used to assist with phone interface operations and execute AI Agent automation tasks.
• Overlay Window: Used to display AI session floating windows for convenient interaction with the AI while using other apps.
• Screen Recording/Screenshot: Used to capture screen content to help the AI understand the current interface state.
• Network Access: Used to call AI API services and messaging channels (Feishu, Discord, etc.).
• Storage Access: Used to read and write configuration files, session records, and workspace files.
• Notification Listener: Used to read and manage device notifications to support notification-related automation tasks.
• Install Applications: Used for in-app automatic update functionality."""),

    TextSection("4. Third-Party Services", isHeading = true),
    TextSection("""The App may send data to the following third-party services:

1. AI Service Providers: Including but not limited to OpenAI, Anthropic, Google, etc., used to process your AI conversation requests. Which service is used depends on your configuration.

2. Messaging Channels: Including Feishu, Discord, Telegram, Slack, etc., used only after you actively enable and configure the relevant channels.

Please note that data processing by third-party services is subject to their respective privacy policies."""),

    TextSection("5. Data Storage and Security", isHeading = true),
    TextSection("""• All user data is stored locally on your device.
• Sensitive information such as API keys is encrypted using Android EncryptedSharedPreferences.
• The App does not maintain independent servers and does not collect or store any user data in the cloud.
• Network transmission uses HTTPS encryption (when connecting to AI APIs)."""),

    TextSection("6. User Rights", isHeading = true),
    TextSection("""You can at any time:
• View and modify your configuration information in the App settings.
• Clear App data to delete all locally stored conversation records and configuration.
• Uninstall the App to completely delete all related data.
• Revoke any of the App's permissions in system settings."""),

    TextSection("7. Children's Privacy", isHeading = true),
    TextSection("This App is not intended for children under 13 years of age. We do not knowingly collect personal information from children under 13."),

    TextSection("8. Privacy Policy Updates", isHeading = true),
    TextSection("We may update this privacy policy from time to time. The updated privacy policy will be published within the App. Continued use of the App indicates your agreement to the updated privacy policy."),

    TextSection("9. Contact Us", isHeading = true),
    TextSection("If you have any questions about this privacy policy, please contact us at:\n\nEmail: xiaomochn@gmail.com\nGitHub: https://github.com/SelectXn00b/AndroidForClaw"),
)

private fun termsOfServiceText(): List<TextSection> = listOf(
    TextSection("ForClaw Terms of Service", isHeading = true),
    TextSection("Last updated: March 22, 2025"),

    TextSection("1. Service Description", isHeading = true),
    TextSection("ForClaw is an AI Agent runtime tool that runs on Android devices. This App provides you with features such as AI conversation, automation operations, and multi-channel messaging integration. By using this App, you agree to comply with this agreement."),

    TextSection("2. Conditions of Use", isHeading = true),
    TextSection("""To use this App, you need to:

1. Have a valid AI service API key (such as OpenAI, Anthropic, etc.). This App does not provide AI services itself and only serves as a client tool.

2. Ensure that your use of AI services complies with the terms of service of the respective service providers.

3. Take responsibility for all AI interaction results generated through the use of this App."""),

    TextSection("3. User Responsibilities", isHeading = true),
    TextSection("""You agree to:

• Not use this App for any illegal activities.
• Not use the App's automation features to harass others or disrupt other applications/services.
• Safeguard your API keys and related configuration information.
• Take responsibility for all operations performed through this App."""),

    TextSection("4. Disclaimer", isHeading = true),
    TextSection("""• This App is provided \"as is\" without any express or implied warranties.
• AI-generated content may be inaccurate or contain errors; please verify it yourself.
• The App's automation operations may produce unexpected results; please fully understand the relevant features before use.
• We are not responsible for unavailability caused by changes, interruptions, or termination of third-party AI services.
• We are not responsible for data loss or security issues caused by improper user configuration."""),

    TextSection("5. Intellectual Property", isHeading = true),
    TextSection("The App's code is developed based on open-source projects and follows the respective open-source licenses. The intellectual property of UI designs, icons, and other original content within the App belongs to the developers."),

    TextSection("6. Agreement Changes", isHeading = true),
    TextSection("We reserve the right to modify this agreement at any time. The modified agreement will be published within the App. Continued use of the App indicates your agreement to the modified agreement."),

    TextSection("7. Contact Us", isHeading = true),
    TextSection("If you have any questions about this agreement, please contact us at:\n\nEmail: xiaomochn@gmail.com\nGitHub: https://github.com/SelectXn00b/AndroidForClaw"),
)
