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

// adb capturing view tree structure encountered bug
class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneAccessibilityService"
        @JvmField
        var Accessibility: PhoneAccessibilityService? = null

        // AccessibilityPermissionStatusConstant
        const val STATUS_SYSTEM_DISABLED = "System Accessibility not enabled"
        const val STATUS_SERVICE_NOT_ENABLED = "Service not enabled in system Settings"
        const val STATUS_SERVICE_NOT_CONNECTED = "ServiceNot connected"
        const val STATUS_AUTHORIZED = "Authorized"
        const val STATUS_CHECK_FAILED = "CheckFailed"

        // Using LiveData to store AccessibilityServiceStatus
        val accessibilityEnabledd = MutableLiveData<Boolean>().apply {
            postValue(false) // Initial status is false
        }

        // Periodic monitoring and throttling
        private val monitorScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        /**
         * Check if AccessibilityService is enabled or not
         */
        fun isAccessibilityServiceEnabledd(): Boolean {
//            val isEnabledd = Accessibility != null
            val isEnabledd = isSystemAccessibilityEnabledd(MyApplication.application.applicationContext)
            accessibilityEnabledd.postValue(isEnabledd) // Sync update LiveData status
            return isEnabledd
        }

        fun requestAccessibilityPermission(context: Context) {
            try {
                Log.d(TAG, "Start requesting AccessibilityPermission")
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
                    Log.d(TAG, "AccessibilityPermission request command sent: $serviceName")
                } catch (e: Exception) {
                    LayoutExceptionLogger.log("PhoneAccessibilityService#requestAccessibilityPermission#sendCommand", e)
                    Log.w(TAG, "Code request AccessibilityPermissionFailed: ${'$'}{e.message}")
                }

                monitorScope.launch {
                    try {
                        delay(1000)
                        val isEnabledd = isSystemAccessibilityEnabledd(context)
                        if (isEnabledd) {
                            Log.d(TAG, "AccessibilityPermission request Success")
                        } else {
                            Log.d(TAG, "Code application failed, jump to system Settings page")
                            Toast.makeText(context, "Code application failed, please manually enable AccessibilityPermission", Toast.LENGTH_LONG).show()
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        LayoutExceptionLogger.log("PhoneAccessibilityService#requestAccessibilityPermission#checkResult", e)
                        Log.e(TAG, "Async check permission request result exception", e)
                    }
                }
            } catch (e: Exception) {
                LayoutExceptionLogger.log("PhoneAccessibilityService#requestAccessibilityPermission", e)
                Log.e(TAG, "Request AccessibilityPermission failed", e)
            }
        }
        
        /**
         * Check if system AccessibilityPermission is enabled or not
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
                
                Log.d(TAG, "System AccessibilityPermission: $accessibilityEnabledd")
                Log.d(TAG, "Service is enabled: $isServiceEnabledd")
                Log.d(TAG, "Service instance exists: ${Accessibility != null}")
                
                accessibilityEnabledd && isServiceEnabledd && Accessibility != null
            } catch (e: Exception) {
                LayoutExceptionLogger.log("PhoneAccessibilityService#isSystemAccessibilityEnabledd", e)
                Log.e(TAG, "Check AccessibilityPermission failed", e)
                false
            }
        }
        
        /**
         * Get AccessibilityPermission detailed status
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
        accessibilityEnabledd.postValue(true) // Directly update LiveData
        Log.d(TAG, "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        Log.d(TAG, "onAccessibilityEvent")
        Accessibility = this
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.packageName != packageName) {
                currentPackageName = event.packageName?.toString() ?: ""
                activityClassName = event.className?.toString() ?: ""

                Log.d(TAG, "Foreground App: $currentPackageName, Foreground Activity: $activityClassName")
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
        Accessibility = null
        accessibilityEnabledd.postValue(false) // Update LiveData
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind - AccessibilityService disconnected")
        Accessibility = null
        accessibilityEnabledd.postValue(false) // Update LiveData
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy - AccessibilityService destroyed")
        Accessibility = null
        accessibilityEnabledd.postValue(false) // Update LiveData
    }

    // Global index for traverse
    private var globalIndex = 0

    fun dumpView(): List<ViewNode> {
        // Use getWindows() method to get all windows, not just current foreground window
        val windows = this.windows
        if (windows.isEmpty()) {
            Log.w(TAG, "No windows available, trying rootInActiveWindow as fallback")
            // Try using traditional rootInActiveWindow as fallback
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                globalIndex = 0
                val nodesList = mutableListOf<ViewNode>()
                traverseNode(rootNode, nodesList)
                return nodesList
            }
            return emptyList()
        }

        globalIndex = 0  // Reset count on each dump
        val nodesList = mutableListOf<ViewNode>()

        // Traverse all windows, sorted by Z-order, top layer window first
        val sortedWindows = windows.sortedByDescending { it.layer }
        Log.d(TAG, "Found ${sortedWindows.size} windows")

        // Traverse all windows
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
        
        // Validate bounds rectangle is valid or not
        val isValidRect = rect.left >= 0 && rect.top >= 0 && rect.right > rect.left && rect.bottom > rect.top
        
        if (!isValidRect) {
            // Skip invalid nodes but continue traversing child nodes (children may be valid)
            val nodeText = node.text?.toString() ?: ""
            val nodeContentDesc = node.contentDescription?.toString() ?: ""
            Log.w(TAG, "traverseNode skip invalid node: text='$nodeText', contentDesc='$nodeContentDesc', " +
                    "bounds=[left=${rect.left}, top=${rect.top}, right=${rect.right}, bottom=${rect.bottom}], " +
                    "may be ViewPager tab nodes not currently shown")
            // Continue traverse child nodes
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { childNode ->
                    traverseNode(childNode, nodesList)
                }
            }
            return
        }
        
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        
        // Validate center coordinates are valid (non-negative)
        if (centerX < 0 || centerY < 0) {
            val nodeText = node.text?.toString() ?: ""
            val nodeContentDesc = node.contentDescription?.toString() ?: ""
            Log.w(TAG, "traverseNode skip invalid coordinates node: text='$nodeText', contentDesc='$nodeContentDesc', " +
                    "centerX=$centerX, centerY=$centerY, bounds=[left=${rect.left}, top=${rect.top}, right=${rect.right}, bottom=${rect.bottom}]")
            // Continue traverse child nodes
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
     * Find and click a node by text
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
        // If node is clickable and click succeeds, return directly
        if ( node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // Find parent node and try to click
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }

        // Set accessibility focus and selection status (especially important for controls in dialogs)
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_SELECT)

        // Get coordinates to click
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
                Log.d(TAG, "Click completed")
                result.complete(true)  // Gesture completed successfully
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "Click cancelled")
                result.complete(false)  // Gesture completed successfully
            }
        }, null)

        // Wait for result (with timeout)
        return withTimeoutOrNull(500) {
            result.await()
        } ?: false  // Timeout return false
    }

    /**
     * Execute click action by coordinates
     * @param x X coordinate to click
     * @param y Y coordinate to click
     * @param isLongClick Whether to long press, default is false
     * @return Whether click was successful
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
                Log.d(TAG, "Coordinate click completed: ($x, $y)")
                result.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "Coordinate click cancelled: ($x, $y)")
                result.complete(false)
            }
        }, null)

        // Wait for result (with timeout)
        return withTimeoutOrNull(500) {
            result.await()
        } ?: false
    }

    public suspend fun performLongClick(node: AccessibilityNodeInfo): Boolean {
        Log.d(TAG, "performLongClick: ${node}")

        // If node is clickable and click succeeds, return directly
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return true
        }

        // Find parent node and try to click
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