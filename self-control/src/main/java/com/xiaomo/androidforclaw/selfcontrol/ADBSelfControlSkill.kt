package com.xiaomo.androidforclaw.selfcontrol

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ADB Self-Control Skill
 *
 * Used by development computers, remote invocation of PhoneForClaw's Self-Control features via ADB.
 *
 * This Skill encapsulates ADB commands, allowing developers to remotely control PhoneForClaw from their computer.
 *
 * Use cases:
 * - CI/CD automated testing
 * - Remote debugging and configuration
 * - Quick testing during development
 * - Batch control of multiple devices
 *
 * Note:
 * - This Skill runs on the development computer (not on Android device)
 * - Requires ADB (Android Debug Bridge) installed
 * - Requires device connected via USB or network ADB
 *
 * Examples (Python/Shell):
 * ```python
 * # Using Python
 * import subprocess
 *
 * def adb_self_control(skill, **kwargs):
 *     args = " ".join([f"--extra {k}:s:{v}" for k, v in kwargs.items()])
 *     cmd = f'alb shell content call --uri content://com.xiaomo.androidforclaw.selfcontrol/execute --method {skill} {args}'
 *     result = subprocess.check_output(cmd, shell=True)
 *     return result.decode()
 *
 * # Page navigation
 * adb_self_control("navigate_app", page="config")
 *
 * # Configuration management
 * adb_self_control("manage_config", operation="get", key="exploration_mode")
 * ```
 *
 * ```bash
 * # Using Shell script
 * ./self-control-adb.sh navigate_app page=config
 * ./self-control-adb.sh manage_config operation=get key=exploration_mode
 * ```
 */
class ADBSelfControlSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ADBSelfControlSkill"

        /**
         * Generate ADB command string
         */
        fun generateADBCommand(skill: String, args: Map<String, Any?>): String {
            val extras = args.map { (key, value) ->
                when (value) {
                    is Int -> "--extra $key:i:$value"
                    is Long -> "--extra $key:l:$value"
                    is Boolean -> "--extra $key:b:$value"
                    is Float -> "--extra $key:f:$value"
                    is Double -> "--extra $key:d:$value"
                    else -> "--extra $key:s:$value"
                }
            }.joinToString(" ")

            return "adb shell content call " +
                    "--uri content://com.xiaomo.androidforclaw.selfcontrol/execute " +
                    "--method $skill " +
                    extras
        }

        /**
         * Generate Broadcast command string
         */
        fun generateBroadcastCommand(skill: String, args: Map<String, Any?>): String {
            val extras = args.map { (key, value) ->
                when (value) {
                    is Int -> "--ei $key $value"
                    is Long -> "--el $key $value"
                    is Boolean -> "--ez $key $value"
                    is Float -> "--ef $key $value"
                    is Double -> "--ed $key $value"
                    else -> "--es $key $value"
                }
            }.joinToString(" ")

            return "adb shell am broadcast " +
                    "-a com.xiaomo.androidforclaw.SELF_CONTROL " +
                    "--es skill $skill " +
                    extras
        }

        /**
         * Generate helper script command string
         */
        fun generateScriptCommand(skill: String, args: Map<String, Any?>): String {
            val params = args.map { (key, value) ->
                when (value) {
                    is Int -> "$key:i=$value"
                    is Long -> "$key:l=$value"
                    is Boolean -> "$key:b=$value"
                    is Float -> "$key:f=$value"
                    is Double -> "$key:d=$value"
                    else -> "$key=$value"
                }
            }.joinToString(" ")

            return "./self-control-adb.sh $skill $params"
        }
    }

    override val name = "adb_self_control"

    override val description = """
        Remote invocation of PhoneForClaw's Self-Control features via ADB (for development computers).

        This Skill generates ADB commands to remotely control PhoneForClaw on Android devices from a development computer.

        Supports 3 command formats:
        - ContentProvider (recommended): returns structured data
        - Broadcast: better compatibility, async execution
        - Shell Script: simplified encapsulation, easy to use

        Use cases:
        - CI/CD automated testing
        - Remote debugging and configuration
        - Quick testing and validation
        - Batch control of multiple devices

        Examples:
        ```
        # Get ContentProvider command
        {
          "method": "provider",
          "skill": "navigate_app",
          "args": {"page": "config"}
        }
        Returns: adb shell content call --uri ... --method navigate_app --extra page:s:config

        # Get Broadcast command
        {
          "method": "broadcast",
          "skill": "manage_config",
          "args": {"operation": "get", "key": "exploration_mode"}
        }
        Returns: adb shell am broadcast -a ... --es skill manage_config --es operation get --es key exploration_mode

        # Get script command
        {
          "method": "script",
          "skill": "control_service",
          "args": {"operation": "hide_float"}
        }
        Returns: ./self-control-adb.sh control_service operation=hide_float
        ```

        Note:
        - This Skill only generates command strings, does not actually execute
        - Run generated commands on development computer
        - Device must be connected via USB or network ADB
    """.trimIndent()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "method" to PropertySchema(
                            type = "string",
                            description = "ADB invocation method",
                            enum = listOf("provider", "broadcast", "script")
                        ),
                        "skill" to PropertySchema(
                            type = "string",
                            description = "Target Skill name"
                        ),
                        "args" to PropertySchema(
                            type = "object",
                            description = "Skill parameters (JSON object)"
                        )
                    ),
                    required = listOf("method", "skill")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val method = args["method"] as? String
            ?: return SkillResult.error("Missing required parameter: method")

        val skill = args["skill"] as? String
            ?: return SkillResult.error("Missing required parameter: skill")

        val skillArgs = (args["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: emptyMap()

        return try {
            val command = when (method) {
                "provider" -> generateADBCommand(skill, skillArgs)
                "broadcast" -> generateBroadcastCommand(skill, skillArgs)
                "script" -> generateScriptCommand(skill, skillArgs)
                else -> return SkillResult.error("Unknown method: $method. Use: provider, broadcast, script")
            }

            Log.d(TAG, "Generated ADB command: $command")

            val output = buildString {
                appendLine("【ADB Self-Control Command】")
                appendLine()
                appendLine("Method: $method")
                appendLine("Skill: $skill")
                appendLine("Args: $skillArgs")
                appendLine()
                appendLine("Command:")
                appendLine(command)
                appendLine()
                appendLine("📋 Copy and run this command on your development machine")
                appendLine()
                appendLine("Alternative methods:")
                when (method) {
                    "provider" -> {
                        appendLine("• Broadcast: ${generateBroadcastCommand(skill, skillArgs)}")
                        appendLine("• Script: ${generateScriptCommand(skill, skillArgs)}")
                    }
                    "broadcast" -> {
                        appendLine("• Provider: ${generateADBCommand(skill, skillArgs)}")
                        appendLine("• Script: ${generateScriptCommand(skill, skillArgs)}")
                    }
                    "script" -> {
                        appendLine("• Provider: ${generateADBCommand(skill, skillArgs)}")
                        appendLine("• Broadcast: ${generateBroadcastCommand(skill, skillArgs)}")
                    }
                }
            }

            SkillResult.success(
                output,
                mapOf(
                    "command" to command,
                    "method" to method,
                    "skill" to skill,
                    "args" to skillArgs
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate ADB command", e)
            SkillResult.error("Failed to generate command: ${e.message}")
        }
    }
}
