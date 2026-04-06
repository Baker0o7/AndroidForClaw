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
 * TextInput工具
 * 优先throughcut板pasteInputText, AccessibilitynotAvailablehour兜底to ClawIME Key盘
 *
 * 工作原理:
 * 1. user先用 tap() clickInput field, 让ItsobtainFocus
 * 2. 优先Path: cut板Write → Accessibility ACTION_PASTE
 * 3. 兜底Path: ClawIME Input method commitText
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
                clipboardOk -> " [OK] cut板InputalreadyReady"
                imeOk -> " [OK] ClawIME Key盘alreadyReady(cut板notAvailable)"
                else -> " [WARN] **notAvailable** - needopenAccessibilityserviceor ClawIME Input method"
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
                        "text" to Propertyschema("string", "needInputTextcontent"),
                        "action" to Propertyschema(
                            "string",
                            "Action type: 'input'(InputText,Default) | 'send'(Inputbacksend) | 'clear'(清NullInput field)",
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
                "Input unavailable. pleaseopenAccessibilityservice(recommend, Supportcut板paste), orswitchto ClawIME Input method"
            )
        }

        return try {
            when (action) {
                "clear" -> {
                    val success = if (imeOk) {
                        ClawIMEmanager.clearText()
                    } else {
                        // Accessibility方式清Null: 选中All → Delete
                        false
                    }
                    if (success) {
                        kotlinx.coroutines.delay(100)
                        skillresult.success("already清NullInput field")
                    } else {
                        skillresult.error("清NullInput fieldFailed")
                    }
                }
                "send" -> {
                    // InputText
                    val (inputSuccess, method) = inputTextwithFallback(text!!, clipboardOk, imeOk)
                    if (!inputSuccess) {
                        return skillresult.error("InputTextFailed")
                    }
                    kotlinx.coroutines.delay(500)

                    // send: 优先 ClawIME  sendMessage, 兜底return车Key
                    val sendSuccess = if (imeOk) {
                        ClawIMEmanager.sendMessage()
                    } else {
                        // through shell sendreturn车Key
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 66")).waitfor() == 0
                    }
                    if (!sendSuccess) {
                        return skillresult.error("sendMessageFailed")
                    }
                    kotlinx.coroutines.delay(1000)

                    skillresult.success(
                        "alreadyInputConcurrency送: $text (${text.length} chars, via $method)",
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
                        "alreadyInput: $text (${text.length} chars, via $method)",
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
            skillresult.error("InputFailed: ${e.message}")
        }
    }

    /**
     * 优先cut板, 兜底 ClawIME
     * @return Pair(whetherSuccess, useMethod名)
     */
    private fun inputTextwithFallback(text: String, clipboardOk: Boolean, imeOk: Boolean): Pair<Boolean, String> {
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
