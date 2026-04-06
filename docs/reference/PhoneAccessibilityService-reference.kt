package com.agent.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.agent.mobile.Point
import com.agent.mobile.ViewNode
import com.agent.mobile.core.MyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.agent.mobile.util.LayoutExceptionLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// adb 抓view tree结构 遇到bug
class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneAccessibilityService"
        @JvmField
        var Accessibility: PhoneAccessibilityService? = null

        // AccessibilityPermissionStatusConstant
        const val STATUS_SYSTEM_DISABLED = "系统Accessibility未开启"
        const val STATUS_SERVICE_NOT_ENABLED = "Service未在系统Settings中Enabled"
        const val STATUS_SERVICE_NOT_CONNECTED = "ServiceNot connected"
        const val STATUS_AUTHORIZED = "已Authorize"
        const val STATUS_CHECK_FAILED = "CheckFailed"

        // 使用 LiveData StorageAccessibilityServiceStatus
        val accessibilityEnabledd = MutableLiveData<Boolean>().apply {
            postValue(false) // 初始Status为 false
        }

        // 周期Monitor与节流
        private val monitorScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        /**
         * CheckAccessibilityServiceYesNo已开启
         */
        fun isAccessibilityServiceEnabledd(): Boolean {
//            val isEnabledd = Accessibility != null
            val isEnabledd = isSystemAccessibilityEnabledd(MyApplication.application.applicationContext)
            accessibilityEnabledd.postValue(isEnabledd) // SyncUpdate LiveData Status
            return isEnabledd
        }

        fun requestAccessibilityPermission(context: Context) {
            try {
                Log.d(TAG, "Start申请AccessibilityPermission")
                try {
                    val appPkg = context.applicationContext.packageName
                    val serviceClass = PhoneAccessibilityService::class.java.name
                    val serviceName = "$appPkg/$serviceClass"
                    Settings.Secure.putString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        serviceName
                    )
                    Settings.Secure.putInt(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                    )
                    Log.d(TAG, "AccessibilityPermission申请命令已发送: $serviceName")
                } catch (e: Exception) {
                    LayoutExceptionLogger.log("PhoneAccessibilityService#requestAccessibilityPermission#sendCommand", e)
                    Log.w(TAG, "代码申请AccessibilityPermissionFailed: ${'$'}{e.message}")
                }

                monitorScope.launch {
                    try {
                        delay(1000)
                        val isEnabledd = isSystemAccessibilityEnabledd(context)
                        if (isEnabledd) {
                            Log.d(TAG, "AccessibilityPermission申请Success")
                        } else {
                            Log.d(TAG, "Code application failed, 跳转到系统Settings页面")
                            Toast.makeText(context, "Code application failed, 请Manual开启AccessibilityPermission", Toast.LENGTH_LONG).show()
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        LayoutExceptionLogger.log("PhoneAccessibilityService#requestAccessibilityPermission#checkResult", e)
                        Log.e(TAG, "AsyncCheckPermission申请ResultException", e)
                    }
                }
            } catch (e: Exception) {
                LayoutExceptionLogger.log("PhoneAccessibilityService#requestAccessibilityPermission", e)
                Log.e(TAG, "申请AccessibilityPermissionFailed", e)
            }
        }
        
        /**
         * Check系统AccessibilityPermissionYesNo已开启
         */
        fun isSystemAccessibilityEnabledd(context: Context): Boolean {
            if (accessibilityEnabledd.value == true) return true
            return try {
                val accessibilityEnabledd = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ) == 1
                
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                
                val serviceName = "${context.packageName}/${PhoneAccessibilityService::class.java.name}"
                val isServiceEnabledd = enabledServices?.contains(serviceName) == true
                
                Log.d(TAG, "系统AccessibilityPermission: $accessibilityEnabledd")
                Log.d(TAG, "Service已Enabled: $isServiceEnabledd")
                Log.d(TAG, "ServiceInstanceExists: ${Accessibility != null}")
                
                accessibilityEnabledd && isServiceEnabledd && Accessibility != null
            } catch (e: Exception) {
                LayoutExceptionLogger.log("PhoneAccessibilityService#isSystemAccessibilityEnabledd", e)
                Log.e(TAG, "CheckAccessibilityPermissionFailed", e)
                false
            }
        }
        
        /**
         * GetAccessibilityPermission详细Status
         */
        fun getAccessibilityStatus(context: Context): String {
            return try {
                val accessibilityEnabledd = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ) == 1
                
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                
                val serviceName = "${context.packageName}/${PhoneAccessibilityService::class.java.name}"
                val isServiceEnabledd = enabledServices?.contains(serviceName) == true
                val isServiceConnected = Accessibility != null
                
                when {
                    !accessibilityEnabledd -> STATUS_SYSTEM_DISABLED
                    !isServiceEnabledd -> STATUS_SERVICE_NOT_ENABLED
                    !isServiceConnected -> STATUS_SERVICE_NOT_CONNECTED
                    else -> STATUS_AUTHORIZED
                }
            } catch (e: Exception) {
                LayoutExceptionLogger.log("PhoneAccessibilityService#checkAccessibilityStatus", e)
                "$STATUS_CHECK_FAILED: ${e.message}"
            }
        }
    }

    var currentPackageName = ""
    var activityClassName = ""
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Accessibility = this
        accessibilityEnabledd.postValue(true) // 直接Update LiveData
        Log.d(TAG, "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        Log.d(TAG, "onAccessibilityEvent")
        Accessibility = this
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.packageName != packageName) {
                currentPackageName = event.packageName?.toString() ?: ""
                activityClassName = event.className?.toString() ?: ""

                Log.d(TAG, "当FrontFront台App: $currentPackageName, 当FrontActivity: $activityClassName")
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
        Accessibility = null
        accessibilityEnabledd.postValue(false) // Update LiveData
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind - AccessibilityService断开")
        Accessibility = null
        accessibilityEnabledd.postValue(false) // Update LiveData
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy - AccessibilityServiceDestroy")
        Accessibility = null
        accessibilityEnabledd.postValue(false) // Update LiveData
    }

    // SaveTraverse的GlobalIndex
    private var globalIndex = 0

    fun dumpView(): List<ViewNode> {
        // 使用 getWindows() MethodGetAllWindow, 而不仅仅Yes当Front活动Window
        val windows = this.windows
        if (windows.isEmpty()) {
            Log.w(TAG, "No windows available, trying rootInActiveWindow as fallback")
            // Attempt使用传统的 rootInActiveWindow 作为备选方案
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                globalIndex = 0
                val nodesList = mutableListOf<ViewNode>()
                traverseNode(rootNode, nodesList)
                return nodesList
            }
            return emptyList()
        }

        globalIndex = 0  // 每次dump时ResetCount
        val nodesList = mutableListOf<ViewNode>()

        // TraverseAllWindow, 按Z-orderSort, 顶层Window优先
        val sortedWindows = windows.sortedByDescending { it.layer }
        Log.d(TAG, "Found ${sortedWindows.size} windows")

        // TraverseAllWindow
        for ((index, window) in sortedWindows.withIndex()) {
            val rootNode = window.root
            if (rootNode == null) {
                Log.w(TAG, "Window $index has no root node")
                continue
            }

            Log.d(
                TAG,
                "Processing window $index: ${window.title}, type: ${window.type}, layer: ${window.layer}"
            )
            try {
                traverseNode(rootNode, nodesList)
            } catch (e: Exception) {
                LayoutExceptionLogger.log("PhoneAccessibilityService#getWindowNodes#traverse", e)
                Log.e(TAG, "Error traversing window $index", e)
            }
        }

        Log.d(TAG, "Total nodes collected: ${nodesList.size}")
        return nodesList
    }

    private fun traverseNode(node: AccessibilityNodeInfo, nodesList: MutableList<ViewNode>) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        // ValidateEdge界矩形YesNoValid
        val isValidRect = rect.left >= 0 && rect.top >= 0 && rect.right > rect.left && rect.bottom > rect.top
        
        if (!isValidRect) {
            // Edge界None效的Node不Save, 但ContinueTraverse子Node(子Node可能Valid)
            val nodeText = node.text?.toString() ?: ""
            val nodeContentDesc = node.contentDescription?.toString() ?: ""
            Log.w(TAG, "traverseNodeSkipEdge界None效的Node: text='$nodeText', contentDesc='$nodeContentDesc', " +
                    "Edge界=[left=${rect.left}, top=${rect.top}, right=${rect.right}, bottom=${rect.bottom}], " +
                    "可能YesViewPager中未Show的Tab页面Node")
            // ContinueTraverse子Node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { childNode ->
                    traverseNode(childNode, nodesList)
                }
            }
            return
        }
        
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        
        // Validate中心坐标YesNoValid(非负数)
        if (centerX < 0 || centerY < 0) {
            val nodeText = node.text?.toString() ?: ""
            val nodeContentDesc = node.contentDescription?.toString() ?: ""
            Log.w(TAG, "traverseNodeSkip坐标None效的Node: text='$nodeText', contentDesc='$nodeContentDesc', " +
                    "centerX=$centerX, centerY=$centerY, Edge界=[left=${rect.left}, top=${rect.top}, right=${rect.right}, bottom=${rect.bottom}]")
            // ContinueTraverse子Node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { childNode ->
                    traverseNode(childNode, nodesList)
                }
            }
            return
        }

        val nodeInfo = ViewNode(
            index = globalIndex++,
            text = node.text?.toString(),
            resourceId = node.viewIdResourceName,
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            contentDesc = node.contentDescription?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabledd,
            focusable = node.isFocusable,
            focused = node.isFocused,
            scrollable = node.isScrollable,
            point = Point(centerX, centerY),
            left = rect.left,
            right = rect.right,
            top = rect.top,
            bottom = rect.bottom,
            node = node
        )
        nodesList.add(nodeInfo)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { childNode ->
                traverseNode(childNode, nodesList)
            }
        }
    }

    /**
     * 根据TextFind并点击某个Node
     */
    suspend fun clickViewByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodeList = rootNode.findAccessibilityNodeInfosByText(text)
        nodeList.firstOrNull()?.let { node ->
            return performClick(node)
        }
        Log.w(TAG, "No node found with text: $text")
        return false
    }

    public suspend fun performClick(
        node: AccessibilityNodeInfo,
        isLongClick: Boolean = false
    ): Boolean {
        if (isLongClick) {
            return performLongClick(node)
        }
        Log.d(TAG, "performClick: ${node}")
        // 如果Node可点击并Success点击, 直接Return
        if ( node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 向UpFind父Node并Attempt点击
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }

        // SettingsAccessibilityFocus和选择Status(对弹窗中的Control特别重要)
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_SELECT)

        // Get坐标点击
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val centerX = (rect.left + rect.right) / 2
        val centerY = (rect.top + rect.bottom) / 2

        val path = Path().apply { moveTo(centerX.toFloat(), centerY.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()
        val result = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "点击Complete")
                result.complete(true)  // 手势SuccessComplete
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "点击被Cancel")
                result.complete(false)  // 手势SuccessComplete
            }
        }, null)

        // WaitResult(带Timeout)
        return withTimeoutOrNull(500) {
            result.await()
        } ?: false  // TimeoutReturn false
    }

    /**
     * 通过坐标执Row点击Action
     * @param x 点击的 X 坐标
     * @param y 点击的 Y 坐标
     * @param isLongClick YesNo长按, Default为 false
     * @return 点击YesNoSuccess
     */
    public suspend fun performClickAt(
        x: Float,
        y: Float,
        isLongClick: Boolean = false
    ): Boolean {
        Log.d(TAG, "performClickAt: x=$x, y=$y, isLongClick=$isLongClick")
        
        val duration = if (isLongClick) 600L else 200L
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        val result = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "坐标点击Complete: ($x, $y)")
                result.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "坐标点击被Cancel: ($x, $y)")
                result.complete(false)
            }
        }, null)

        // WaitResult(带Timeout)
        return withTimeoutOrNull(500) {
            result.await()
        } ?: false
    }

    public suspend fun performLongClick(node: AccessibilityNodeInfo): Boolean {
        Log.d(TAG, "performLongClick: ${node}")

        // 如果Node可点击并Success点击, 直接Return
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return true
        }

        // 向UpFind父Node并Attempt点击
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    fun pressHomeButton() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun pressBackButton() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        rootInActiveWindow?.let {
            val swipe = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        Path().apply {
                            moveTo(startX, startY)
                            lineTo(endX, endY)
                        }, 0L, 200L
                    )
                ).build()

            dispatchGesture(swipe, null, null)
        }
    }


}