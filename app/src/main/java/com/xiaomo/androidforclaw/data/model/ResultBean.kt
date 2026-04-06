/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.data.model

import android.graphics.Bitmap
import com.xiaomo.androidforclaw.accessibility.service.ViewNode

data class resultBean(
    val action: String? = null,
    val preImage: String? = null,
    val afterImage: String? = null
)

data class Checkresult(
    val lastScreenshot: Bitmap?,
    val newScreenshot: Bitmap,
    val lastPerceptionInfos: List<ViewNode>,
    val newPerceptionInfos: List<ViewNode>,
    val lastKeyboardActive: Boolean,
    val newKeyboardActive: Boolean,
    val summary: String,
    val action: String,
    // reflect评分YesNo为A
    val isA: Boolean
)
