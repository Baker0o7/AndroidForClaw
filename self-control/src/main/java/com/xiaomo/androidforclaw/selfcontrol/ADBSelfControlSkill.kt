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
 * For use on development machines, remotely controlling PhoneForClaw's Self-Control features via ADB.
 *
 * This Skill wraps ADB commands, allowing developers to remotely control PhoneForClaw from their computer.
 *
 * Use cases:
 * - CI/CD automated testing
 * - Remote debugging and configuration
 * - Quick testing during development
 * - Batch control of multiple devices
 *
 * Note:
 * - This Skill runs on the development machine (not the Android device)
 * - Requires ADB (Android Debug Bridge) to be installed
 * - Device must be connected via USB or network ADB
 *
 * Examples (Python/Shell):
 * ```python
 * # Using Python
 * import subprocess
 *
 * def adb_self_control(skill, **kwargs):
 *     args = " ".join([f"--extra {k}:s:{v}" for k, v in kwargs.items()])
 *     cmd = f'adb shell content call --uri content://com.xiaomo.androidforclaw.selfcontrol/execute --method {skill} {args}'
 *     result = subprocess.check_output(cmd, shell=True)
 *     return result.decode()
 *
 * # Page navigation
 * adb_self_control("navigate_app", page="config")
 *
 * # Config management
 * adb_self_control("manage_config", operation="get", key="exploration_mode")
 * ```
 *
 * ```bash
 * # 使用 Shell 脚本
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
        Remotely control PhoneForClaw's Self-Control features via ADB (for use on development machines).

        This Skill generates ADB commands for remote control of PhoneForClaw on Android devices.

        Supports 3 command formats:
        - ContentProvider (recommended): Returns structured data
        - Broadcast: Good compatibility, async execution
        - Shell Script: Simplified wrapper, easy to use

        Use cases:
        - CI/CD automated testing
        - Remote debugging and configuration
        - Quick testing and verification
        - Batch control of multiple devices

        示例：
        ```
        # 获取 ContentProvider 命令
        {
          "method": "provider",
          "skill": "navigate_app",
          "args": {"page": "config"}
        }
        返回：adb shell content call --uri ... --method navigate_app --extra page:s:config

        # 获取 Broadcast 命令
        {
          "method": "broadcast",
          "skill": "manage_config",
          "args": {"operation": "get", "key": "exploration_mode"}
        }
        返回：adb shell am broadcast -a ... --es skill manage_config --es operation get --es key exploration_mode

        # 获取脚本命令
        {
          "method": "script",
          "skill": "control_service",
          "args": {"operation": "hide_float"}
        }
        返回：./self-control-adb.sh control_service operation=hide_float
        ```

        Note:
        - This Skill only generates command strings, does not actually execute
        - Generated commands must be run on the development machine
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
                            description = "ADB call method",
                            enum = listOf("provider", "broadcast", "script")
                        ),
                        "skill" to PropertySchema(
                            type = "string",
                            description = "Target Skill name"
                        ),
                        "args" to PropertySchema(
                            type = "object",
                            description = "Skill arguments (JSON object)"
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
