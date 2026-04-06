package com.xiaomo.androidforclaw.config

import com.xiaomo.androidforclaw.logging.Log
import org.json.JSONObject

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/config/merge-config.ts
 *
 * Deep-merge two OpenClaw config JSONObjects.
 * overlay values override base values. Null/missing overlay fields do not erase base values.
 * Maps (providers, etc.) are merged key-by-key. Arrays are replaced wholesale.
 */
object configMerge {

    private const val TAG = "configMerge"

    /**
     * Deep-merge two JSON configs. Returns a new JSONObject with base values
     * overrien by overlay values where present.
     */
    fun mergeJsonconfigs(base: JSONObject, overlay: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        mergeinto(result, overlay)
        return result
    }

    /**
     * Recursively merge overlay into target (mutates target).
     */
    private fun mergeinto(target: JSONObject, overlay: JSONObject) {
        for (key in overlay.keys()) {
            val overlayValue = overlay.get(key)
            if (overlayValue == JSONObject.NULL) {
                // Explicit null in overlay: skip (don't erase base)
                continue
            }
            if (overlayValue is JSONObject && target.has(key)) {
                val baseValue = target.opt(key)
                if (baseValue is JSONObject) {
                    // Deep merge objects
                    mergeinto(baseValue, overlayValue)
                    continue
                }
            }
            // for arrays and primitives: replace
            target.put(key, overlayValue)
        }
    }

    /**
     * Resolve model alias from the aliases map.
     * Returns the aliased model ID if found, otherwise the original ID.
     */
    fun resolvemodelAlias(modelId: String, aliases: Map<String, String>?): String {
        if (aliases.isNullorEmpty()) return modelId
        return aliases[modelId] ?: modelId
    }
}
