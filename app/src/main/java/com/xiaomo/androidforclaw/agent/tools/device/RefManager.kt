package com.xiaomo.androidforclaw.agent.tools.device

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: ref ID management for Playwright-aligned device tool.
 * Maps accessibility tree nodes to stable ref IDs (e1, e2, ...) and resolves
 * refs back to screen coordinates for action execution.
 */

import android.graphics.Rect
import android.util.Log

data class RefNode(
    val ref: String,
    val role: String,        // Button, Input, Text, List, Image, etc.
    val text: String?,       // Visible text or content description
    val bounds: Rect,        // Screen bounds
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focusable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val depth: Int = 0,
    val className: String? = null,
    val packageName: String? = null
)

class RefManager {
    companion object {
        private const val TAG = "RefManager"
    }

    private val refMap = mutableMapOf<String, RefNode>()
    private var lastSnapshotTime = 0L

    fun updateRefs(nodes: List<RefNode>) {
        refMap.clear()
        nodes.forEach { refMap[it.ref] = it }
        lastSnapshotTime = System.currentTimeMillis()
        Log.d(TAG, "Updated ${nodes.size} refs")
    }

    fun resolveRef(ref: String): Pair<Int, Int>? {
        val node = refMap[ref] ?: return null
        return Pair(node.bounds.centerX(), node.bounds.centerY())
    }

    fun getRefNode(ref: String): RefNode? = refMap[ref]

    fun isStale(maxAgeMs: Long = 10_000): Boolean {
        return System.currentTimeMillis() - lastSnapshotTime > maxAgeMs
    }

    fun getRefCount(): Int = refMap.size

    fun clear() {
        refMap.clear()
        lastSnapshotTime = 0
    }
}
