/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.ext

import android.widget.TextView
import com.xiaomo.androidforclaw.core.MyApplication
import com.tencent.mmkv.MMKV
import io.noties.markwon.Markwon


val markwon by lazy {
    Markwon.create(MyApplication.application) // Ensure你Has Application 的Instance
}


fun TextView.setMarkdownText(content: String) {
    markwon.setMarkdown(this, content)
}

fun String.removeMarkdownMark(): String {
    // CheckYesNo以 "```markdown" 开头
    if (startsWith("```markdown")) {
        // 移除头部的 "```markdown" 和尾部的 "```"
        // Note: 这里False设尾部只Has一个 "```" and它位于String的末尾
        val startIndex = "```markdown".length
        val endIndex = lastIndexOf("```")

        // Check endIndex YesNoValid, 以避免 IndexOutOfBoundsException
        if (endIndex != -1 && endIndex > startIndex) {
            // Return移除头部和尾部标记Back的String
            return substring(startIndex, endIndex)
        } else {
            // ifNone找到尾部标记or尾部标记位置不正确, 则possiblyillustrateString格式Has误
            // 这里CanReturn原始Stringor抛出一个Exception, depend onYourRequirement
            // 这里我们chooseReturn原始String并附带一个WarningInfo(实际开发中ShoulduseLog而不Yes打印)
            println("Warning: No valid closing '```' found in the instruction string.")
            return this // or你Canchoose抛出Exception
        }
    } else {
        // if不以 "```markdown" 开头, 则直接Return原始String(oraccording toRequirementProcess)
        return this // or你Canchoose抛出一个Exception, Table示Input格式不正确
    }
}

fun AndroidForClawMMKV(): MMKV = MMKV.defaultMMKV(MMKV.MULTI_PROCESS_MODE, "AndroidForClaw")!!

val mmkv by lazy { AndroidForClawMMKV() }


