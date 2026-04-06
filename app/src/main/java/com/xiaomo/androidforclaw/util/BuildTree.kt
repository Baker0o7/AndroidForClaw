/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaomo.androidforclaw.accessibility.service.ViewNode

object BuildTree {
    /**
     * 多叉TreeNode定义
     */
    private data class TreeNode(
        val viewNode: ViewNode,
        val children: MutableList<TreeNode> = mutableListOf()
    )

    /**
     * GetNode的相关Property: 坐标, Class名, Resourceid, Text, Inside容Description
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
        } catch (e: Exception) {
            null
        }
    }

    /**
     * NodeType提取(such asbutton, textView)
     */
    private fun getTreeDisplayType(viewNode: ViewNode): String {
        return viewNode.className?.substringAfterLast('.') ?: "View"
    }

    /**
     *  追加Node的StatusInfo: checked、selected、progress
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
        } catch (_: Exception) {
            // IgnoreException, 不影响主流程
        }
    }

    /**
     * NodeFormatted output: 缩Into、Type、Text、Description、坐标、可clickStatus、StatusInfo
     */
    private fun formatTreeNodeLine(node: ViewNode, depth: Int): String {
        val builder = StringBuilder()
        val indent = "  ".repeat(depth)
        val nodeType = getTreeDisplayType(node)
        builder.append(indent).append("- [").append(nodeType).append("] ")
        
        // if text 和 contentDesc Inside容相同, 只Output contentDesc
        val text = node.text?.trim()
        val contentDesc = node.contentDesc?.trim()
        val isSame = !text.isNullOrEmpty() && !contentDesc.isNullOrEmpty() && text == contentDesc
        
        if (!isSame && !text.isNullOrEmpty()) {
            builder.append("text=\"${node.text}\" ")
        }
        if (!contentDesc.isNullOrEmpty()) {
            builder.append("contentDesc=\"${node.contentDesc}\" ")
        }
        
        builder.append("Center (${node.point.x}, ${node.point.y}), l: ${node.left}, r: ${node.right}, t: ${node.top}, b: ${node.bottom}; ")
        builder.append("[clickable:${node.clickable}")
        appendStateInfo(builder, node, nodeType)
        builder.append("]\n")
        return builder.toString()
    }
    /**
     * Filter系统Status栏的None效Info
     */
    private fun isSystemStatusBar(node: ViewNode): Boolean {
        if (node.top >= 100) return false

        val contentDesc = node.contentDesc?.lowercase() ?: ""
        if (SYSTEM_STATUS_KEYWORDS.any { contentDesc.contains(it) }) {
            return true
        }

        return node.text?.matches(Regex("\\d{1,2}:\\d{2}")) == true && node.contentDesc.isNullOrEmpty()
    }

    private val SYSTEM_STATUS_KEYWORDS = listOf(
        "android 系统Notification",
        "系统Notification",
        "Notification",
        "wlan",
        "信号",
        "充电",
        "sim 卡",
        "振铃器",
        "振动",
        "nfc"
    )

    /**
     * BuildTree的主流程, 核心主Function
     */
    fun buildComponentTreeDescription(nodes: List<ViewNode>): String {
        //Filter掉系统Status栏
        val filteredNodes = nodes.filter { !isSystemStatusBar(it) }
        if (filteredNodes.isEmpty()) {
            return "(NoneAvailableData)\n"
        }
        /**
         * nodeOrder: RecordNode在原List中的SequentialIndex
         * treeNodeMap: RecordViewNode到TreeNode的Map关系
         * nodeKeyMap: StorageNodeUnique标识到 ViewNode 的Map
         */
        val nodeOrder = filteredNodes.withIndex().associate { it.value to it.index }
        val treeNodeMap = mutableMapOf<ViewNode, TreeNode>()
        val nodeKeyMap = mutableMapOf<String, ViewNode>()

        /**
         * 为EachFilterBack的NodeCreate对应的 TreeNode Object
         * 通过 getNodeKey 生成NodeUnique标识并建立Map
         */
        filteredNodes.forEach { viewNode ->
            treeNodeMap[viewNode] = TreeNode(viewNode)
            getNodeKey(viewNode.node)?.let { key ->
                nodeKeyMap[key] = viewNode
            }
        }
        /**
         * TraverseAllTreeNode建立父子关系, None父Node的Node作为根Node
         */
        val rootNodes = mutableListOf<TreeNode>()
        treeNodeMap.values.forEach { treeNode ->
            val parentKey = getNodeKey(treeNode.viewNode.node?.parent)
            val parentTreeNode = parentKey?.let { nodeKeyMap[it] }?.let { treeNodeMap[it] }
            if (parentTreeNode != null && parentTreeNode !== treeNode) {
                parentTreeNode.children.add(treeNode)
            } else {
                rootNodes.add(treeNode)
            }
        }
        /**
         * NodeSortRule: 原始SequentialIndex -》垂直位置 -》水平位置
         */
        val comparator = compareBy<TreeNode> { nodeOrder[it.viewNode] ?: Int.MAX_VALUE }
            .thenBy { it.viewNode.top }
            .thenBy { it.viewNode.left }

        /**
         * TreeTraverseOutput
         */
        val rootsToProcess = if (rootNodes.isNotEmpty()) rootNodes.distinct() else treeNodeMap.values.distinct()
        val builder = StringBuilder()
        rootsToProcess.sortedWith(comparator).forEach { appendTreeNode(builder, it, comparator) }
        /**
         * resultReturn
         */
        if (builder.isEmpty()) {
            builder.append("(NoneAvailableData)\n")
        }
        return builder.toString()
    }

    /**
     * 用于RecurseOutputTree结构
     * 步骤1: 折叠冗余链
     * 步骤2: SkipNull叶子Container
     * 步骤3: Format当FrontNode
     * 步骤4: Filter按钮Duplicate子Node
     * 步骤5: RecurseProcess子Node(depth + 1)
     */
    private fun appendTreeNode(builder: StringBuilder, treeNode: TreeNode, comparator: Comparator<TreeNode>, depth: Int = 0) {
        val effectiveNode = collapseRedundantChain(treeNode)
        if (shouldSkipLeafContainer(effectiveNode.viewNode, effectiveNode.children)) {
            return
        }
        builder.append(formatTreeNodeLine(effectiveNode.viewNode, depth))
        val remainingChildren = effectiveNode.children.filterNot {
            shouldBypassButtonChild(effectiveNode.viewNode, it.viewNode)
        }
        remainingChildren.distinct().sortedWith(comparator).forEach {
            appendTreeNode(builder, it, comparator, depth + 1)
        }
    }

    /**
     * 冗余链折叠: 当父Node只Has一个子Node, 且二者等价或父Node为NullNode时, 则Skip中间层只ShowHas意义Node
     */
    private fun collapseRedundantChain(node: TreeNode): TreeNode {
        var current = node
        while (true) {
            val singleChild = current.children.singleOrNull() ?: break
            val isCurrentButton = current.viewNode.className?.lowercase()?.contains("button") == true
            if (isCurrentButton && shouldBypassButtonChild(current.viewNode, singleChild.viewNode)) {
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
     * Check两NodeYesNo等价, 用于折叠链去重
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
     * SkipNull的ContainerClass(Null的layout, ViewGroup等)
     */
    private fun shouldBypassContainer(container: ViewNode, child: ViewNode): Boolean {
        val isStructural = isStructuralClass(container.className)
        if (!isStructural) return false
        val containerHasContent = !container.text.isNullOrEmpty() || !container.contentDesc.isNullOrEmpty()
        val childHasContent = !child.text.isNullOrEmpty() || !child.contentDesc.isNullOrEmpty()
        val childIsStructural = isStructuralClass(child.className)
        return !containerHasContent && (childHasContent || childIsStructural)
    }

    /**
     * CheckYesNo为结构Class
     */
    private fun isStructuralClass(className: String?): Boolean {
        val lower = className?.lowercase() ?: return false
        return lower.contains("layout") ||
                lower.contains("viewgroup") ||
                lower.contains("frame")
    }

    /**
     * 去除buttonDown的textView(Table达含义相同), 简化prompt
     */
    private fun shouldBypassButtonChild(parent: ViewNode, child: ViewNode): Boolean {
        val parentClass = parent.className?.lowercase() ?: return false
        if (!parentClass.contains("button")) return false

        val childClass = child.className?.lowercase() ?: return false
        if (!childClass.contains("textview") && !childClass.contains("text")) return false

        val parentLabel = (parent.text ?: parent.contentDesc)?.trim() ?: return false
        val childLabel = (child.text ?: child.contentDesc)?.trim() ?: return false

        return parentLabel == childLabel
    }

    /**
     * CheckYesNoSkip为Null的叶子Node
     */
    private fun shouldSkipLeafContainer(node: ViewNode, children: List<TreeNode>): Boolean {
        if (children.isNotEmpty()) return false
        val isStructural = isStructuralClass(node.className)
        val hasContent = !node.text.isNullOrEmpty() || !node.contentDesc.isNullOrEmpty()
        return isStructural && !hasContent
    }

    /**
     * FilterGet到的ScreenOutsideNode, 仅保留ScreenInside的Node
     */
    fun isNodeWithinScreen(
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

    // buildTreeFromImageDetail() 已Delete
    // ImageDetail YesOld架构的Class(已Delete), No longer used
    // New架构直接use buildComponentTreeDescription(nodes: List<ViewNode>)
}