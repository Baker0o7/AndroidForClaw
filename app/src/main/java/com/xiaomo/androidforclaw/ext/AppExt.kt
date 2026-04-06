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
    Markwon.create(MyApplication.application) // Ensure you have an Application instance
}


fun TextView.setMarkdownText(content: String) {
    markwon.setMarkdown(this, content)
}

fun String.removeMarkdownMark(): String {
    // Check if string starts with "```markdown"
    if (startswith("```markdown")) {
        // Remove leading "```markdown" and trailing "```"
        // Note: This assumes the trailing part has only one "```" at the end of the string
        val startIndex = "```markdown".length
        val endIndex = lastIndexOf("```")

        // Check if endIndex is valid to avoid IndexOutOfBoundsException
        if (endIndex != -1 && endIndex > startIndex) {
            // Return string with leading and trailing markers removed
            return substring(startIndex, endIndex)
        } else {
            // If no valid closing marker found or position is incorrect, the string format may be wrong
            // This can return original string or throw an exception depending on your requirements
            // Here we choose to return original string with a warning (in production should use Log)
            println("Warning: No valid closing '```' found in the instruction string.")
            return this
        }
    } else {
        // If not starting with "```markdown", return original string (or process according to requirements)
        return this
    }
}

fun androidforClawMMKV(): MMKV = MMKV.defaultMMKV(MMKV.MULTI_PROCESS_MODE, "androidforClaw")!!

val mmkv by lazy { androidforClawMMKV() }


