package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import com.xiaomo.androidforclaw.service.ClawIMEmanager
import com.xiaomo.androidforclaw.service.ClipboardInputhelper

/**
 * Text Input tool
 * Prefer clipboard paste input, fallback to ClawIME keyboard when Accessibility not available
 *
 * How it works:
 * 1. User first uses tap() to click input field, let it obtain focus
 * 2. Preferred path: Clipboard write → Accessibility ACTION_PASTE
 * 3. Fallback path: ClawIME Input method commitText
 */
class ClawImeInputskill(private val context: context) : skill {
    companion object {
        private const val TAG = "ClawImeInputskill"
    }

    override val name = "claw_ime_input"
    override val description: String
        get() {
            val clipboardOk = ClipboardInputhelper.isPasteAvailable() && ClipboardInputhelper.isClipboardAvailable(context)
            val imeOk = ClawIMEmanager.isClawImeEnabled(context) && ClawIMEmanager.isConnected()
            val statusnote = when {
                clipboardOk -> " [OK] Clipboard input ready"
                imeOk -> " [OK] ClawIME keyboard ready (clipboard not available)"
                else -> " [WARN] **Not available** - need to open Accessibility service or ClawIME Input method"
            }
            return "Input text via clipboard paste (preferred) or ClawIME keyboard (fallback)$statusnote"
        }

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "text" to Propertyschema("string", "Text content to input"),
                        "action" to Propertyschema(
                            "string",
                            "Action type: 'input' (input text, default) | 'send' (input and send) | 'clear' (clear input field)",
                            enum = listOf("input", "send", "clear")
                        )
                    ),
                    required = listOf("text")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val text = args["text"] as? String
        val action = args["action"] as? String ?: "input"

        if (text == null && action != "clear") {
            return skillresult.error("Missing required parameter: text")
        }

        val clipboardOk = ClipboardInputhelper.isPasteAvailable() && ClipboardInputhelper.isClipboardAvailable(context)
        val imeOk = ClawIMEmanager.isClawImeEnabled(context) && ClawIMEmanager.isConnected()

        if (!clipboardOk && !imeOk) {
            return skillresult.error(
                "Input unavailable. Please open Accessibility service (recommended, supports clipboard paste), or switch to ClawIME Input method"
            )
        }

        return try {
            when (action) {
                "clear" -> {
                    val success = if (imeOk) {
                        ClawIMEmanager.clearText()
                    } else {
                        // Accessibility way to clear: Select All → Delete
                        false
                    }
                    if (success) {
                        kotlinx.coroutines.delay(100)
                        skillresult.success("Input field cleared")
                    } else {
                        skillresult.error("Clear input field failed")
                    }
                }
                "send" -> {
                    // Input text
                    val (inputSuccess, method) = inputTextWithFallback(text!!, clipboardOk, imeOk)
                    if (!inputSuccess) {
                        return skillresult.error("Input text failed")
                    }
                    kotlinx.coroutines.delay(500)

                    // send: Prefer ClawIME sendMessage, fallback to return key
                    val sendSuccess = if (imeOk) {
                        ClawIMEmanager.sendMessage()
                    } else {
                        // Send return key via shell
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 66")).waitFor() == 0
                    }
                    if (!sendSuccess) {
                        return skillresult.error("Send message failed")
                    }
                    kotlinx.coroutines.delay(1000)

                    skillresult.success(
                        "Input and sent: $text (${text.length} chars, via $method)",
                        mapOf(
                            "text" to text,
                            "length" to text.length,
                            "action" to "send",
                            "method" to method
                        )
                    )
                }
                else -> {
                    val (success, method) = inputTextwithFallback(text!!, clipboardOk, imeOk)
                    if (!success) {
                        return skillresult.error("InputTextFailed")
                    }

                    val waitTime = (100L + (text.length * 5L).coerceAtMost(300L)).coerceAtLeast(500L)
                    kotlinx.coroutines.delay(waitTime)

                    skillresult.success(
                        "Input: $text (${text.length} chars, via $method)",
                        mapOf(
                            "text" to text,
                            "length" to text.length,
                            "method" to method,
                            "wait_time_ms" to waitTime
                        )
                    )
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "Input failed", e)
            skillresult.error("Input failed: ${e.message}")
        }
    }

    /**
     * Prefer clipboard, fallback to ClawIME
     * @return Pair(whetherSuccess, methodName)
     */
    private fun inputTextWithFallback(text: String, clipboardOk: Boolean, imeOk: Boolean): Pair<Boolean, String> {
        if (clipboardOk) {
            val success = ClipboardInputhelper.inputTextViaClipboard(context, text)
            if (success) return Pair(true, "clipboard")
            Log.w(TAG, "Clipboard input failed, trying ClawIME fallback")
        }
        if (imeOk) {
            val success = ClawIMEmanager.inputText(text)
            if (success) return Pair(true, "clawime")
        }
        return Pair(false, "none")
    }
}
