/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ext

import android.widget.TextView
import com.xiaomo.androidforclaw.core.MyApplication
import com.tencent.mmkv.MMKV
import io.noties.markwon.Markwon


val markwon by lazy {
    Markwon.create(MyApplication.application) // Ensure你Has Application Instance
}


fun TextView.setMarkdownText(content: String) {
    markwon.setMarkdown(this, content)
}

fun String.removeMarkdownMark(): String {
    // Checkwhetherby "```markdown" 开头
    if (startswith("```markdown")) {
        // remove头part "```markdown" and尾part "```"
        // note: thisinFalse设尾part只Hasone "```" and它position于String末尾
        val startIndex = "```markdown".length
        val endIndex = lastIndexOf("```")

        // Check endIndex whetherValid, by避免 IndexOutOfBoundsexception
        if (endIndex != -1 && endIndex > startIndex) {
            // Returnremove头partand尾partmarkbackString
            return substring(startIndex, endIndex)
        } else {
            // ifNone找to尾partmarkor尾partmarkposition置not正确, thenpossiblyillustrateStringformatHas误
            // thisincanReturnoriginalStringor抛出oneexception, depend onYourRequirement
            // thisin我们chooseReturnoriginalString并附带oneWarningInfo(实际开发中shoulduseLog而notYes打印)
            println("Warning: No valid closing '```' found in the instruction string.")
            return this // or你canchoose抛出exception
        }
    } else {
        // ifnotby "```markdown" 开头, then直接ReturnoriginalString(oraccording toRequirementProcess)
        return this // or你canchoose抛出oneexception, Table示Inputformatnot正确
    }
}

fun androidforClawMMKV(): MMKV = MMKV.defaultMMKV(MMKV.MULTI_PROCESS_MODE, "androidforClaw")!!

val mmkv by lazy { androidforClawMMKV() }


