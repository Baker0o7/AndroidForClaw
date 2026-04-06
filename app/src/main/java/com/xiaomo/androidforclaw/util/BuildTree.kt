/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaomo.androidforclaw.accessibility.service.ViewNode

object BuildTree {
    /**
     * Multi-way tree node definition
     */
    private data class TreeNode(
        val viewNode: ViewNode,
        val children: MutableList<TreeNode> = mutableListOf()
    )

    /**
     * Get node properties: coordinates, class name, resource id, text, content description
     */
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

    /**
     * Extract node type (such as button, textView)
     */
    private fun getTreeDisplayType(viewNode: ViewNode): String {
        return viewNode.className?.substringafterLast('.') ?: "View"
    }

    /**
     * append node state info: checked, selected, progress
     */
    private fun appendStateInfo(builder: StringBuilder, node: ViewNode, nodeTypeLabel: String) {
        val accessibilityNode = node.node ?: return
        try {
            val lowerLabel = nodeTypeLabel.lowercase()
            when {
                lowerLabel == "switch" || lowerLabel == "checkbox" -> {
                    builder.append(", checked:${accessibilityNode.isChecked}")
                }
                lowerLabel == "button" || lowerLabel == "text" || lowerLabel == "textview" -> {
                    if (accessibilityNode.isSelected) {
                        builder.append(", selected:true")
                    }
                }
                lowerLabel == "progress" || lowerLabel == "progressbar" -> {
                    accessibilityNode.rangeInfo?.let {
                        builder.append(", progress:${it.current}/${it.max}")
                    }
                }
            }
        } catch (_: exception) {
            // Ignore exception, don't affect main flow
        }
    }

    /**
     * Node formatted output: indentation, type, text, description, coordinates, clickable status, state info
     */
    private fun formatTreeNodeLine(node: ViewNode, depth: Int): String {
        val builder = StringBuilder()
        val indent = "  ".repeat(depth)
        val nodeType = getTreeDisplayType(node)
        builder.append(indent).append("- [").append(nodeType).append("] ")
        
        // if text and contentDesc are the same, only output contentDesc
        val text = node.text?.trim()
        val contentDesc = node.contentDesc?.trim()
        val isSame = !text.isNullorEmpty() && !contentDesc.isNullorEmpty() && text == contentDesc
        
        if (!isSame && !text.isNullorEmpty()) {
            builder.append("text=\"${node.text}\" ")
        }
        if (!contentDesc.isNullorEmpty()) {
            builder.append("contentDesc=\"${node.contentDesc}\" ")
        }
        
        builder.append("Center (${node.point.x}, ${node.point.y}), l: ${node.left}, r: ${node.right}, t: ${node.top}, b: ${node.bottom}; ")
        builder.append("[clickable:${node.clickable}")
        appendStateInfo(builder, node, nodeType)
        builder.append("]\n")
        return builder.toString()
    }
    /**
     * Filter system status bar invalid info
     */
    private fun isSystemStatusBar(node: ViewNode): Boolean {
        if (node.top >= 100) return false

        val contentDesc = node.contentDesc?.lowercase() ?: ""
        if (SYSTEM_STATUS_KEYWORDS.any { contentDesc.contains(it) }) {
            return true
        }

        return node.text?.matches(Regex("\\d{1,2}:\\d{2}")) == true && node.contentDesc.isNullorEmpty()
    }

    private val SYSTEM_STATUS_KEYWORDS = listOf(
        "android system notification",
        "system notification",
        "notification",
        "wlan",
        "signal",
        "charging",
        "sim card",
        "ringer",
        "vibrate",
        "nfc"
    )

    /**
     * BuildTree main flow, core main function
     */
    fun buildComponentTreeDescription(nodes: List<ViewNode>): String {
        // Filter out system status bar
        val filteredNodes = nodes.filter { !isSystemStatusBar(it) }
        if (filteredNodes.isEmpty()) {
            return "(No available data)\n"
        }
        /**
         * nodeorder: Record node's sequential index in original list
         * treeNodeMap: Record ViewNode to TreeNode mapping
         * nodeKeyMap: Store node unique identifier to ViewNode mapping
         */
        val nodeorder = filteredNodes.withIndex().associate { it.value to it.index }
        val treeNodeMap = mutableMapOf<ViewNode, TreeNode>()
        val nodeKeyMap = mutableMapOf<String, ViewNode>()

        /**
         * Create TreeNode object for each filtered node
         * Generate node unique identifier via getNodeKey and establish mapping
         */
        filteredNodes.forEach { viewNode ->
            treeNodeMap[viewNode] = TreeNode(viewNode)
            getNodeKey(viewNode.node)?.let { key ->
                nodeKeyMap[key] = viewNode
            }
        }
        /**
         * Traverse all tree nodes to establish parent-child relationship, nodes without parent become root nodes
         */
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
        /**
         * Node sort rule: original sequential index -> vertical position -> horizontal position
         */
        val comparator = compareBy<TreeNode> { nodeorder[it.viewNode] ?: Int.MAX_VALUE }
            .thenBy { it.viewNode.top }
            .thenBy { it.viewNode.left }

        /**
         * Tree traverse output
         */
        val rootsToProcess = if (rootNodes.isnotEmpty()) rootNodes.distinct() else treeNodeMap.values.distinct()
        val builder = StringBuilder()
        rootsToProcess.sortedwith(comparator).forEach { appendTreeNode(builder, it, comparator) }
        /**
         * Result return
         */
        if (builder.isEmpty()) {
            builder.append("(No available data)\n")
        }
        return builder.toString()
    }

    /**
     * Recursive output tree structure
     * Step 1: Fold redundant chains
     * Step 2: Skip null leaf containers
     * Step 3: format current node
     * Step 4: Filter button duplicate child nodes
     * Step 5: Recursive process child nodes (depth + 1)
     */
    private fun appendTreeNode(builder: StringBuilder, treeNode: TreeNode, comparator: Comparator<TreeNode>, depth: Int = 0) {
        val effectiveNode = collapseRedundantChain(treeNode)
        if (shouldSkipLeafContainer(effectiveNode.viewNode, effectiveNode.children)) {
            return
        }
        builder.append(formatTreeNodeLine(effectiveNode.viewNode, depth))
        val remainingChildren = effectiveNode.children.filternot {
            shouldBypassbuttonChild(effectiveNode.viewNode, it.viewNode)
        }
        remainingChildren.distinct().sortedwith(comparator).forEach {
            appendTreeNode(builder, it, comparator, depth + 1)
        }
    }

    /**
     * Redundant chain folding: when parent node only has one child node, and they are equivalent or parent is a null node, skip mile layer and show only meaningful node
     */
    private fun collapseRedundantChain(node: TreeNode): TreeNode {
        var current = node
        while (true) {
            val singleChild = current.children.singleorNull() ?: break
            val isCurrentbutton = current.viewNode.className?.lowercase()?.contains("button") == true
            if (isCurrentbutton && shouldBypassbuttonChild(current.viewNode, singleChild.viewNode)) {
                break
            }
            if (areNodesEquivalent(current.viewNode, singleChild.viewNode) ||
                shouldBypassContainer(current.viewNode, singleChild.viewNode)
            ) {
                current = singleChild
                continue
            }
            break
        }
        return if (current === node) node else TreeNode(current.viewNode, current.children)
    }

    /**
     * Check if two nodes are equivalent, used for chain folding deduplication
     */
    private fun areNodesEquivalent(first: ViewNode, second: ViewNode): Boolean {
        return first.className == second.className &&
                first.left == second.left &&
                first.right == second.right &&
                first.top == second.top &&
                first.bottom == second.bottom &&
                first.clickable == second.clickable &&
                first.text == second.text &&
                first.contentDesc == second.contentDesc
    }

    /**
     * Skip null container class (null layout, ViewGroup, etc.)
     */
    private fun shouldBypassContainer(container: ViewNode, child: ViewNode): Boolean {
        val isStructural = isStructuralClass(container.className)
        if (!isStructural) return false
        val containerHasContent = !container.text.isNullorEmpty() || !container.contentDesc.isNullorEmpty()
        val childHasContent = !child.text.isNullorEmpty() || !child.contentDesc.isNullorEmpty()
        val childIsStructural = isStructuralClass(child.className)
        return !containerHasContent && (childHasContent || childIsStructural)
    }

    /**
     * Check if it's a structural class
     */
    private fun isStructuralClass(className: String?): Boolean {
        val lower = className?.lowercase() ?: return false
        return lower.contains("layout") ||
                lower.contains("viewgroup") ||
                lower.contains("frame")
    }

    /**
     * Remove textView under button (expressing same meaning), simplify prompt
     */
    private fun shouldBypassbuttonChild(parent: ViewNode, child: ViewNode): Boolean {
        val parentClass = parent.className?.lowercase() ?: return false
        if (!parentClass.contains("button")) return false

        val childClass = child.className?.lowercase() ?: return false
        if (!childClass.contains("textview") && !childClass.contains("text")) return false

        val parentLabel = (parent.text ?: parent.contentDesc)?.trim() ?: return false
        val childLabel = (child.text ?: child.contentDesc)?.trim() ?: return false

        return parentLabel == childLabel
    }

    /**
     * Check if should skip null leaf node
     */
    private fun shouldSkipLeafContainer(node: ViewNode, children: List<TreeNode>): Boolean {
        if (children.isnotEmpty()) return false
        val isStructural = isStructuralClass(node.className)
        val hasContent = !node.text.isNullorEmpty() || !node.contentDesc.isNullorEmpty()
        return isStructural && !hasContent
    }

    /**
     * Filter nodes outside screen, only keep nodes inside screen
     */
    fun isNodewithinScreen(
        node: ViewNode,
        screenWidth: Int,
        screenHeight: Int,
        tolerance: Int = 20
    ): Boolean {
        if (node.left >= node.right || node.top >= node.bottom) return false
        if (screenWidth > 0 && (node.right < -tolerance || node.left > screenWidth + tolerance)) return false
        if (screenHeight > 0 && (node.bottom < -tolerance || node.top > screenHeight + tolerance)) return false
        return true
    }

    // buildTreefromImageDetail() has been deleted
    // ImageDetail is old architecture class (deleted), no longer used
    // new architecture directly uses buildComponentTreeDescription(nodes: List<ViewNode>)
}