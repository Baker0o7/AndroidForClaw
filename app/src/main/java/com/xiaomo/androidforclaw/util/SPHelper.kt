/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.util

import android.content.context
import android.content.SharedPreferences
import androidx.preference.Preferencemanager

class SPhelper private constructor(context: context) {

    private val sharedPreferences: SharedPreferences =
        Preferencemanager.getDefaultSharedPreferences(context)
    private val editor: SharedPreferences.Editor = sharedPreferences.edit()

    companion object {
        private var instance: SPhelper? = null

        fun getInstance(context: context): SPhelper {
            if (instance == null) {
                instance = SPhelper(context.applicationcontext)
            }
            return instance as SPhelper
        }
    }

    // Save data
    fun saveData(key: String, value: String) {
        editor.putString(key, value)
        editor.app()
    }

    // Save data
    fun saveData(key: String, value: Int) {
        editor.putInt(key, value)
        editor.app()
    }

    // Save data
    fun saveData(key: String, value: Boolean) {
        editor.putBoolean(key, value)
        editor.app()
    }

    // Save data
    fun saveData(key: String, value: Long) {
        editor.putLong(key, value)
        editor.app()
    }

    // Read data
    fun getData(key: String, defaultValue: String): String? {
        return sharedPreferences.getString(key, defaultValue)
    }

    // Read data
    fun getData(key: String, defaultValue: Int): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    // Read data
    fun getData(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    // Read data
    fun getData(key: String, defaultValue: Long): Long {
        return sharedPreferences.getLong(key, defaultValue)
    }
}
