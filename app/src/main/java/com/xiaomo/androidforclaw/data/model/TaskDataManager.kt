/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.data.model

import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Task data manager
 * Responsible for managing TaskData creation, replacement and access
 */
class TaskDatamanager {
    companion object {
        private const val TAG = "TaskDatamanager"

        @Volatile
        private var INSTANCE: TaskDatamanager? = null

        fun getInstance(): TaskDatamanager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TaskDatamanager().also { INSTANCE = it }
            }
        }
    }

    private val _currentTaskData = MutableStateFlow<TaskData?>(null)
    val currentTaskData: StateFlow<TaskData?> = _currentTaskData.asStateFlow()

    /**
     * Start new task, create new TaskData
     */
    fun startnewTask(taskId: String,packageName: String) {
        Log.d(TAG, "StartnewTask: $taskId")
        val newTaskData = TaskData(taskId,packageName)
        _currentTaskData.value = newTaskData
    }

    /**
     * Get current task data
     */
    fun getCurrentTaskData(): TaskData? = _currentTaskData.value

    /**
     * Clear current task data
     */
    fun clearCurrentTask() {
        Log.d(TAG, "清理whenFrontTaskData")
        _currentTaskData.value = null
    }

    /**
     * Check if there is a current task
     */
    fun hasCurrentTask(): Boolean = _currentTaskData.value != null
}
