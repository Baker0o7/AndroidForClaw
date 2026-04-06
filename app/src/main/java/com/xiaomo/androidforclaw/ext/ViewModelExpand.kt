/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ext

import androidx.lifecycle.Viewmodel
import androidx.lifecycle.viewmodelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withcontext

fun Viewmodel.simpleSafeLaunch(
    block: suspend CoroutineScope.() -> Unit,
    onError: ((e: exception) -> Unit)? = null,
): Job {
    return viewmodelScope.launch(Dispatchers.IO) {
        try {
            block.invoke(this)
        } catch (e: exception) {
            withcontext(Dispatchers.Main) {
                onError?.invoke(e)
            }
        }
    }
}

fun CoroutineScope.simpleSafeLaunch(
    block: suspend CoroutineScope.() -> Unit,
    onError: ((e: exception) -> Unit)? = null,
): Job {
    return this.launch(Dispatchers.IO) {
        try {
            block.invoke(this)
        } catch (e: exception) {
            withcontext(Dispatchers.Main) {
                onError?.invoke(e)
            }
        }
    }
}