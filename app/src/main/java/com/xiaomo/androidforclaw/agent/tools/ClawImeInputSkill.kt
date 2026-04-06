package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */


import android.content.Context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition
import com.xiaomo.androidforclaw.service.ClawIMEManager
import com.xiaomo.androidforclaw.service.ClipboardInputHelper

/**
 * TextInput工具
 * 优先通过cut板pasteInputText, Accessibility不Available时兜底到 ClawIME Key盘
 *
 * 工作原理:
 * 1. User先用 tap() clickInput field, 让ItsobtainFocus
 * 2. 优先Path: cut板Write → Accessibility ACTION_PASTE
 * 3. 兜底Path: ClawIME Input method commitText
 */
class ClawImeInputSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ClawImeInputSkill"
    }

    override val name = "claw_ime_input"
    override val description: String
        get() {
            val clipboardOk = ClipboardInputHelper.isPasteAvailable() && ClipboardInputHelper.isClipboardAvailable(context)
            val imeOk = ClawIMEManager.isClawImeEnableddd(context) && ClawIMEManager.isConnected()
            val statusNote = when {
                clipboardOk -> " ✅ cut板Input已Ready"
                imeOk -> " ✅ ClawIME Key盘已Ready(cut板不Available)"
                else -> " ⚠️ **不Available** - Need开启AccessibilityService或 ClawIME Input method"
            }
            return "Input text via clipboard paste (preferred) or ClawIME keyboard (fallback)$statusNote"
        }

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "text" to PropertySchema("string", "要Input的TextInside容"),
                        "action" to PropertySchema(
                            "string",
                            "Action type: 'input'(InputText,Default) | 'send'(InputBacksend) | 'clear'(清NullInput field)",
                            enum = listOf("input", "send", "clear")
                        )
                    ),
                    required = listOf("text")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        val text = args["text"] as? String
        val action = args["action"] as? String ?: "input"

        if (text == null && action != "clear") {
            return Skillresult.error("Missing required parameter: text")
        }

        val clipboardOk = ClipboardInputHelper.isPasteAvailable() && ClipboardInputHelper.isClipboardAvailable(context)
        val imeOk = ClawIMEManager.isClawImeEnableddd(context) && ClawIMEManager.isConnected()

        if (!clipboardOk && !imeOk) {
            return Skillresult.error(
                "Input unavailable. 请开启AccessibilityService(recommend, Supportcut板paste), 或switch到 ClawIME Input method"
            )
        }

        return try {
            when (action) {
                "clear" -> {
                    val success = if (imeOk) {
                        ClawIMEManager.clearText()
                    } else {
                        // Accessibility方式清Null: 选中All → Delete
                        false
                    }
                    if (success) {
                        kotlinx.coroutines.delay(100)
                        Skillresult.success("已清NullInput field")
                    } else {
                        Skillresult.error("清NullInput fieldFailed")
                    }
                }
                "send" -> {
                    // InputText
                    val (inputSuccess, method) = inputTextWithFallback(text!!, clipboardOk, imeOk)
                    if (!inputSuccess) {
                        return Skillresult.error("InputTextFailed")
                    }
                    kotlinx.coroutines.delay(500)

                    // send: 优先 ClawIME 的 sendMessage, 兜底回车Key
                    val sendSuccess = if (imeOk) {
                        ClawIMEManager.sendMessage()
                    } else {
                        // 通过 shell send回车Key
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 66")).waitFor() == 0
                    }
                    if (!sendSuccess) {
                        return Skillresult.error("sendMessageFailed")
                    }
                    kotlinx.coroutines.delay(1000)

                    Skillresult.success(
                        "已InputConcurrency送: $text (${text.length} chars, via $method)",
                        mapOf(
                            "text" to text,
                            "length" to text.length,
                            "action" to "send",
                            "method" to method
                        )
                    )
                }
                else -> {
                    val (success, method) = inputTextWithFallback(text!!, clipboardOk, imeOk)
                    if (!success) {
                        return Skillresult.error("InputTextFailed")
                    }

                    val waitTime = (100L + (text.length * 5L).coerceAtMost(300L)).coerceAtLeast(500L)
                    kotlinx.coroutines.delay(waitTime)

                    Skillresult.success(
                        "已Input: $text (${text.length} chars, via $method)",
                        mapOf(
                            "text" to text,
                            "length" to text.length,
                            "method" to method,
                            "wait_time_ms" to waitTime
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Input failed", e)
            Skillresult.error("InputFailed: ${e.message}")
        }
    }

    /**
     * 优先cut板, 兜底 ClawIME
     * @return Pair(YesNoSuccess, use的Method名)
     */
    private fun inputTextWithFallback(text: String, clipboardOk: Boolean, imeOk: Boolean): Pair<Boolean, String> {
        if (clipboardOk) {
            val success = ClipboardInputHelper.inputTextViaClipboard(context, text)
            if (success) return Pair(true, "clipboard")
            Log.w(TAG, "Clipboard input failed, trying ClawIME fallback")
        }
        if (imeOk) {
            val success = ClawIMEManager.inputText(text)
            if (success) return Pair(true, "clawime")
        }
        return Pair(false, "none")
    }
}
