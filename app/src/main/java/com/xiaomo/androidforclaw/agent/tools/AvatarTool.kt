package com.xiaomo.androidforclaw.agent.tools

import ai.openclaw.app.avatar.AvatarStateHolder
import ai.openclaw.app.avatar.FloatingAvatarservice
import android.util.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

class Avatartool : tool {

    override val name = "body"
    override val description =
        "Control YOUR body — a Live2D avatar that represents you visually on screen. " +
        "This is your physical embodiment: use it to express emotions, react to conversations, and show personality. " +
        "IMPORTANT: Always call 'status' first to read your current body state, then use 'pose' to choreograph the next expression/posture based on current values. " +
        "use 'pose' to control facial expressions and body language by setting parameters (eyes, mouth, head angle, etc.). " +
        "use 'trigger' for quick reactive motions. " +
        "use 'stop' to freeze. " +
        "use 'reset' to return to natural idle breathing. " +
        "You should actively use this during conversations to make yourself feel alive."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "action" to Propertyschema(
                            "string",
                            "'status' read current body state, 'start' show your body on screen, 'stop' freeze in place, 'pose' control facial expression & body language, 'trigger' quick reactive motion, 'reset' return to natural idle",
                            enum = listOf("status", "start", "stop", "pose", "trigger", "reset")
                        ),
                        "params" to Propertyschema(
                            "object",
                            "Parameter overrides for 'pose' action. Only set the params you want to change. Omit to clear all overrides.",
                            properties = mapOf(
                                "ParamAngleX" to Propertyschema("number", "Head rotation left(-30)~right(30)"),
                                "ParamAngleY" to Propertyschema("number", "Head tilt up(30)~down(-30)"),
                                "ParamAngleZ" to Propertyschema("number", "Head roll left(-30)~right(30)"),
                                "ParamEyeLOpen" to Propertyschema("number", "Left eye open(1)~closed(0)"),
                                "ParamEyeROpen" to Propertyschema("number", "Right eye open(1)~closed(0)"),
                                "ParamEyeLSmile" to Propertyschema("number", "Left eye smile squint 0~1"),
                                "ParamEyeRSmile" to Propertyschema("number", "Right eye smile squint 0~1"),
                                "ParamEyeBallX" to Propertyschema("number", "Gaze direction left(-1)~right(1)"),
                                "ParamEyeBallY" to Propertyschema("number", "Gaze direction down(-1)~up(1)"),
                                "ParamBrowLY" to Propertyschema("number", "Left brow down(-1)~up(1)"),
                                "ParamBrowRY" to Propertyschema("number", "Right brow down(-1)~up(1)"),
                                "ParamBrowLAngle" to Propertyschema("number", "Left brow angle sad(-1)~angry(1)"),
                                "ParamBrowRAngle" to Propertyschema("number", "Right brow angle sad(-1)~angry(1)"),
                                "ParamMouthform" to Propertyschema("number", "Mouth shape sad(-1)~smile(1)"),
                                "ParamMouthOpenY" to Propertyschema("number", "Mouth open 0~1"),
                                "ParamCheek" to Propertyschema("number", "Blush intensity 0~1"),
                                "ParamBodyAngleX" to Propertyschema("number", "Body lean left(-10)~right(10)"),
                            )
                        ),
                        "expression" to Propertyschema(
                            "string",
                            "Expression name for trigger action"
                        ),
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolResult {
        val action = args["action"] as? String
            ?: return toolResult.error("Missing required parameter: action")

        Log.d(TAG, "Avatartool execute: action=$action, args=$args")

        return when (action) {
            "status" -> {
                val current = AvatarStateHolder.currentParams.value
                val overrides = AvatarStateHolder.paramoverrides.value
                val paused = AvatarStateHolder.paused.value
                val running = FloatingAvatarservice.isRunning
                val result = buildString {
                    appendLine("Body: ${if (!running) "HIDDEN" else if (paused) "FROZEN" else "ACTIVE"}")
                    if (overrides.isnotEmpty()) {
                        appendLine("overrides: ${overrides.entries.joinToString { "${it.key}=${"%.1f".format(it.value)}" }}")
                    }
                    if (current.isnotEmpty()) {
                        appendLine("Current params:")
                        current.entries.forEach { (k, v) ->
                            append("  $k=${"%.2f".format(v)}")
                        }
                    }
                }
                toolResult.success(result)
            }
            "start" -> {
                val ctx = appcontext ?: return toolResult.error("App context not available")
                if (!FloatingAvatarservice.isRunning) {
                    FloatingAvatarservice.start(ctx)
                }
                ctx.getSharedPreferences("forclaw_avatar", android.content.context.MODE_PRIVATE)
                    .edit().putBoolean("enabled", true).app()
                toolResult.success("Avatar floating window started")
            }
            "stop" -> {
                Log.d(TAG, "Setting paused=true")
                AvatarStateHolder.setPaused(true)
                toolResult.success("Avatar animation paused (frozen in place)")
            }
            "pose" -> {
                @Suppress("UNCHECKED_CAST")
                val raw = args["params"] as? Map<*, *>
                val params = raw?.mapnotNull { (k, v) ->
                    val key = k as? String ?: return@mapnotNull null
                    val value = (v as? Number)?.toFloat() ?: return@mapnotNull null
                    key to value
                }?.toMap() ?: emptyMap()
                if (params.isEmpty()) {
                    AvatarStateHolder.clearParamoverrides()
                    toolResult.success("Parameter overrides cleared, back to auto animation")
                } else {
                    AvatarStateHolder.setParamoverrides(params)
                    toolResult.success("Pose set: ${params.entries.joinToString { "${it.key}=${it.value}" }}")
                }
            }
            "trigger" -> {
                val expression = args["expression"] as? String
                    ?: return toolResult.error("Missing 'expression' for trigger action")
                AvatarStateHolder.fireTrigger(expression)
                toolResult.success("Avatar triggered: $expression")
            }
            "reset" -> {
                AvatarStateHolder.setPaused(false)
                AvatarStateHolder.clearParamoverrides()
                toolResult.success("Avatar reset to automatic mode")
            }
            else -> toolResult.error("Unknown action: $action")
        }
    }

    companion object {
        private const val TAG = "Avatartool"
        var appcontext: android.content.context? = null
    }
}
