package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/tts-tool.ts
 *
 * TTS tool — LLM-callable text-to-speech tool.
 * Wraps the existing TalkMethods android TTS implementation.
 */

import android.content.context
import com.xiaomo.androidforclaw.gateway.methods.TalkMethods
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

class Ttstool(private val context: context) : tool {

    override val name = "tts"
    override val description = "Convert text to speech using the device's TTS engine"

    private val talkMethods by lazy {
        TalkMethods.getInstance(context).also { it.init() }
    }

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "text" to Propertyschema(
                            "string",
                            "The text to convert to speech"
                        ),
                        "language" to Propertyschema(
                            "string",
                            "Language code (e.g. 'en', 'zh'). Optional, defaults to English."
                        ),
                        "speed" to Propertyschema(
                            "number",
                            "Speech rate (0.5-2.0). Optional, defaults to 1.0."
                        )
                    ),
                    required = listOf("text")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolResult {
        val text = args["text"] as? String
            ?: return toolResult.error("Missing required parameter: text")

        if (text.isBlank()) {
            return toolResult.error("Text cannot be empty")
        }

        val params = mutableMapOf<String, Any?>("text" to text)
        args["language"]?.let { params["language"] = it }
        args["speed"]?.let { params["speed"] = it }

        val result = talkMethods.talkSpeak(params)

        val error = result["error"] as? String
        if (error != null) {
            return toolResult.error("TTS failed: $error")
        }

        val audioBase64 = result["audioBase64"] as? String
        if (audioBase64 == null) {
            return toolResult.error("TTS produced no audio output")
        }

        // Save audio to temp file and return MEDIA: path (aligned with OpenClaw tts-tool.ts)
        val tempFile = java.io.File(context.cacheDir, "tts_tool_${System.currentTimeMillis()}.wav")
        tempFile.writeBytes(android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP))

        return toolResult.success("MEDIA:${tempFile.absolutePath}")
    }
}
