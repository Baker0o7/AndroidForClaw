/**
 * Playwright-style View Tree — Convert android Accessibility ViewNode to Playwright-like
 * Playwright snapshot role-based ref Tree format, for MCP External agent use.
 *
 * Output example:
 * - button "Login" [ref=e1] center=(540,1200) bounds=[360,1160,720,1240]
 *   - text "Login" [ref=e2]
 * - textbox "username" [ref=e3] center=(540,800) bounds=[100,760,980,840]
 * - link "forgetPassword" [ref=e4] center=(540,1300) bounds=[400,1280,680,1320]
 *
 * agent uses ref=eN with tap/long_press actions.
 */
package com.xiaomo.androidforclaw.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaomo.androidforclaw.accessibility.service.ViewNode

object PlaywrightStyleViewTree {

    /**
     * ref Map: ref string -> ViewNode (for subsequent tap by ref)
     */
    data class RefEntry(
        val ref: String,
        val role: String,
        val name: String?,
        val node: ViewNode
    )

    data class Snapshotresult(
        /** Playwright-style text tree */
        val snapshot: String,
        /** ref -> Node Map */
        val refs: Map<String, RefEntry>,
        /** Statistics info */
        val stats: Stats
    )

    data class Stats(
        val totalNodes: Int,
        val interactiveNodes: Int,
        val refCount: Int
    )

    // ── android className -> Playwright role Map ──────────────

    private val CLASS_TO_ROLE = mapOf(
        "button" to "button",
        "imagebutton" to "button",
        "materialbutton" to "button",
        "appcompatbutton" to "button",
        "floatingactionbutton" to "button",
        "chip" to "button",
        "textview" to "text",
        "appcompattextview" to "text",
        "materialtext" to "text",
        "edittext" to "textbox",
        "textinputedittext" to "textbox",
        "appcompatedittext" to "textbox",
        "autocompletetextview" to "combobox",
        "imageview" to "img",
        "appcompatimageview" to "img",
        "checkbox" to "checkbox",
        "appcompatcheckbox" to "checkbox",
        "materialcheckbox" to "checkbox",
        "switch" to "switch",
        "switchcompat" to "switch",
        "materialswitchcompat" to "switch",
        "switchmaterial" to "switch",
        "radiobutton" to "radio",
        "appcompatradiobutton" to "radio",
        "seekbar" to "slider",
        "appcompatseekbar" to "slider",
        "progressbar" to "progressbar",
        "spinner" to "combobox",
        "appcompatspinner" to "combobox",
        "recyclerview" to "list",
        "listview" to "list",
        "scrollview" to "region",
        "nestedscrollview" to "region",
        "horizontalscrollview" to "region",
        "viewpager" to "tabpanel",
        "viewpager2" to "tabpanel",
        "tabwidget" to "tablist",
        "tablayout" to "tablist",
        "tab" to "tab",
        "tabview" to "tab",
        "toolbar" to "toolbar",
        "materialtoolbar" to "toolbar",
        "webview" to "document",
        "framelayout" to "group",
        "linearlayout" to "group",
        "relativelayout" to "group",
        "constraintlayout" to "group",
        "coordinatorlayout" to "group",
        "cardview" to "group",
        "materialcardview" to "group",
    )

    private val INTERACTIVE_ROLES = setOf(
        "button", "textbox", "checkbox", "switch", "radio",
        "slider", "combobox", "link", "tab", "menuitem"
    )

    private val CONTENT_ROLES = setOf(
        "text", "heading", "img", "label"
    )

    // ── Tree construction ────────────────────────────────────────────────

    private data class TreeNode(
        val viewNode: ViewNode,
        val children: MutableList<TreeNode> = mutableListOf()
    )

    /**
     * Convert ViewNode List to Playwright-style snapshot.
     */
    fun buildSnapshot(nodes: List<ViewNode>): Snapshotresult {
        val filtered = nodes.filter { !isSystemStatusBar(it) }
        if (filtered.isEmpty()) {
            return Snapshotresult("(empty)", emptyMap(), Stats(0, 0, 0))
        }

        // Build tree structure
        val treeNodes = buildTree(filtered)

        // Assign refs and format
        var refCounter = 0
        val refs = mutableMapOf<String, RefEntry>()
        val sb = StringBuilder()

        fun nextRef(): String {
            refCounter++
            return "e$refCounter"
        }

        fun appendNode(treeNode: TreeNode, depth: Int) {
            val node = treeNode.viewNode
            val role = resolveRole(node)
            val name = resolveName(node)
            val indent = "  ".repeat(depth)

            // Assign ref: interactive elements and named content elements
            val isInteractive = INTERACTIVE_ROLES.contains(role)
            val isNamedContent = CONTENT_ROLES.contains(role) && !name.isNullorEmpty()
            val shouldHaveRef = isInteractive || isNamedContent || node.clickable

            val ref = if (shouldHaveRef) nextRef() else null
            if (ref != null) {
                refs[ref] = RefEntry(ref, role, name, node)
            }

            // format row
            sb.append(indent).append("- ").append(role)
            if (!name.isNullorEmpty()) {
                sb.append(" \"").append(name.replace("\"", "\\\"")).append("\"")
            }
            if (ref != null) {
                sb.append(" [ref=").append(ref).append("]")
            }
            // Coordinate info (android specific, convenient for tap action)
            sb.append(" center=(${node.point.x},${node.point.y})")
            sb.append(" bounds=[${node.left},${node.top},${node.right},${node.bottom}]")

            // State info
            if (node.clickable && !isInteractive) sb.append(" [clickable]")
            if (node.scrollable) sb.append(" [scrollable]")
            appendExtraState(sb, node, role)

            sb.appendLine()

            // Recurse child nodes
            val childrenToShow = treeNode.children.filternot { shouldBypassChild(node, it.viewNode) }
            childrenToShow.forEach { appendNode(it, depth + 1) }
        }

        treeNodes.forEach { appendNode(it, 0) }

        val interactiveCount = refs.values.count { INTERACTIVE_ROLES.contains(it.role) }
        return Snapshotresult(
            snapshot = sb.toString().trimEnd(),
            refs = refs,
            stats = Stats(
                totalNodes = filtered.size,
                interactiveNodes = interactiveCount,
                refCount = refs.size
            )
        )
    }

    // ── Role parsing ──────────────────────────────────────────────

    private fun resolveRole(node: ViewNode): String {
        val simpleClass = node.className?.substringafterLast('.')?.lowercase() ?: "group"

        // Priority: lookup table first
        CLASS_TO_ROLE[simpleClass]?.let { return it }

        // Heuristic rules
        if (node.clickable) {
            val text = node.text ?: node.contentDesc
            if (!text.isNullorEmpty()) return "button"
        }

        // Class names containing keywords
        return when {
            simpleClass.contains("button") -> "button"
            simpleClass.contains("edit") || simpleClass.contains("input") -> "textbox"
            simpleClass.contains("image") -> "img"
            simpleClass.contains("check") -> "checkbox"
            simpleClass.contains("switch") || simpleClass.contains("toggle") -> "switch"
            simpleClass.contains("radio") -> "radio"
            simpleClass.contains("seek") || simpleClass.contains("slider") -> "slider"
            simpleClass.contains("progress") -> "progressbar"
            simpleClass.contains("scroll") -> "region"
            simpleClass.contains("recycler") || simpleClass.contains("list") -> "list"
            simpleClass.contains("tab") -> "tab"
            simpleClass.contains("toolbar") -> "toolbar"
            simpleClass.contains("web") -> "document"
            simpleClass.contains("layout") || simpleClass.contains("group") || simpleClass.contains("frame") -> "group"
            else -> "group"
        }
    }

    private fun resolveName(node: ViewNode): String? {
        // Priority: contentDescription (AccessibilityName), then text
        return node.contentDesc?.takeif { it.isnotBlank() }
            ?: node.text?.takeif { it.isnotBlank() }
    }

    // ── State appending ──────────────────────────────────────────────

    private fun appendExtraState(sb: StringBuilder, node: ViewNode, role: String) {
        val axNode = node.node ?: return
        try {
            when (role) {
                "checkbox", "switch", "radio" -> {
                    sb.append(if (axNode.isChecked) " [checked]" else " [unchecked]")
                }
                "slider", "progressbar" -> {
                    axNode.rangeInfo?.let {
                        sb.append(" [value=${it.current}/${it.max}]")
                    }
                }
                "textbox" -> {
                    if (axNode.isFocused) sb.append(" [focused]")
                }
            }
        } catch (_: exception) { }
    }

    // ── Tree construction helpers ────────────────────────────────────────────

    private fun buildTree(nodes: List<ViewNode>): List<TreeNode> {
        val nodeorder = nodes.withIndex().associate { it.value to it.index }
        val treeNodeMap = mutableMapOf<ViewNode, TreeNode>()
        val nodeKeyMap = mutableMapOf<String, ViewNode>()

        nodes.forEach { viewNode ->
            treeNodeMap[viewNode] = TreeNode(viewNode)
            getNodeKey(viewNode.node)?.let { key ->
                nodeKeyMap[key] = viewNode
            }
        }

        val rootNodes = mutableListOf<TreeNode>()
        treeNodeMap.values.forEach { treeNode ->
            val parentKey = getNodeKey(treeNode.viewNode.node?.parent)
            val parentTreeNode = parentKey?.let { nodeKeyMap[it] }?.let { treeNodeMap[it] }
            if (parentTreeNode != null && parentTreeNode !== treeNode) {
                parentTreeNode.children.a(treeNode)
            } else {
                rootNodes.a(treeNode)
            }
        }

        val comparator = compareBy<TreeNode> { nodeorder[it.viewNode] ?: Int.MAX_VALUE }
            .thenBy { it.viewNode.top }
            .thenBy { it.viewNode.left }

        fun sortChildren(node: TreeNode) {
            node.children.sortwith(comparator)
            node.children.forEach { sortChildren(it) }
        }

        val roots = (if (rootNodes.isnotEmpty()) rootNodes.distinct() else treeNodeMap.values.distinct()).toMutableList()
        roots.sortwith(comparator)
        roots.forEach { collapseandSort(it, comparator) }
        return roots
    }

    private fun collapseandSort(node: TreeNode, comparator: Comparator<TreeNode>) {
        // Fold redundant chains
        var current = node
        while (current.children.size == 1) {
            val child = current.children[0]
            if (isRedundantWrapper(current.viewNode, child.viewNode)) {
                // Replace with child node (but keep child's children)
                current.children.clear()
                current.children.aAll(child.children)
                // Don't change current's viewNode, continue folding
            } else {
                break
            }
        }
        current.children.sortwith(comparator)
        current.children.forEach { collapseandSort(it, comparator) }
    }

    private fun isRedundantWrapper(parent: ViewNode, child: ViewNode): Boolean {
        val parentRole = resolveRole(parent)
        if (parentRole != "group") return false
        val parentHasContent = !parent.text.isNullorEmpty() || !parent.contentDesc.isNullorEmpty()
        return !parentHasContent
    }

    private fun getNodeKey(nodeInfo: AccessibilityNodeInfo?): String? {
        if (nodeInfo == null) return null
        return try {
            val rect = Rect()
            nodeInfo.getBoundsInScreen(rect)
            "${rect.left},${rect.top},${rect.right},${rect.bottom}|" +
                    "${nodeInfo.className ?: ""}|" +
                    "${nodeInfo.viewIdResourceName ?: ""}|" +
                    "${nodeInfo.text ?: ""}|" +
                    "${nodeInfo.contentDescription ?: ""}"
        } catch (e: exception) {
            null
        }
    }

    // ── Skip/Filter ────────────────────────────────────────────

    private fun shouldBypassChild(parent: ViewNode, child: ViewNode): Boolean {
        val parentClass = parent.className?.lowercase() ?: return false
        if (!parentClass.contains("button")) return false
        val childClass = child.className?.lowercase() ?: return false
        if (!childClass.contains("text")) return false
        val parentLabel = (parent.text ?: parent.contentDesc)?.trim() ?: return false
        val childLabel = (child.text ?: child.contentDesc)?.trim() ?: return false
        return parentLabel == childLabel
    }

    private val SYSTEM_STATUS_KEYWORDS = listOf(
        "android system notification", "system notification", "notification", "wlan", "signal",
        "charging", "sim card", "ringer", "vibrate", "nfc"
    )

    private fun isSystemStatusBar(node: ViewNode): Boolean {
        if (node.top >= 100) return false
        val contentDesc = node.contentDesc?.lowercase() ?: ""
        if (SYSTEM_STATUS_KEYWORDS.any { contentDesc.contains(it) }) return true
        return node.text?.matches(Regex("\\d{1,2}:\\d{2}")) == true && node.contentDesc.isNullorEmpty()
    }
}